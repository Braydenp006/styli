package syncdev

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

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
	devs := must(devices.Open(root))
	key := must(devicekey.Load(t.TempDir()))
	ln := must(net.Listen("tcp", "127.0.0.1:0"))
	s := syncsrv.NewServer(syncsrv.Deps{Vault: v, Logf: t.Logf, Version: "test"}, key, devs)
	go s.Serve(ln)
	t.Cleanup(func() { s.Shutdown(context.Background()) })
	return &atrium{v, devs, key, ln.Addr().String()}
}

// device is a paired device with its own vault and engine.
type device struct {
	v *vault.Vault
	e *Engine
	k *devicekey.Key
}

func (a *atrium) device(t *testing.T) *device {
	t.Helper()
	v := must(vault.Open(t.TempDir()))
	k := must(devicekey.Load(t.TempDir()))
	if err := a.devs.Add(k.ID, "Test device"); err != nil {
		t.Fatal(err)
	}
	e := New(v, nil, syncsrv.ClientTLS(k, a.key.ID), a.addr, t.Logf)
	e.Merge = func(context.Context, string, *Base, vault.Group, vault.Group) error {
		t.Error("merge called")
		return nil
	}
	return &device{v, e, k}
}

func (d *device) sync(t *testing.T) Status {
	t.Helper()
	if err := d.e.Sync(context.Background()); err != nil {
		t.Fatalf("sync: %v", err)
	}
	return d.e.Status()
}

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

func TestPushPullAndMove(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.device(t), a.device(t)

	g := must(d1.v.CreateNote("Ideas.md", []byte("one\n")))
	noWaiting(t, d1.sync(t))
	if got := text(t, a.v, g.ID); got != "one\n" {
		t.Fatalf("atrium has %q", got)
	}

	noWaiting(t, d2.sync(t))
	if got := text(t, d2.v, g.ID); got != "one\n" {
		t.Fatalf("second device has %q", got)
	}

	write(t, d2.v, g.ID, "one\ntwo\n")
	d2.sync(t)
	d1.sync(t)
	if got := text(t, d1.v, g.ID); got != "one\ntwo\n" {
		t.Fatalf("edit did not travel: %q", got)
	}

	if err := a.v.Move(g.ID, "Projects"); err != nil {
		t.Fatal(err)
	}
	d1.sync(t)
	if p := must(d1.v.Get(g.ID)).Note.Path; p != "Projects/Ideas.md" {
		t.Fatalf("move did not travel: %s", p)
	}

	// Nothing left to do: another pass changes nothing on either side.
	aSeq, dSeq := a.v.Seq(), d1.v.Seq()
	noWaiting(t, d1.sync(t))
	if a.v.Seq() != aSeq || d1.v.Seq() != dSeq {
		t.Fatal("an idle pass changed something")
	}
}

func TestAttachedFilesTravel(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.device(t), a.device(t)
	g := must(d1.v.ImportFile("scan.pdf", strings.NewReader("%PDF-1.7 pretend")))
	d1.sync(t)
	noWaiting(t, d2.sync(t))
	f := must(d2.v.Get(g.ID))
	if len(f.Files) != 1 || f.Files[0].Path != "scan.pdf" {
		t.Fatalf("%+v", f)
	}
	b := must(os.ReadFile(filepath.Join(d2.v.Root(), "scan.pdf")))
	if string(b) != "%PDF-1.7 pretend" {
		t.Fatalf("%q", b)
	}
}

// A crash between a step and writing the base leaves local equal to remote;
// the next pass only records the base, and changes nothing on either side.
func TestLostBaseIsRecordedAgain(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.device(t), a.device(t)
	g := must(d1.v.CreateNote("n.md", []byte("x")))
	d1.sync(t)
	d2.sync(t)

	for _, d := range []*device{d1, d2} { // d1 pushed it, d2 pulled it
		for _, dropCursor := range []bool{false, true} {
			os.Remove(d.e.rec.basePath(g.ID))
			if dropCursor {
				os.Remove(filepath.Join(d.v.Root(), ".ids", "sync", "cursor"))
			}
			aSeq, dSeq := a.v.Seq(), d.v.Seq()
			noWaiting(t, d.sync(t))
			if b, _ := d.e.rec.base(g.ID); b == nil || string(b.Text) != "x" {
				t.Fatalf("base not recorded again: %+v", b)
			}
			if a.v.Seq() != aSeq || d.v.Seq() != dSeq {
				t.Fatal("recording the base changed a vault")
			}
		}
	}
}

