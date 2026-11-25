package vault

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"styli/core/internal/atomicfile"
	"testing"
	"time"
)

// stage uploads content and returns its hash.
func stage(t *testing.T, v *Vault, content string) string {
	t.Helper()
	h := hashBytes([]byte(content))
	if err := v.StageBlob(h, strings.NewReader(content), 1<<20); err != nil {
		t.Fatal(err)
	}
	return h
}

func read(t *testing.T, root, p string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(root, p))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

const deviceID = "aaaaaaaaaaaaaaaaaaaaaaaaaa" // an ID a device made

func TestApplyCreatesGroup(t *testing.T) {
	v, root := open(t)
	h := stage(t, v, "hello")
	g, err := v.Apply(deviceID, 0, Group{Note: &File{"inbox/a.md", h}, Files: []File{}})
	if err != nil {
		t.Fatal(err)
	}
	if g.Rev != v.Seq() || read(t, root, "inbox/a.md") != "hello" {
		t.Fatalf("rev %d seq %d", g.Rev, v.Seq())
	}
	if vs, _ := v.History(deviceID); len(vs) != 1 {
		t.Fatalf("history: %d versions", len(vs))
	}
	if _, err := os.Stat(v.blobPath(h)); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("used blob left behind")
	}
	// The same push again (its reply was lost) is fine and changes nothing.
	again, err := v.Apply(deviceID, 0, Group{Note: &File{"inbox/a.md", h}, Files: []File{}})
	if err != nil || again.Rev != g.Rev {
		t.Fatalf("retry: %v rev %d", err, again.Rev)
	}
}

func TestApplyNeedsCurrentBase(t *testing.T) {
	v, root := open(t)
	v.SetHistoryWindow(0) // every change its own version, so each text can be found
	g := must(v.CreateNote("a.md", []byte("one")))
	two := stage(t, v, "two")
	want := Group{Note: &File{"a.md", two}, Files: []File{}}
	if _, err := v.Apply(g.ID, 0, want); !errors.Is(err, ErrConflict) {
		t.Fatalf("create over existing: %v", err)
	}
	// Someone edits in the web UI first; the device's base is now stale.
	_, ver, _ := v.ReadNote(g.ID)
	must(v.WriteNote(g.ID, []byte("web"), ver))
	cur, err := v.Apply(g.ID, g.Rev, want)
	if !errors.Is(err, ErrConflict) || cur.Note.Version != hashBytes([]byte("web")) {
		t.Fatalf("stale base: %v %+v", err, cur.Note)
	}
	if read(t, root, "a.md") != "web" {
		t.Fatal("stale push overwrote the note")
	}
	// With the current base it goes through, and the web text is in history.
	got, err := v.Apply(g.ID, cur.Rev, want)
	if err != nil || read(t, root, "a.md") != "two" || got.Rev <= cur.Rev {
		t.Fatalf("apply: %v", err)
	}
	found := false
	vs, _ := v.History(g.ID)
	for _, ver := range vs {
		if string(must(v.ReadVersion(g.ID, ver.ID))) == "web" {
			found = true
		}
	}
	if !found {
		t.Fatal("replaced text not in history")
	}
}

func TestApplyMovesRenamesAndAddsFiles(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("note")))
	g = must(v.AddFile(g.ID, "pic.png", strings.NewReader("old png")))
	png := stage(t, v, "new png")
	pdf := stage(t, v, "pdf")
	want := Group{
		Note:  &File{"x/b.md", g.Note.Version},
		Files: []File{{"x/pic.png", png}, {"x/doc.pdf", pdf}},
	}
	if _, err := v.Apply(g.ID, g.Rev, want); err != nil {
		t.Fatal(err)
	}
	if read(t, root, "x/b.md") != "note" || read(t, root, "x/pic.png") != "new png" || read(t, root, "x/doc.pdf") != "pdf" {
		t.Fatal("wrong content after apply")
	}
	for _, p := range []string{"a.md", "pic.png"} {
		if _, err := os.Stat(filepath.Join(root, p)); err == nil {
			t.Fatalf("%s left behind", p)
		}
	}
	// The vault agrees with the disk: a scan finds nothing to change.
	if ch, _, err := v.Rescan(); err != nil || len(ch) != 0 {
		t.Fatalf("rescan after apply: %v %v", ch, err)
	}
}

