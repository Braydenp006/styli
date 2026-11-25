package vault

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// rev reads a group's current Rev.
func rev(t *testing.T, v *Vault, id string) uint64 {
	t.Helper()
	return must(v.Get(id)).Rev
}

func TestEveryChangeTakesANewRev(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	if g.Rev == 0 || g.Rev != v.Seq() {
		t.Fatalf("create: rev %d, seq %d", g.Rev, v.Seq())
	}
	last := g.Rev
	step := func(what string) {
		t.Helper()
		if r := rev(t, v, g.ID); r <= last {
			t.Fatalf("%s: rev %d did not go past %d", what, r, last)
		} else {
			last = r
		}
	}

	_, ver, _ := v.ReadNote(g.ID)
	must(v.WriteNote(g.ID, []byte("two"), ver))
	step("write")
	if err := v.Move(g.ID, "folder"); err != nil {
		t.Fatal(err)
	}
	step("move")
	off := false
	if err := v.SetHistory(g.ID, &off); err != nil {
		t.Fatal(err)
	}
	step("history toggle")
	must(v.AddFile(g.ID, "pic.png", strings.NewReader("png")))
	step("add file")

	// An edit made outside the app, found by a rescan.
	if err := os.WriteFile(filepath.Join(root, "folder", "a.md"), []byte("three"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := v.Rescan(); err != nil {
		t.Fatal(err)
	}
	step("outside edit")

	if err := v.Trash(g.ID); err != nil {
		t.Fatal(err)
	}
	if err := v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	step("restore")

	// A new file dropped into the vault becomes a group with its own number.
	os.WriteFile(filepath.Join(root, "b.md"), []byte("b"), 0o600)
	ch, _, err := v.Rescan()
	if err != nil || len(ch) != 1 || ch[0].Kind != Added {
		t.Fatalf("rescan: %v %v", ch, err)
	}
	if r := rev(t, v, ch[0].ID); r <= last || r != v.Seq() {
		t.Fatalf("adopted file: rev %d, last %d, seq %d", r, last, v.Seq())
	}
}

func TestRevsSurviveReopen(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	must(v.CreateNote("b.md", []byte("two")))
	seq, epoch := v.Seq(), v.Epoch()

	v2 := must(Open(root))
	if v2.Seq() != seq || v2.Epoch() != epoch {
		t.Fatalf("reopen: seq %d epoch %s, want %d %s", v2.Seq(), v2.Epoch(), seq, epoch)
	}
	if rev(t, v2, g.ID) != g.Rev {
		t.Fatal("rev changed on reopen")
	}
	if h := must(v2.CreateNote("c.md", nil)); h.Rev != seq+1 {
		t.Fatalf("next rev %d, want %d", h.Rev, seq+1)
	}
}

// The counter file is written before the record, so the records can never be
// ahead of it, but a lost or old counter file must still never hand out a
// number already used.
func TestCounterRepairsFromRecords(t *testing.T) {
	for _, name := range []string{"deleted", "old"} {
		t.Run(name, func(t *testing.T) {
			v, root := open(t)
			g := must(v.CreateNote("a.md", nil))
			must(v.CreateNote("b.md", nil))
			trashed := must(v.CreateNote("c.md", nil)) // the highest number ends up in the trash
			if err := v.Trash(trashed.ID); err != nil {
				t.Fatal(err)
			}
			seqPath := filepath.Join(root, ".ids", "sync", "seq")
			if name == "deleted" {
				os.Remove(seqPath)
			} else {
				os.WriteFile(seqPath, []byte("1\n"), 0o600)
			}
			v2 := must(Open(root))
			del := trashed.Rev + 1 // the delete's own number
			if v2.Seq() != del {
				t.Fatalf("seq %d, want %d", v2.Seq(), del)
			}
			_, ver, _ := v2.ReadNote(g.ID)
			must(v2.WriteNote(g.ID, []byte("x"), ver))
			if r := rev(t, v2, g.ID); r != del+1 {
				t.Fatalf("rev %d reused or skipped, want %d", r, del+1)
			}
		})
	}
}

// A crash after the counter was written but before the record leaves the
// counter ahead; that only skips a number, which is harmless.
func TestCounterAheadOfRecordsIsKept(t *testing.T) {
	v, root := open(t)
	must(v.CreateNote("a.md", nil))
	writeSeq(filepath.Join(root, ".ids", "sync", "seq"), 10)
	if s := must(Open(root)).Seq(); s != 10 {
		t.Fatalf("seq %d, want 10", s)
	}
}

func TestDamagedCounterStopsOpen(t *testing.T) {
	_, root := open(t)
	os.WriteFile(filepath.Join(root, ".ids", "sync", "seq"), []byte("banana"), 0o600)
	if _, err := Open(root); err == nil {
		t.Fatal("opened with a damaged counter")
	}
}

// Records written before revisions existed get numbers on open.
func TestOldRecordsGetNumbers(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", nil))
	os.WriteFile(filepath.Join(root, ".ids", g.ID), []byte(`{"note":{"path":"a.md","version":"x"},"files":[]}`), 0o600)
	os.RemoveAll(filepath.Join(root, ".ids", "sync"))
	v2 := must(Open(root))
	if r := rev(t, v2, g.ID); r == 0 || r != v2.Seq() {
		t.Fatalf("rev %d, seq %d", r, v2.Seq())
	}
}

func TestRestoredVaultGetsNewEpoch(t *testing.T) {
	v, root := open(t)
	mirror := filepath.Join(t.TempDir(), "seq")
	if restored := must(v.SetSeqMirror(mirror)); restored {
		t.Fatal("fresh vault reported as restored")
	}
	must(v.CreateNote("a.md", nil))
	backup := filepath.Join(t.TempDir(), "backup")
	if err := os.CopyFS(backup, os.DirFS(root)); err != nil {
		t.Fatal(err)
	}
	must(v.CreateNote("b.md", nil))
	must(v.CreateNote("c.md", nil))
	epoch, seq := v.Epoch(), v.Seq()

	// Reopening the same vault is not a restore.
	v2 := must(Open(root))
	if must(v2.SetSeqMirror(mirror)) || v2.Epoch() != epoch {
		t.Fatal("plain reopen reported as restored")
	}

	// The backup has the old counter and the old epoch.
	r := must(Open(backup))
	if r.Epoch() != epoch || r.Seq() >= seq {
		t.Fatalf("backup: epoch %s seq %d", r.Epoch(), r.Seq())
	}
	if !must(r.SetSeqMirror(mirror)) {
		t.Fatal("restore not noticed")
	}
	if r.Epoch() == epoch {
		t.Fatal("epoch not changed")
	}
	// Numbers carry on past the ones the lost changes used.
	if h := must(r.CreateNote("d.md", nil)); h.Rev != seq+1 {
		t.Fatalf("rev %d, want %d", h.Rev, seq+1)
	}
	// And the next start is a normal one.
	r2 := must(Open(backup))
	if must(r2.SetSeqMirror(mirror)) {
		t.Fatal("restore reported twice")
	}
}

func TestDamagedMirrorIsAnError(t *testing.T) {
	v, _ := open(t)
	mirror := filepath.Join(t.TempDir(), "seq")
	os.WriteFile(mirror, []byte("?"), 0o600)
	if _, err := v.SetSeqMirror(mirror); err == nil {
		t.Fatal("damaged mirror accepted")
	}
}