// The atrium refuses a push because it has an outside edit it hasn't scanned:
// its state still equals the device's base, so this is not a merge.
func TestRefusalWithSameBaseIsNotAMerge(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(d.v.CreateNote("n.md", []byte("base")))
	d.sync(t)

	onDisk(t, a.v, g.ID, "edited on the atrium, not scanned")
	write(t, d.v, g.ID, "edited on the device")
	s := d.sync(t) // Merge fails the test if called
	if len(s.Waiting) != 1 || !strings.Contains(s.Waiting[0].Reason, "catching up") {
		t.Fatalf("%+v", s.Waiting)
	}
	if got := text(t, d.v, g.ID); got != "edited on the device" {
		t.Fatalf("device text %q", got)
	}
	b := must(os.ReadFile(filepath.Join(a.v.Root(), "n.md")))
	if string(b) != "edited on the atrium, not scanned" {
		t.Fatalf("atrium text %q", b)
	}
}

func TestBothChanged(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(d.v.CreateNote("n.md", []byte("base\n")))
	d.sync(t)
	write(t, a.v, g.ID, "base\natrium\n")
	write(t, d.v, g.ID, "base\ndevice\n")

	// No merger yet: held, nothing lost on either side.
	d.e.Merge = nil
	s := d.sync(t)
	if len(s.Waiting) != 1 || !strings.Contains(s.Waiting[0].Reason, "merg") || s.Waiting[0].Path != "n.md" {
		t.Fatalf("%+v", s.Waiting)
	}
	if text(t, d.v, g.ID) != "base\ndevice\n" || text(t, a.v, g.ID) != "base\natrium\n" {
		t.Fatal("a side lost its text")
	}

	// With a merger: it sees all three states, and its result is pushed.
	d.e.Merge = func(_ context.Context, id string, base *Base, local, remote vault.Group) error {
		if string(base.Text) != "base\n" || local.Note.Version == remote.Note.Version {
			t.Errorf("merge got base %q", base.Text)
		}
		write(t, d.v, id, "base\ndevice\natrium\n")
		return nil
	}
	noWaiting(t, d.sync(t))
	if got := text(t, a.v, g.ID); got != "base\ndevice\natrium\n" {
		t.Fatalf("merge not pushed: %q", got)
	}
}

// A pull never overwrites a local edit the scan hasn't seen yet.
func TestPullKeepsUnscannedEdit(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(d.v.CreateNote("n.md", []byte("base")))
	d.sync(t)
	write(t, a.v, g.ID, "from the atrium")
	onDisk(t, d.v, g.ID, "typed here, not scanned")
	s := d.sync(t)
	if len(s.Waiting) != 1 {
		t.Fatalf("%+v", s.Waiting)
	}
	b := must(os.ReadFile(filepath.Join(d.v.Root(), "n.md")))
	if string(b) != "typed here, not scanned" {
		t.Fatalf("unscanned edit overwritten: %q", b)
	}
}

// A group that can't sync holds the cursor back, so its change is read again;
// later groups in the feed still sync.
func TestWaitingGroupHoldsCursor(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	d.e.Merge = nil
	x := must(d.v.CreateNote("x.md", []byte("x")))
	d.sync(t)
	write(t, a.v, x.ID, "x atrium")
	write(t, d.v, x.ID, "x device")
	xRev := must(a.v.Get(x.ID)).Rev
	y := must(a.v.CreateNote("y.md", []byte("y")))

	d.sync(t)
	if got := text(t, d.v, y.ID); got != "y" {
		t.Fatalf("later group not pulled: %q", got)
	}
	if c := must(d.e.rec.cursor()); c.Since >= xRev {
		t.Fatalf("cursor %d moved past the waiting group (rev %d)", c.Since, xRev)
	}
}

