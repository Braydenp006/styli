package syncdev

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// dead is an address nothing listens on: connecting is refused at once.
const dead = "127.0.0.1:1"

// reaching makes d try the given addresses in order, and records which address
// the engine says worked.
func reaching(d *device, addrs ...string) (saved *[]string) {
	d.e.c.last, d.e.c.given = addrs[0], addrs
	saved = new([]string)
	d.e.c.worked = func(a string) { *saved = append(*saved, a) }
	return saved
}

// An address that cannot be connected to is passed over, and what the device
// pushes still arrives whole (the request body was not used up by the failure).
func TestSyncMovesOnFromAnAddressThatCannotBeReached(t *testing.T) {
	a := newAtrium(t)
	d := a.deviceWith(t, nil)
	saved := reaching(d, dead, a.addr)
	data := bigFile(100000)
	g := must(d.v.CreateNote("N.md", []byte("hello\n")))
	must(d.v.AddFile(g.ID, "scan.bin", bytes.NewReader(data)))

	noWaiting(t, d.sync(t))
	if got, _ := os.ReadFile(filepath.Join(a.v.Root(), "scan.bin")); !bytes.Equal(got, data) {
		t.Fatalf("the atrium has %d bytes of the file", len(got))
	}
	if d.e.c.current() != a.addr || len(*saved) != 1 || (*saved)[0] != a.addr {
		t.Fatalf("current %q, saved %v", d.e.c.current(), *saved)
	}
	if d.e.Status().Atrium != a.addr {
		t.Fatalf("status says %q", d.e.Status().Atrium)
	}
	// The address that worked is tried first from now on, and not saved again.
	*saved = nil
	d.sync(t)
	if len(*saved) != 0 {
		t.Fatalf("saved again: %v", *saved)
	}
}

// What is on the network is looked for only when the last address fails, and
// is tried before the other given addresses.
func TestFindOnlyWhenTheLastAddressFails(t *testing.T) {
	a := newAtrium(t)
	d := a.deviceWith(t, nil)
	saved := reaching(d, a.addr, dead)
	looked := 0
	d.e.c.find = func(context.Context) []string { looked++; return nil }
	d.sync(t)
	if looked != 0 {
		t.Fatalf("looked on the network %d times with a working address", looked)
	}

	var order []string
	d.e.c.last, d.e.c.given = dead, []string{dead, "127.0.0.1:2"}
	d.e.c.find = func(context.Context) []string { order = append(order, "find"); return []string{a.addr} }
	d.net.onRequest = func() {}
	d.sync(t)
	if len(order) != 1 || d.e.c.current() != a.addr || len(*saved) != 1 || (*saved)[0] != a.addr {
		t.Fatalf("find calls %v, current %q, saved %v", order, d.e.c.current(), *saved)
	}
}

// An address where something else answers, with another key, is not an atrium
// for this device: it is passed over and nothing is sent to it.
func TestAnAddressShowingTheWrongKeyIsSkipped(t *testing.T) {
	real, fake := newAtrium(t), newAtrium(t)
	d := real.deviceWith(t, nil)
	reaching(d, fake.addr, real.addr)
	g := must(d.v.CreateNote("Private.md", []byte("secret\n")))
	noWaiting(t, d.sync(t))
	if d.e.c.current() != real.addr {
		t.Fatalf("current %q", d.e.c.current())
	}
	if _, err := fake.v.Get(g.ID); err == nil {
		t.Fatal("the note reached the wrong atrium")
	}
	if got, _ := os.ReadFile(filepath.Join(real.v.Root(), "Private.md")); string(got) != "secret\n" {
		t.Fatalf("the real atrium has %q", got)
	}

	// With only the wrong one, it fails and says so.
	d2 := real.deviceWith(t, nil)
	reaching(d2, fake.addr)
	if err := d2.e.Sync(context.Background()); err == nil {
		t.Fatal("synced with an atrium that showed the wrong key")
	}
}

// An answer from the atrium, even an error, ends the search: the atrium is
// there, so the other addresses are not tried.
func TestAnAnswerFromTheAtriumIsFinal(t *testing.T) {
	a := newAtrium(t)
	d := a.deviceWith(t, nil)
	saved := reaching(d, a.addr, dead)
	d.e.c.find = func(context.Context) []string { t.Error("looked on the network"); return nil }
	var ae *apiError
	if _, err := d.e.c.group(context.Background(), strings.Repeat("a", 26)); !errors.As(err, &ae) || ae.Status != 404 {
		t.Fatalf("want a 404 from the atrium, got %v", err)
	}
	if len(*saved) != 0 || d.e.c.current() != a.addr {
		t.Fatalf("saved %v, current %q", *saved, d.e.c.current())
	}
}

// With no address that can be reached, the pass fails with the reason.
func TestSyncFailsWhenNoAddressCanBeReached(t *testing.T) {
	a := newAtrium(t)
	d := a.deviceWith(t, nil)
	reaching(d, dead, "127.0.0.1:2")
	err := d.e.Sync(context.Background())
	if err == nil || !strings.Contains(d.e.Status().Error, "connect") {
		t.Fatalf("%v / %q", err, d.e.Status().Error)
	}
}