func TestApplyNeverOverwritesAnotherFile(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("mine")))
	must(v.CreateNote("taken.md", []byte("someone else")))
	if _, err := v.Apply(g.ID, g.Rev, Group{Note: &File{"taken.md", g.Note.Version}, Files: []File{}}); !errors.Is(err, ErrExists) {
		t.Fatalf("rename onto another note: %v", err)
	}
	h := stage(t, v, "new")
	if _, err := v.Apply(deviceID, 0, Group{Note: &File{"taken.md", h}, Files: []File{}}); !errors.Is(err, ErrExists) {
		t.Fatalf("create onto another note: %v", err)
	}
	if read(t, root, "taken.md") != "someone else" || read(t, root, "a.md") != "mine" {
		t.Fatal("a refused push changed files")
	}
}

func TestApplyRefusesUnscannedOutsideEdit(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	os.WriteFile(filepath.Join(root, "a.md"), []byte("typed in vim"), 0o600)
	h := stage(t, v, "device")
	if _, err := v.Apply(g.ID, g.Rev, Group{Note: &File{"a.md", h}, Files: []File{}}); !errors.Is(err, ErrConflict) {
		t.Fatalf("got %v", err)
	}
	if read(t, root, "a.md") != "typed in vim" {
		t.Fatal("outside edit overwritten")
	}
}

func TestApplyRefusesBadPushes(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	g = must(v.AddFile(g.ID, "pic.png", strings.NewReader("png")))
	h := stage(t, v, "x")
	missing := hashBytes([]byte("never uploaded"))
	cases := []struct {
		name string
		want Group
		err  error
	}{
		{"drops the note", Group{Files: g.Files}, ErrBadGroup},
		{"drops a file", Group{Note: g.Note, Files: []File{}}, ErrBadGroup},
		{"note not .md", Group{Note: &File{"a.txt", h}, Files: g.Files}, ErrBadPath},
		{"file is .md", Group{Note: g.Note, Files: []File{g.Files[0], {"b.md", h}}}, ErrBadPath},
		{"two folders", Group{Note: &File{"x/a.md", g.Note.Version}, Files: g.Files}, ErrBadGroup},
		{"into .ids", Group{Note: &File{".ids/a.md", g.Note.Version}, Files: []File{{".ids/pic.png", g.Files[0].Version}}}, ErrBadPath},
		{"escapes", Group{Note: &File{"../a.md", g.Note.Version}, Files: []File{{"../pic.png", g.Files[0].Version}}}, ErrBadPath},
		{"bad hash", Group{Note: &File{"a.md", "nope"}, Files: g.Files}, ErrBadGroup},
		{"not uploaded", Group{Note: &File{"a.md", missing}, Files: g.Files}, ErrMissingBlob},
	}
	for _, c := range cases {
		if _, err := v.Apply(g.ID, g.Rev, c.want); !errors.Is(err, c.err) {
			t.Errorf("%s: got %v, want %v", c.name, err, c.err)
		}
	}
	if _, err := v.Apply("BAD", 0, Group{Note: &File{"z.md", h}}); !errors.Is(err, ErrBadGroup) {
		t.Errorf("bad id: %v", err)
	}
	if _, err := v.Apply(deviceID, 5, Group{Note: &File{"z.md", h}}); !errors.Is(err, ErrNotFound) {
		t.Errorf("unknown group with a base: %v", err)
	}
}

func TestApplyToTrashedGroup(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	v.Trash(g.ID)
	h := stage(t, v, "edited offline")
	if _, err := v.Apply(g.ID, g.Rev, Group{Note: &File{"a.md", h}, Files: []File{}}); !errors.Is(err, ErrTrashed) {
		t.Fatalf("got %v", err)
	}
}

func TestStageBlobChecksHashAndSize(t *testing.T) {
	v, _ := open(t)
	if err := v.StageBlob(hashBytes([]byte("a")), strings.NewReader("b"), 10); !errors.Is(err, ErrHashMismatch) {
		t.Fatalf("wrong content: %v", err)
	}
	big := strings.Repeat("x", 11)
	if err := v.StageBlob(hashBytes([]byte(big)), strings.NewReader(big), 10); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("too large: %v", err)
	}
	if err := v.StageBlob("../../etc", strings.NewReader(""), 10); !errors.Is(err, ErrBadGroup) {
		t.Fatalf("bad name: %v", err)
	}
	ents, _ := os.ReadDir(filepath.Join(v.root, ".ids", "sync", "blobs"))
	if len(ents) != 0 {
		t.Fatalf("refused blobs left behind: %v", ents)
	}
}

