package syncdev

// The sync test harness (see "Tests" under Sync protocol in DESIGN.md): a real
// atrium over TLS and devices in temp folders, a network that can drop any
// request or any reply, and crashes at any file write. faults_test.go and
// random_test.go use it.

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"math/rand/v2"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"styli/core/internal/atomicfile"
	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

// ---- the atrium ----

// atrium is a real atrium serving sync over TLS on loopback.
type atrium struct {
	v    *vault.Vault
	devs *devices.List
	key  *devicekey.Key
	addr string
}

func newAtrium(t *testing.T) *atrium {
	t.Helper()
	root := t.TempDir()
	v := must(vault.Open(root))
	v.SetHistoryWindow(0) // every save is its own version, so none can fold away unseen
	devs := must(devices.Open(root))
	key := must(devicekey.Load(t.TempDir()))
	ln := must(net.Listen("tcp", "127.0.0.1:0"))
	s := syncsrv.NewServer(syncsrv.Deps{Vault: v, Logf: t.Logf, Version: "test"}, key, devs)
	go s.Serve(ln)
	t.Cleanup(func() { s.Shutdown(context.Background()) })
	return &atrium{v, devs, key, ln.Addr().String()}
}

// ---- a device ----

// device is a paired device with its own vault and engine.
type device struct {
	t     *testing.T
	a     *atrium
	root  string
	k     *devicekey.Key
	v     *vault.Vault
	e     *Engine
	net   *faultNet
	merge MergeFunc
}

// device makes a paired device whose merge fails the test if it is called.
func (a *atrium) device(t *testing.T) *device {
	return a.deviceWith(t, func(context.Context, string, *Base, vault.Group, vault.Group) error {
		t.Error("merge called")
		return nil
	})
}

// deviceWith makes a paired device that merges with merge (nil: the real one).
func (a *atrium) deviceWith(t *testing.T, merge MergeFunc) *device {
	t.Helper()
	d := &device{t: t, a: a, root: t.TempDir(), k: must(devicekey.Load(t.TempDir())), merge: merge, net: &faultNet{}}
	if err := a.devs.Add(d.k.ID, "Test device"); err != nil {
		t.Fatal(err)
	}
	d.build()
	return d
}

// build opens the device's vault and makes a fresh engine: what a restart does
// (opening the vault finishes any commit a crash left half done).
func (d *device) build() {
	d.v = must(vault.Open(d.root))
	d.v.SetHistoryWindow(0)
	// The app scans at start, which picks up a note file a crash wrote before
	// its record.
	vault.Settle = 0
	if _, _, err := d.v.Rescan(); err != nil {
		d.t.Fatal(err)
	}
	d.e = New(d.v, nil, syncsrv.ClientTLS(d.k, d.a.key.ID), d.a.addr, d.t.Logf)
	d.net.next = d.e.c.hc.Transport
	d.e.c.hc.Transport = d.net
	if d.merge != nil { // nil keeps the real merge
		d.e.Merge = d.merge
	}
}

// sync runs a pass that must succeed.
func (d *device) sync(t *testing.T) Status {
	t.Helper()
	if err := d.e.Sync(context.Background()); err != nil {
		t.Fatalf("sync: %v", err)
	}
	return d.e.Status()
}

// ---- the network ----

var errDropped = errors.New("test network: dropped")

// faultNet is the device's HTTP transport with faults. A request can be
// dropped before it is sent, or its reply can be lost after the atrium has
// handled it (the interesting one: the device doesn't know it went through).
type faultNet struct {
	mu        sync.Mutex
	next      http.RoundTripper
	n         int  // requests so far
	dropReq   int  // drop the n-th request (1-based); 0 = none
	dropReply int  // lose the reply to the n-th request; 0 = none
	down      bool // the device is offline: every request fails
	rate      float64
	rng       *rand.Rand // used with rate: each request faults with this chance
}

// reset forgets counts and planned faults; down and rate stay.
func (f *faultNet) reset() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.n, f.dropReq, f.dropReply = 0, 0, 0
}

func (f *faultNet) requests() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.n
}

func (f *faultNet) RoundTrip(r *http.Request) (*http.Response, error) {
	f.mu.Lock()
	f.n++
	fault := ""
	switch {
	case f.down || f.n == f.dropReq:
		fault = "request"
	case f.n == f.dropReply:
		fault = "reply"
	case f.rate > 0 && f.rng.Float64() < f.rate:
		fault = []string{"request", "reply"}[f.rng.IntN(2)]
	}
	next := f.next
	f.mu.Unlock()
	if fault == "request" {
		if r.Body != nil {
			r.Body.Close()
		}
		return nil, errDropped
	}
	res, err := next.RoundTrip(r)
	if fault == "reply" && err == nil {
		res.Body.Close()
		return nil, errDropped
	}
	return res, err
}

// ---- crashes ----

type crashed struct{}

