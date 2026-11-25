package vault

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"styli/core/internal/atomicfile"
)

const dev = "device-1"

// edited stages an edited copy of g (as a device would upload it).
func edited(t *testing.T, v *Vault, g Group, text string) Group {
	t.Helper()
	want := Group{Note: &File{g.Note.Path, stage(t, v, text)}, Files: append([]File{}, g.Files...)}
	return want
}

func TestAddCandidate(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	want := edited(t, v, g, "edited offline")
	if _, err := v.AddCandidate(g.ID, want, dev, EditedAfterDelete); !errors.Is(err, ErrConflict) {
		t.Fatalf("live note: %v", err)
	}
	v.Trash(g.ID)
	c, err := v.AddCandidate(g.ID, want, dev, EditedAfterDelete)
	if err != nil || c.Group.ID != g.ID || c.DeletedAt.IsZero() {
		t.Fatalf("%v %+v", err, c)
	}
	again := must(v.AddCandidate(g.ID, want, dev, EditedAfterDelete)) // its reply was lost
	cs := must(v.ListCandidates())
	if again.Key != c.Key || len(cs) != 1 || cs[0].Group.ID != g.ID || cs[0].Device != dev {
		t.Fatalf("%+v %+v", again, cs)
	}
	if read(t, root, filepath.Join(".ids", "recovery", c.Key, "files", want.Note.Version)) != "edited offline" {
		t.Fatal("content not kept")
	}
	// Content that was never uploaded is refused, and nothing is left behind.
	other := Group{Note: &File{"a.md", hashBytes([]byte("never sent"))}, Files: []File{}}
	if _, err := v.AddCandidate(g.ID, other, dev, EditedAfterDelete); !errors.Is(err, ErrMissingBlob) {
		t.Fatalf("missing upload: %v", err)
	}
	if ents, _ := os.ReadDir(filepath.Join(root, ".ids", "recovery")); len(ents) != 1 {
		t.Fatalf("%d entries", len(ents))
	}
}

// A crash part way leaves only a temporary folder, removed on open.
func TestInterruptedCandidateIsRemovedOnOpen(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	v.Trash(g.ID)
	want := edited(t, v, g, "edited")
	atomicfile.SetBeforeWrite(func(dst string) {
		if filepath.Base(dst) == "record.json" && strings.Contains(dst, "recovery") {
			panic("crash")
		}
	})
	func() {
		defer func() { recover() }()
		v.AddCandidate(g.ID, want, dev, EditedAfterDelete)
	}()
	atomicfile.SetBeforeWrite(nil)
	v2 := must(Open(root))
	if cs := must(v2.ListCandidates()); len(cs) != 0 {
		t.Fatalf("%+v", cs)
	}
	if ents, _ := os.ReadDir(filepath.Join(root, ".ids", "recovery")); len(ents) != 0 {
		t.Fatalf("left behind: %v", ents)
	}
}

func TestTrashKeeping(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	if err := v.TrashKeeping(g.ID, g.Rev, dev); err != nil {
		t.Fatal(err)
	}
	if cs := must(v.ListCandidates()); len(cs) != 0 {
		t.Fatal("a delete of the current version kept a candidate")
	}

	h := must(v.CreateNote("b.md", []byte("one")))
	_, ver, _ := v.ReadNote(h.ID)
	must(v.WriteNote(h.ID, []byte("newer"), ver))
	// An edit not scanned yet is refused, and nothing is deleted.
	os.WriteFile(filepath.Join(root, "b.md"), []byte("unscanned"), 0o600)
	if err := v.TrashKeeping(h.ID, h.Rev, dev); !errors.Is(err, ErrConflict) {
		t.Fatalf("unscanned edit: %v", err)
	}
	os.WriteFile(filepath.Join(root, "b.md"), []byte("newer"), 0o600)
	if err := v.TrashKeeping(h.ID, h.Rev, dev); err != nil {
		t.Fatal(err)
	}
	cs := must(v.ListCandidates())
	if len(cs) != 1 || cs[0].Why != DeletedWhileChanged || read(t, root, filepath.Join(".ids", "recovery", cs[0].Key, "files", cs[0].Group.Note.Version)) != "newer" {
		t.Fatalf("%+v", cs)
	}
	if _, err := v.Get(h.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("the delete did not stand")
	}
}

func TestRestoreCandidate(t *testing.T) {
	for _, where := range []string{"in the trash", "emptied from the trash", "live again"} {
		t.Run(where, func(t *testing.T) {
			v, root := open(t)
			v.SetHistoryWindow(0)
			g := must(v.CreateNote("a.md", []byte("deleted version")))
			g = must(v.AddFile(g.ID, "pic.png", strings.NewReader("png")))
			v.Trash(g.ID)
			stage(t, v, "png") // a device uploads every file of its copy
			c := must(v.AddCandidate(g.ID, edited(t, v, g, "edited offline"), dev, EditedAfterDelete))
			switch where {
			case "emptied from the trash":
				if err := v.DeleteForever(g.ID); err != nil {
					t.Fatal(err)
				}
			case "live again":
				if err := v.Restore(g.ID, ""); err != nil {
					t.Fatal(err)
				}
			}
			got, err := v.RestoreCandidate(c.Key, "")
			if err != nil || got.ID != g.ID {
				t.Fatalf("%v %+v", err, got)
			}
			if read(t, root, "a.md") != "edited offline" || read(t, root, "pic.png") != "png" {
				t.Fatal("not restored")
			}
			if cs := must(v.ListCandidates()); len(cs) != 0 {
				t.Fatal("candidate left after restore")
			}
			if _, err := v.Trashed(g.ID); !errors.Is(err, ErrNotFound) {
				t.Fatal("still in the trash")
			}
			if where != "emptied from the trash" {
				found := false
				for _, ver := range must(v.History(g.ID)) {
					found = found || string(must(v.ReadVersion(g.ID, ver.ID))) == "deleted version"
				}
				if !found {
					t.Fatal("the replaced text is not in history")
				}
			}
		})
	}
}

func TestRestoreCandidateRefusesTakenName(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	v.Trash(g.ID)
	c := must(v.AddCandidate(g.ID, edited(t, v, g, "edited"), dev, EditedAfterDelete))
	must(v.CreateNote("a.md", []byte("someone else")))
	if _, err := v.RestoreCandidate(c.Key, ""); !errors.Is(err, ErrExists) {
		t.Fatalf("%v", err)
	}
	if read(t, root, "a.md") != "someone else" || len(must(v.ListCandidates())) != 1 {
		t.Fatal("something changed")
	}
	// Another folder works.
	if _, err := v.RestoreCandidate(c.Key, "Kept"); err != nil {
		t.Fatal(err)
	}
	if read(t, root, "Kept/a.md") != "edited" {
		t.Fatal("not restored to the chosen folder")
	}
}

// Candidates are never emptied by the maid's purge, only deleted by hand.
func TestCandidatesSurvivePurgeAndCanBeDeleted(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	v.Trash(g.ID)
	c := must(v.AddCandidate(g.ID, edited(t, v, g, "edited"), dev, EditedAfterDelete))
	if _, _, err := v.Purge(time.Now().Add(1000*24*time.Hour), false); err != nil {
		t.Fatal(err)
	}
	if len(must(v.ListCandidates())) != 1 {
		t.Fatal("purge removed a candidate")
	}
	if err := v.DeleteCandidate(c.Key); err != nil {
		t.Fatal(err)
	}
	if len(must(v.ListCandidates())) != 0 {
		t.Fatal("not deleted")
	}
	if err := v.DeleteCandidate(c.Key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("again: %v", err)
	}
}