// A crash after some files were moved but before the record was saved is
// finished when the vault next opens, and the device's retry is then a no-op.
func TestInterruptedApplyIsFinishedOnOpen(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	g = must(v.AddFile(g.ID, "pic.png", strings.NewReader("png")))
	two := stage(t, v, "two")
	want := Group{Note: &File{"x/a.md", two}, Files: []File{{"x/pic.png", g.Files[0].Version}}}
	p := pendingApply{ID: g.ID, Group: want, Steps: []applyStep{
		{Src: "a.md", Dst: "x/a.md", Ver: two, Content: true},
		{Src: "pic.png", Dst: "x/pic.png", Ver: g.Files[0].Version},
	}}
	writeJSONFile(t, v.pendingPath(g.ID), p)
	// The crash happened after the note was moved, before anything else.
	os.MkdirAll(filepath.Join(root, "x"), 0o700)
	os.Rename(filepath.Join(root, "a.md"), filepath.Join(root, "x", "a.md"))

	// A scan meanwhile must not adopt the half-moved note as a new group.
	if ch, _, _ := v.Rescan(); len(ch) != 0 && ch[0].Kind == Added {
		t.Fatalf("rescan adopted a half-committed file: %v", ch)
	}

	v2 := must(Open(root))
	if read(t, root, "x/a.md") != "two" || read(t, root, "x/pic.png") != "png" {
		t.Fatal("commit not finished")
	}
	if _, err := os.Stat(v2.pendingPath(g.ID)); err == nil {
		t.Fatal("journal left behind")
	}
	got, err := v2.Apply(g.ID, g.Rev, want)
	if err != nil || !SameState(got, want) {
		t.Fatalf("retry: %v", err)
	}
	if gs := must(v2.List()); len(gs) != 1 {
		t.Fatalf("%d groups, want 1", len(gs))
	}
}

func TestStaleBlobsRemovedOnOpen(t *testing.T) {
	v, root := open(t)
	old := stage(t, v, "old")
	fresh := stage(t, v, "fresh")
	past := time.Now().Add(-2 * StaleBlobAge)
	os.Chtimes(v.blobPath(old), past, past)
	v2 := must(Open(root))
	if _, err := os.Stat(v2.blobPath(old)); err == nil {
		t.Fatal("stale blob kept")
	}
	if _, err := os.Stat(v2.blobPath(fresh)); err != nil {
		t.Fatal("fresh blob removed")
	}
}

func TestChangesFeed(t *testing.T) {
	v, _ := open(t)
	a := must(v.CreateNote("a.md", []byte("a")))
	b := must(v.CreateNote("b.md", []byte("b")))
	c := must(v.CreateNote("c.md", []byte("c")))
	_, ver, _ := v.ReadNote(a.ID)
	must(v.WriteNote(a.ID, []byte("a2"), ver)) // a moves to the end

	gs, more, err := v.Changes(0, 2)
	if err != nil || !more || len(gs) != 2 || gs[0].ID != b.ID || gs[1].ID != c.ID {
		t.Fatalf("page 1: %v %v %v", gs, more, err)
	}
	gs, more, _ = v.Changes(gs[1].Rev, 2)
	if more || len(gs) != 1 || gs[0].ID != a.ID {
		t.Fatalf("page 2: %v %v", gs, more)
	}
	if gs, _, _ = v.Changes(v.Seq(), 10); len(gs) != 0 {
		t.Fatalf("nothing new: %v", gs)
	}
	// A trashed group is listed as a deletion, at the delete's own number, as
	// it was when deleted.
	seq := v.Seq()
	v.Trash(b.ID)
	gs, _, _ = v.Changes(seq, 10)
	if len(gs) != 1 || gs[0].ID != b.ID || !gs[0].Deleted || gs[0].Rev != seq+1 || gs[0].Note.Path != "b.md" {
		t.Fatalf("deletion: %+v", gs)
	}
	// Restored, it is a live change again.
	if err := v.Restore(b.ID, ""); err != nil {
		t.Fatal(err)
	}
	gs, _, _ = v.Changes(seq+1, 10)
	if len(gs) != 1 || gs[0].ID != b.ID || gs[0].Deleted {
		t.Fatalf("restored: %+v", gs)
	}
	// Emptied from the trash, it leaves the feed and the forgotten number moves.
	v.Trash(b.ID)
	del := v.Seq()
	if err := v.DeleteForever(b.ID); err != nil {
		t.Fatal(err)
	}
	if gs, _, _ = v.Changes(0, 10); len(gs) != 2 {
		t.Fatalf("after delete forever: %+v", gs)
	}
	if v.Forgotten() != del || must(Open(v.Root())).Forgotten() != del {
		t.Fatalf("forgotten %d, want %d", v.Forgotten(), del)
	}
}

