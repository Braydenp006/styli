package syncdev

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"styli/core/internal/vault"
)

func TestMerge3(t *testing.T) {
	base := "a\nb\nc\n"
	got, conflict := merge3([]byte("A\nb\nc\n"), []byte(base), []byte("a\nb\nC\n"))
	if conflict || string(got) != "A\nb\nC\n" {
		t.Fatalf("clean merge: %q conflict=%v", got, conflict)
	}
	got, conflict = merge3([]byte("mine\nb\nc\n"), []byte(base), []byte("theirs\nb\nc\n"))
	want := "<<<<<<< this device\nmine\n=======\ntheirs\n>>>>>>> atrium\nb\nc\n"
	if !conflict || string(got) != want {
		t.Fatalf("conflict: %q conflict=%v", got, conflict)
	}
	got, conflict = merge3([]byte("x\n"), nil, []byte("y\n")) // no base: both are additions
	if !conflict || !strings.Contains(string(got), "x\n") || !strings.Contains(string(got), "y\n") {
		t.Fatalf("no base: %q", got)
	}
}

func flagOn(t *testing.T, id string, vs ...*vault.Vault) {
	t.Helper()
	for _, v := range vs {
		if !must(v.Get(id)).Conflict {
			t.Fatalf("%s is not flagged in %s", id, v.Root())
		}
	}
}

func TestMergeEditsInDifferentPlaces(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.deviceWith(t, nil), a.deviceWith(t, nil)
	g := must(d1.v.CreateNote("Plan.md", []byte("a\nb\nc\n")))
	d1.sync(t)
	d2.sync(t)
	write(t, d1.v, g.ID, "A\nb\nc\n")
	write(t, d2.v, g.ID, "a\nb\nC\n")
	settle(t, a, d1, d2)
	for _, v := range []*vault.Vault{a.v, d1.v, d2.v} {
		if got := text(t, v, g.ID); got != "A\nb\nC\n" {
			t.Fatalf("%s has %q", v.Root(), got)
		}
		if must(v.Get(g.ID)).Conflict {
			t.Fatal("a clean merge was flagged")
		}
	}
}

func TestMergeSameLinesFlagsEverywhere(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.deviceWith(t, nil), a.deviceWith(t, nil)
	g := must(d1.v.CreateNote("Plan.md", []byte("a\nb\n")))
	d1.sync(t)
	d2.sync(t)
	write(t, d1.v, g.ID, "from one\nb\n")
	write(t, d2.v, g.ID, "from two\nb\n")
	settle(t, a, d1, d2)
	flagOn(t, g.ID, a.v, d1.v, d2.v)
	got := text(t, a.v, g.ID)
	if !strings.Contains(got, "from one") || !strings.Contains(got, "from two") || !strings.Contains(got, "<<<<<<<") {
		t.Fatalf("markers missing: %q", got)
	}

	// Marking it resolved on one device clears it everywhere.
	write(t, d1.v, g.ID, "from one and two\nb\n")
	if err := d1.v.SetConflict(g.ID, false); err != nil {
		t.Fatal(err)
	}
	settle(t, a, d1, d2)
	for _, v := range []*vault.Vault{a.v, d1.v, d2.v} {
		if must(v.Get(g.ID)).Conflict {
			t.Fatal("the flag did not clear")
		}
	}
}

// The device's own text is kept as a version even when history is off.
func TestMergeKeepsOwnTextWithHistoryOff(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.deviceWith(t, nil), a.deviceWith(t, nil)
	g := must(d1.v.CreateNote("Plan.md", []byte("a\nb\n")))
	d1.sync(t)
	d2.sync(t)
	off := false
	if err := d2.v.SetHistory(g.ID, &off); err != nil {
		t.Fatal(err)
	}
	write(t, d1.v, g.ID, "one\nb\n")
	d1.sync(t)
	write(t, d2.v, g.ID, "two\nb\n")
	d2.sync(t)
	if !strings.Contains(everything(t, d2.v), "two\nb\n") {
		t.Fatal("this device's own text was lost")
	}
	var found bool
	for _, ver := range must(d2.v.History(g.ID)) {
		found = found || string(must(d2.v.ReadVersion(g.ID, ver.ID))) == "two\nb\n"
	}
	if !found {
		t.Fatal("this device's text is not in its history")
	}
}

func TestMergeFolderAndText(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.deviceWith(t, nil), a.deviceWith(t, nil)
	g := must(d1.v.CreateNote("Plan.md", []byte("a\n")))
	d1.sync(t)
	d2.sync(t)
	if err := d1.v.Move(g.ID, "Work"); err != nil {
		t.Fatal(err)
	}
	write(t, d2.v, g.ID, "a\nmore\n")
	settle(t, a, d1, d2)
	for _, v := range []*vault.Vault{a.v, d1.v, d2.v} {
		if got := must(v.Get(g.ID)).Note.Path; got != "Work/Plan.md" {
			t.Fatalf("%s: %s", v.Root(), got)
		}
		if text(t, v, g.ID) != "a\nmore\n" {
			t.Fatalf("text %q", text(t, v, g.ID))
		}
	}

	// Both moved it somewhere different: the atrium's choice wins.
	if err := d1.v.Move(g.ID, "One"); err != nil {
		t.Fatal(err)
	}
	if err := d2.v.Move(g.ID, "Two"); err != nil {
		t.Fatal(err)
	}
	d1.sync(t)
	settle(t, a, d1, d2)
	if got := must(d2.v.Get(g.ID)).Note.Path; got != "One/Plan.md" {
		t.Fatalf("the atrium's folder should win, got %s", got)
	}
}

func TestMergeKeepsBothVersionsOfAnAttachedFile(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.deviceWith(t, nil), a.deviceWith(t, nil)
	d2.e.DeviceName = "Tablet"
	g := must(d1.v.ImportFile("scan.pdf", strings.NewReader("original")))
	d1.sync(t)
	d2.sync(t)
	for d, s := range map[*device]string{d1: "from one", d2: "from two"} {
		if err := os.WriteFile(filepath.Join(d.v.Root(), "scan.pdf"), []byte(s), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, _, err := d.v.Rescan(); err != nil {
			t.Fatal(err)
		}
	}
	settle(t, a, d1, d2)
	tr := tree(t, a.v.Root())
	if tr["scan.pdf"] != "from one" || tr["scan (Tablet).pdf"] != "from two" {
		t.Fatalf("atrium holds %v", tr)
	}
	flagOn(t, g.ID, a.v, d1.v, d2.v)
}

// While the atrium keeps changing under a merge, the group waits instead of
// merging for ever.
func TestMergeRoundsAreCapped(t *testing.T) {
	a := newAtrium(t)
	var d *device
	var id string
	rounds := 0
	d = a.deviceWith(t, func(ctx context.Context, gid string, b *Base, l, r vault.Group) error {
		rounds++
		err := d.e.mergeGroup(ctx, gid, b, l, r)
		write(t, a.v, id, strings.Repeat("x", rounds+10)+"\n") // the atrium moves on again
		return err
	})
	g := must(d.v.CreateNote("Plan.md", []byte("a\n")))
	id = g.ID
	d.sync(t)
	write(t, d.v, id, "mine\n")
	write(t, a.v, id, "theirs\n")
	s := d.sync(t)
	if rounds != maxMerges || len(s.Waiting) != 1 || !strings.Contains(s.Waiting[0].Reason, "keeps changing") {
		t.Fatalf("rounds=%d waiting=%+v", rounds, s.Waiting)
	}
}
