package syncdev

import (
	"context"
	"errors"
	"math/rand/v2"
	"strings"
	"sync"
	"testing"
	"time"

	"styli/core/internal/vault"
)

// try runs f, treating a panic (a file moved while it was being read) as "not yet".
func try(f func() bool) (ok bool) {
	defer func() {
		if recover() != nil {
			ok = false
		}
	}()
	return f()
}

func waitFor(t *testing.T, what string, f func() bool) {
	t.Helper()
	for end := time.Now().Add(20 * time.Second); time.Now().Before(end); time.Sleep(20 * time.Millisecond) {
		if try(f) {
			return
		}
	}
	t.Fatalf("gave up waiting for %s", what)
}

// appendRetry appends to a note that the running engine may be rewriting.
func appendRetry(t *testing.T, v *vault.Vault, id, more string) {
	t.Helper()
	for i := 0; i < 20; i++ {
		_, ver, err := v.ReadNote(id)
		if err != nil {
			t.Fatal(err)
		}
		b, _, _ := v.ReadNote(id)
		if _, err := v.WriteNote(id, append(b, more...), ver); !errors.Is(err, vault.ErrConflict) {
			if err != nil {
				t.Fatal(err)
			}
			return
		}
	}
	t.Fatal("could not edit the note")
}

// Run on its own, over a network that loses a quarter of its requests and
// replies, with edits made on both devices at once: no pass is called by hand.
func TestRunConvergesOverAFlakyNetwork(t *testing.T) {
	min, max, retry := MinBackoff, MaxBackoff, Retry
	MinBackoff, MaxBackoff, Retry = 5*time.Millisecond, 40*time.Millisecond, 40*time.Millisecond
	t.Cleanup(func() { MinBackoff, MaxBackoff, Retry = min, max, retry })

	w := newWorld(t)
	for i, d := range w.devices() {
		d.net.rate, d.net.rng = 0.25, rand.New(rand.NewPCG(uint64(i+1), 7))
	}
	ctx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	for _, d := range w.devices() {
		wg.Add(1)
		go func() { defer wg.Done(); d.e.Run(ctx) }()
	}
	stop := func() { cancel(); wg.Wait() }
	defer stop()

	g := must(w.d1.v.CreateNote("Live.md", []byte("one\n")))
	waitFor(t, "the note to reach the other device", func() bool { return tree(t, w.d2.root)["Live.md"] == "one\n" })

	appendRetry(t, w.d2.v, g.ID, "two\n")
	waitFor(t, "an edit to travel back", func() bool {
		return tree(t, w.d1.root)["Live.md"] == "one\ntwo\n" && tree(t, w.a.v.Root())["Live.md"] == "one\ntwo\n"
	})

	appendRetry(t, w.d1.v, g.ID, "from d1\n")
	appendRetry(t, w.d2.v, g.ID, "from d2\n")
	waitFor(t, "both devices' edits to meet", func() bool {
		a, d1, d2 := tree(t, w.a.v.Root()), tree(t, w.d1.root), tree(t, w.d2.root)
		return equal(a, d1) && equal(a, d2) && strings.Contains(a["Live.md"], "from d1") && strings.Contains(a["Live.md"], "from d2")
	})

	stop()
	settle(t, w.a, w.devices()...)
	all := everything(t, w.a.v, w.d1.v, w.d2.v)
	for _, s := range []string{"one\n", "two\n", "from d1\n", "from d2\n"} {
		if !strings.Contains(all, s) {
			t.Fatalf("lost %q", s)
		}
	}
}