// Trash entries from before deletes synced get a number when the vault opens,
// so they are listed as deletions.
func TestOldTrashEntriesBecomeDeletions(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("a")))
	v.Trash(g.ID)
	r := must(v.readTrash(g.ID))
	r.DeletedRev = 0
	writeJSONFile(t, filepath.Join(v.trashPath(g.ID), "record.json"), r)

	v2 := must(Open(root))
	gs, _, _ := v2.Changes(0, 10)
	if len(gs) != 1 || !gs[0].Deleted || gs[0].Rev != v2.Seq() || gs[0].Rev <= r.Rev {
		t.Fatalf("%+v seq %d", gs, v2.Seq())
	}
}

func TestTrashIfNeedsCurrentRev(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("a.md", []byte("a")))
	_, ver, _ := v.ReadNote(g.ID)
	must(v.WriteNote(g.ID, []byte("a2"), ver))
	cur, err := v.TrashIf(g.ID, g.Rev)
	if !errors.Is(err, ErrConflict) || cur.Rev == g.Rev {
		t.Fatalf("stale delete: %v %+v", err, cur)
	}
	if _, err := v.TrashIf(g.ID, cur.Rev); err != nil {
		t.Fatal(err)
	}
	if tg := must(v.Trashed(g.ID)); tg.Group.Rev != cur.Rev || tg.DeletedRev != v.Seq() {
		t.Fatalf("%+v", tg)
	}
}

// A push based on the delete's number is a device restoring the note: it is
// restored here and the push applied. Any other base is refused.
func TestApplyRestoresWhenBaseIsTheDelete(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	v.Trash(g.ID)
	tg := must(v.Trashed(g.ID))
	two := stage(t, v, "two")
	want := Group{Note: &File{"a.md", two}, Files: []File{}}
	if _, err := v.Apply(g.ID, g.Rev, want); !errors.Is(err, ErrTrashed) {
		t.Fatalf("push from before the delete: %v", err)
	}
	got, err := v.Apply(g.ID, tg.DeletedRev, want)
	if err != nil || !SameState(got, want) || read(t, root, "a.md") != "two" {
		t.Fatalf("%v %+v", err, got)
	}
	if _, err := v.Trashed(g.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal("still in the trash")
	}
}

func TestChangedWakesWaiters(t *testing.T) {
	v, _ := open(t)
	ch := v.Changed()
	select {
	case <-ch:
		t.Fatal("woke with no change")
	default:
	}
	must(v.CreateNote("a.md", nil))
	select {
	case <-ch:
	case <-time.After(time.Second):
		t.Fatal("not woken by a change")
	}
}

func writeJSONFile(t *testing.T, p string, v any) {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Dir(p), 0o700)
	if err := os.WriteFile(p, b, 0o600); err != nil {
		t.Fatal(err)
	}
}