// A restored atrium (new epoch) makes the device read everything again. Revs
// are refreshed where the state matches, and nothing is deleted or pushed.
func TestNewEpochFullCheck(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	keep := must(d.v.CreateNote("keep.md", []byte("k")))
	gone := must(d.v.CreateNote("gone.md", []byte("g")))
	d.sync(t)

	// Pretend the cursor came from another epoch and the base's numbering is off.
	d.e.rec.saveCursor(Cursor{Epoch: "old", Since: 999})
	b := must(d.e.rec.base(keep.ID))
	b.Rev += 1000
	d.e.rec.saveBase(keep.ID, *b)
	if err := a.v.Trash(gone.ID); err != nil {
		t.Fatal(err)
	}

	aSeq := a.v.Seq()
	noWaiting(t, d.sync(t))
	if b := must(d.e.rec.base(keep.ID)); b.Rev != must(a.v.Get(keep.ID)).Rev {
		t.Fatalf("rev not refreshed: %d", b.Rev)
	}
	if text(t, d.v, gone.ID) != "g" {
		t.Fatal("a group gone from the atrium was touched here")
	}
	if a.v.Seq() != aSeq {
		t.Fatal("the full check changed the atrium")
	}
	if c := must(d.e.rec.cursor()); c.Epoch != a.v.Epoch() {
		t.Fatalf("cursor epoch %q", c.Epoch)
	}

	// And a later edit pushes cleanly on the refreshed rev.
	write(t, d.v, keep.ID, "k2")
	noWaiting(t, d.sync(t))
	if text(t, a.v, keep.ID) != "k2" {
		t.Fatal("push after the full check failed")
	}
}

// Pull side: the atrium's note needs a path a never-synced local note has.
func TestNameClashOnPull(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	theirs := must(a.v.CreateNote("Untitled.md", []byte("atrium's")))
	mine := must(d.v.CreateNote("Untitled.md", []byte("mine")))

	noWaiting(t, d.sync(t))
	if p := must(d.v.Get(theirs.ID)).Note.Path; p != "Untitled.md" || text(t, d.v, theirs.ID) != "atrium's" {
		t.Fatalf("atrium's note at %s", p)
	}
	if p := must(d.v.Get(mine.ID)).Note.Path; p != "Untitled 2.md" {
		t.Fatalf("local note at %s", p)
	}
	if text(t, a.v, mine.ID) != "mine" { // renamed, then pushed in the same pass
		t.Fatal("renamed note not pushed")
	}
}

// Push side: the name is taken on the atrium by a note this device hasn't
// pulled yet (it arrived after the device read the feed).
func TestNameClashOnPush(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	d.sync(t)
	theirs := must(a.v.CreateNote("N.md", []byte("atrium's")))
	d.e.rec.saveCursor(Cursor{Epoch: a.v.Epoch(), Since: a.v.Seq()}) // the device "already read" past it
	mine := must(d.v.CreateNote("N.md", []byte("mine")))

	s := d.sync(t)
	if len(s.Waiting) != 1 || !strings.Contains(s.Waiting[0].Reason, "renamed") {
		t.Fatalf("%+v", s.Waiting)
	}
	noWaiting(t, d.sync(t))
	if p := must(a.v.Get(mine.ID)).Note.Path; p != "N 2.md" || text(t, a.v, mine.ID) != "mine" {
		t.Fatalf("pushed to %s", p)
	}
	if text(t, a.v, theirs.ID) != "atrium's" {
		t.Fatal("atrium's note changed")
	}
}

func TestRevokedDeviceCannotSync(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	a.devs.Revoke(d.k.ID)
	if err := d.e.Sync(context.Background()); err == nil {
		t.Fatal("revoked device synced")
	}
	if d.e.Status().Error == "" {
		t.Fatal("status has no error")
	}
}