// writes counts this device's file writes, and if crashAt > 0 stops the
// device with a panic just before write number crashAt, as if the process
// had died there. Writes by the atrium (same process) are not counted.
func (d *device) writes(crashAt int) *atomic.Int64 {
	var n atomic.Int64
	prefix := d.root + string(filepath.Separator)
	atomicfile.SetBeforeWrite(func(dst string) {
		if strings.HasPrefix(dst, prefix) && int(n.Add(1)) == crashAt {
			panic(crashed{})
		}
	})
	d.t.Cleanup(func() { atomicfile.SetBeforeWrite(nil) })
	return &n
}

// trySync runs one pass that may fail or crash. After a crash the device
// restarts. The crash hook is cleared afterwards; the network's counts and
// faults stay for the caller to read (see faultNet.reset).
func (d *device) trySync() (crashedNow bool, err error) {
	defer func() {
		atomicfile.SetBeforeWrite(nil)
		if r := recover(); r != nil {
			if _, ok := r.(crashed); !ok {
				panic(r)
			}
			d.build()
			crashedNow = true
		}
	}()
	return false, d.e.Sync(context.Background())
}

// ---- looking at the result ----

// tree is every file in the vault folder (outside .ids) by path, with its text.
func tree(t *testing.T, root string) map[string]string {
	t.Helper()
	out := map[string]string{}
	err := filepath.WalkDir(root, func(p string, de fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel := must(filepath.Rel(root, p))
		if de.IsDir() {
			if rel == ".ids" {
				return fs.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(de.Name(), atomicfile.TmpPrefix) {
			return nil
		}
		out[filepath.ToSlash(rel)] = string(must(os.ReadFile(p)))
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func fingerprint(t *testing.T, a *atrium, devs []*device) string {
	s := fmt.Sprint(tree(t, a.v.Root()))
	for _, d := range devs {
		s += fmt.Sprint(tree(t, d.root))
	}
	return s
}

// settle syncs every device, with no faults and everyone online, until a
// whole round changes nothing, and fails if that takes too long or any note is
// still waiting. Then every machine must hold the same files.
func settle(t *testing.T, a *atrium, devs ...*device) {
	t.Helper()
	for _, d := range devs {
		d.net.reset()
		d.net.down, d.net.rate = false, 0
	}
	prev := ""
	for round := 0; ; round++ {
		if round == 15 {
			t.Fatal("the devices did not settle")
		}
		for _, d := range devs {
			d.sync(t)
		}
		cur := fingerprint(t, a, devs)
		if cur == prev {
			break
		}
		prev = cur
	}
	for i, d := range devs {
		if w := d.e.Status().Waiting; len(w) > 0 {
			t.Fatalf("device %d still has notes waiting: %+v", i+1, w)
		}
	}
	want := tree(t, a.v.Root())
	for i, d := range devs {
		if got := tree(t, d.root); !equal(got, want) {
			t.Fatalf("device %d differs from the atrium:\n device: %v\n atrium: %v", i+1, got, want)
		}
	}
	for p := range want {
		if strings.Contains(p, "(moving") {
			t.Fatalf("a spare name was left behind: %s", p)
		}
	}
}

func equal(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		if w, ok := b[k]; !ok || w != v {
			return false
		}
	}
	return true
}

// everything is all the text a user could still find: the notes on every
// machine, and every history version on every machine.
func everything(t *testing.T, vs ...*vault.Vault) string {
	t.Helper()
	var b strings.Builder
	for _, v := range vs {
		for _, g := range must(v.List()) {
			if g.Note != nil {
				b.Write(must(os.ReadFile(filepath.Join(v.Root(), g.Note.Path))))
			}
			for _, ver := range must(v.History(g.ID)) {
				b.Write(must(v.ReadVersion(g.ID, ver.ID)))
			}
		}
	}
	return b.String()
}

// ---- small helpers the tests share ----

// must is for setup steps that never fail in a working test.
func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func text(t *testing.T, v *vault.Vault, id string) string {
	t.Helper()
	b, _, err := v.ReadNote(id)
	if err != nil {
		t.Fatalf("read %s: %v", id, err)
	}
	return string(b)
}

func write(t *testing.T, v *vault.Vault, id, s string) {
	t.Helper()
	_, ver, err := v.ReadNote(id)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := v.WriteNote(id, []byte(s), ver); err != nil {
		t.Fatal(err)
	}
}

// onDisk writes a note's file behind the vault's back: an edit no scan has
// seen yet.
func onDisk(t *testing.T, v *vault.Vault, id, s string) {
	t.Helper()
	g := must(v.Get(id))
	if err := os.WriteFile(filepath.Join(v.Root(), g.Note.Path), []byte(s), 0o600); err != nil {
		t.Fatal(err)
	}
}

func noWaiting(t *testing.T, s Status) {
	t.Helper()
	if len(s.Waiting) != 0 {
		t.Fatalf("waiting: %+v", s.Waiting)
	}
}