// A commit whose uploaded content is gone is retried on the next start, then
// set aside; the vault always opens.
func TestUnfinishableCommitIsSetAside(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	two := hashBytes([]byte("two")) // never uploaded: the blob is "lost"
	want := Group{Note: &File{"x/a.md", two}, Files: []File{}}
	writeJSONFile(t, v.pendingPath(g.ID), pendingApply{ID: g.ID, Group: want, Steps: []applyStep{
		{Src: "a.md", Dst: "x/a.md", Ver: two, Content: true},
	}})

	v2 := must(Open(root)) // first start: kept for another try
	if _, err := os.Stat(v2.pendingPath(g.ID)); err != nil {
		t.Fatal("journal set aside after one try")
	}
	if f := must(v2.FailedCommits()); len(f) != 0 {
		t.Fatalf("failed too early: %v", f)
	}

	v3 := must(Open(root)) // second start: set aside
	if _, err := os.Stat(v3.pendingPath(g.ID)); err == nil {
		t.Fatal("journal still pending")
	}
	f := must(v3.FailedCommits())
	if len(f) != 1 || f[0].ID != g.ID || f[0].NotePath != "x/a.md" || f[0].Reason != "the content the device uploaded is gone" {
		t.Fatalf("failed: %+v", f)
	}
	// The scan tidies the half-moved note: same ID, its text untouched.
	if _, _, err := v3.Rescan(); err != nil {
		t.Fatal(err)
	}
	if got := must(v3.Get(g.ID)); got.Note.Path != "x/a.md" || read(t, root, "x/a.md") != "one" {
		t.Fatalf("after scan: %+v", got.Note)
	}
	// The device sends its change again, and it goes through.
	cur := must(v3.Get(g.ID))
	stage(t, v3, "two")
	if _, err := v3.Apply(g.ID, cur.Rev, want); err != nil || read(t, root, "x/a.md") != "two" {
		t.Fatalf("resend: %v", err)
	}

	if err := v3.DismissFailedCommit(f[0].Name); err != nil {
		t.Fatal(err)
	}
	if len(must(v3.FailedCommits())) != 0 {
		t.Fatal("not dismissed")
	}
	for _, bad := range []string{f[0].Name, "../seq", "x.json"} {
		if err := v3.DismissFailedCommit(bad); !errors.Is(err, ErrNotFound) {
			t.Errorf("dismiss %q: %v", bad, err)
		}
	}
}

func TestUnreadableJournalIsSetAside(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	os.MkdirAll(filepath.Dir(v.pendingPath(g.ID)), 0o700)
	os.WriteFile(v.pendingPath(g.ID), []byte("{garbage"), 0o600)
	v2 := must(Open(root))
	f := must(v2.FailedCommits())
	if len(f) != 1 || f[0].ID != g.ID || f[0].NotePath != "" {
		t.Fatalf("failed: %+v", f)
	}
	if read(t, root, "a.md") != "one" {
		t.Fatal("note touched")
	}
}

func TestCleanBlobsWhileRunning(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("a.md", []byte("one")))
	old := stage(t, v, "abandoned")
	needed := stage(t, v, "waiting on a commit")
	fresh := stage(t, v, "just uploaded")
	past := time.Now().Add(-2 * StaleBlobAge)
	os.Chtimes(v.blobPath(old), past, past)
	os.Chtimes(v.blobPath(needed), past, past)
	writeJSONFile(t, v.pendingPath(g.ID), pendingApply{ID: g.ID, Group: Group{Note: &File{"a.md", needed}}, Steps: []applyStep{
		{Src: "a.md", Dst: "a.md", Ver: needed, Content: true},
	}})
	if n, err := v.CleanBlobs(); err != nil || n != 1 {
		t.Fatalf("removed %d, %v", n, err)
	}
	for h, want := range map[string]bool{old: false, needed: true, fresh: true} {
		if _, err := os.Stat(v.blobPath(h)); (err == nil) != want {
			t.Errorf("%s kept=%v, want %v", h[:8], err == nil, want)
		}
	}
}

const atriumID = "bbbbbbbbbbbbbbbbbbbbbbbbbb" // the atrium's ID for the same note

// adoptSetup makes a never-synced note with some history, and the atrium's
// group for the same files (with its own history setting).
func adoptSetup(t *testing.T) (*Vault, string, Group, Group) {
	t.Helper()
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("same")))
	g = must(v.AddFile(g.ID, "pic.png", strings.NewReader("png")))
	if vs := must(v.History(g.ID)); len(vs) == 0 {
		t.Fatal("setup: no history")
	}
	off := false
	want := Group{Note: g.Note, Files: g.Files, History: &off, Conflict: true}
	return v, root, g, want
}

func checkAdopted(t *testing.T, v *Vault, old Group, want Group) {
	t.Helper()
	if _, err := v.Get(old.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("old ID still there: %v", err)
	}
	got := must(v.Get(atriumID))
	if !SameState(got, want) {
		t.Fatalf("got %+v", got)
	}
	if vs, _ := v.History(atriumID); len(vs) == 0 {
		t.Fatal("history did not move")
	}
	if gs := must(v.List()); len(gs) != 1 {
		t.Fatalf("%d groups, want 1", len(gs))
	}
	if ch, _, _ := v.Rescan(); len(ch) != 0 {
		t.Fatalf("a scan found changes: %v", ch)
	}
}

func TestAdoptTakesTheAtriumsID(t *testing.T) {
	v, _, g, want := adoptSetup(t)
	got, err := v.Adopt(g.ID, atriumID, want)
	if err != nil || got.ID != atriumID {
		t.Fatalf("%v %+v", err, got)
	}
	checkAdopted(t, v, g, want)
	if _, err := os.Stat(v.pendingPath(atriumID)); err == nil {
		t.Fatal("journal left behind")
	}
}

func TestAdoptRefuses(t *testing.T) {
	v, _, g, want := adoptSetup(t)
	other := want
	other.Files = []File{{"pic.png", hashBytes([]byte("different"))}}
	if _, err := v.Adopt(g.ID, atriumID, other); !errors.Is(err, ErrConflict) {
		t.Fatalf("different files: %v", err)
	}
	moved := want
	moved.Note = &File{"b.md", g.Note.Version}
	if _, err := v.Adopt(g.ID, atriumID, moved); !errors.Is(err, ErrConflict) {
		t.Fatalf("different path: %v", err)
	}
	if _, err := v.Adopt(deviceID, atriumID, want); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing source: %v", err)
	}
	if _, err := v.Adopt(g.ID, g.ID, want); !errors.Is(err, ErrBadGroup) {
		t.Fatalf("same ID: %v", err)
	}
	taken := must(v.CreateNote("c.md", []byte("c")))
	if _, err := v.Adopt(g.ID, taken.ID, want); !errors.Is(err, ErrExists) {
		t.Fatalf("ID in use: %v", err)
	}
	if err := v.Trash(taken.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Adopt(g.ID, taken.ID, want); !errors.Is(err, ErrTrashed) {
		t.Fatalf("ID in the trash: %v", err)
	}
	if must(v.Get(g.ID)).Note.Path != "a.md" {
		t.Fatal("a refused adopt changed the group")
	}
}

// A crash at any point of an adopt is finished when the vault next opens.
func TestInterruptedAdoptIsFinishedOnOpen(t *testing.T) {
	for _, stage := range []string{"nothing done", "history moved", "new record saved"} {
		t.Run(stage, func(t *testing.T) {
			v, root, g, want := adoptSetup(t)
			w := want
			w.ID = atriumID
			writeJSONFile(t, v.pendingPath(atriumID), pendingApply{ID: atriumID, Group: w, Steps: []applyStep{}, From: g.ID})
			if stage != "nothing done" {
				if err := os.Rename(v.historyPath(g.ID), v.historyPath(atriumID)); err != nil {
					t.Fatal(err)
				}
			}
			if stage == "new record saved" {
				if err := v.save(&w); err != nil {
					t.Fatal(err)
				}
			}
			checkAdopted(t, must(Open(root)), g, want)
		})
	}
}

// The forgotten number is saved before an entry is emptied: when it is
// written the entry is still whole, so a crash leaves it listed and the number
// at least as high.
func TestForgottenSavedBeforePurge(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", []byte("a")))
	v.Trash(g.ID)
	tg := must(v.Trashed(g.ID))
	whole := false
	atomicfile.SetBeforeWrite(func(dst string) {
		if filepath.Base(dst) == forgottenFile {
			_, err := os.Stat(filepath.Join(v.trashPath(g.ID), "files", "a.md"))
			whole = err == nil
		}
	})
	defer atomicfile.SetBeforeWrite(nil)
	if err := v.DeleteForever(g.ID); err != nil {
		t.Fatal(err)
	}
	if !whole {
		t.Fatal("the entry was touched before the forgotten number was saved")
	}
	if f := must(Open(root)).Forgotten(); f != tg.DeletedRev {
		t.Fatalf("forgotten %d, want %d", f, tg.DeletedRev)
	}
}
