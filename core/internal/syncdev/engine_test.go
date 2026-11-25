package syncdev

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"styli/core/internal/vault"
)

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
// are refreshed where the state matches, and a note the restored atrium
// doesn't have at all (newer than its backup) is sent to it again.
func TestNewEpochFullCheck(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	keep := must(d.v.CreateNote("keep.md", []byte("k")))
	gone := must(d.v.CreateNote("gone.md", []byte("g")))
	d.sync(t)

	// Pretend the cursor came from another epoch and the base's numbering is off,
	// and that the atrium's backup never had gone.md.
	d.e.rec.saveCursor(Cursor{Epoch: "old", Since: 999})
	b := must(d.e.rec.base(keep.ID))
	b.Rev += 1000
	d.e.rec.saveBase(keep.ID, *b)
	if err := a.v.Trash(gone.ID); err != nil {
		t.Fatal(err)
	}
	if err := a.v.DeleteForever(gone.ID); err != nil {
		t.Fatal(err)
	}

	noWaiting(t, d.sync(t))
	if b := must(d.e.rec.base(keep.ID)); b.Rev != must(a.v.Get(keep.ID)).Rev {
		t.Fatalf("rev not refreshed: %d", b.Rev)
	}
	if text(t, d.v, gone.ID) != "g" || text(t, a.v, gone.ID) != "g" {
		t.Fatal("the note missing from the restored atrium was not sent again")
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

// ---- deletes ----

func inTrash(t *testing.T, v *vault.Vault, id string) bool {
	t.Helper()
	_, err := v.Trashed(id)
	return err == nil
}

func live(v *vault.Vault, id string) bool {
	_, err := v.Get(id)
	return err == nil
}

// A delete on one device reaches the atrium and the other device; both keep
// it in their trash.
func TestDeleteEverywhere(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.device(t), a.device(t)
	g := must(d1.v.CreateNote("n.md", []byte("n")))
	d1.sync(t)
	d2.sync(t)

	if err := d1.v.Trash(g.ID); err != nil {
		t.Fatal(err)
	}
	noWaiting(t, d1.sync(t))
	if live(a.v, g.ID) || !inTrash(t, a.v, g.ID) {
		t.Fatal("not deleted on the atrium")
	}
	noWaiting(t, d2.sync(t))
	if live(d2.v, g.ID) || !inTrash(t, d2.v, g.ID) {
		t.Fatal("not deleted on the other device")
	}
	noWaiting(t, d1.sync(t)) // its own delete in the feed changes nothing
	if must(d1.e.rec.base(g.ID)).Deleted == 0 {
		t.Fatal("the delete is not recorded in the base")
	}
}

// candidate returns the atrium's only recovery candidate's note text and why.
func candidate(t *testing.T, a *atrium) (string, string) {
	t.Helper()
	cs := must(a.v.ListCandidates())
	if len(cs) != 1 {
		t.Fatalf("%d candidates, want 1: %+v", len(cs), cs)
	}
	c := cs[0]
	b := must(os.ReadFile(filepath.Join(a.v.Root(), ".ids", "recovery", c.Key, "files", c.Group.Note.Version)))
	return string(b), c.Why
}

// A copy changed on a device after the delete: the delete stands everywhere,
// and the copy is kept on the atrium as a recovery candidate.
func TestChangedAfterDeleteBecomesCandidate(t *testing.T) {
	for _, path := range []string{"delete in the feed", "refused push"} {
		t.Run(path, func(t *testing.T) {
			a := newAtrium(t)
			d := a.device(t)
			g := must(d.v.CreateNote("n.md", []byte("n")))
			d.sync(t)
			if path == "refused push" { // the device has read past the delete already
				a.v.Trash(g.ID)
				d.sync(t)
				d.v.Restore(g.ID, "")
				d.e.rec.saveBase(g.ID, Base{Rev: 1, Note: must(d.v.Get(g.ID)).Note, Files: []vault.File{}})
			}
			write(t, d.v, g.ID, "edited offline")
			if path == "delete in the feed" {
				a.v.Trash(g.ID)
			}
			noWaiting(t, d.sync(t))
			if txt, why := candidate(t, a); txt != "edited offline" || why != vault.EditedAfterDelete {
				t.Fatalf("%q %s", txt, why)
			}
			if live(d.v, g.ID) || live(a.v, g.ID) {
				t.Fatal("the delete did not stand")
			}
			noWaiting(t, d.sync(t))
			if len(must(a.v.ListCandidates())) != 1 {
				t.Fatal("sent twice")
			}
		})
	}
}

// Restoring the candidate on the atrium brings the edited copy back everywhere.
func TestRestoredCandidateSyncs(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.device(t), a.device(t)
	g := must(d1.v.CreateNote("n.md", []byte("n")))
	d1.sync(t)
	d2.sync(t)
	write(t, d1.v, g.ID, "edited offline")
	a.v.Trash(g.ID)
	d1.sync(t)
	d2.sync(t)
	c := must(a.v.ListCandidates())[0]
	must(a.v.RestoreCandidate(c.Key, ""))
	settle(t, a, d1, d2)
	if text(t, d1.v, g.ID) != "edited offline" || text(t, d2.v, g.ID) != "edited offline" {
		t.Fatal("not restored on the devices")
	}
}

// A delete of a version that changed on the atrium since: the delete stands,
// and the atrium's newer version is kept as a candidate.
func TestDeleteOfChangedNoteKeepsCandidate(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(d.v.CreateNote("n.md", []byte("n")))
	d.sync(t)
	_, ver, _ := a.v.ReadNote(g.ID)
	must(a.v.WriteNote(g.ID, []byte("changed on the atrium"), ver))
	d.v.Trash(g.ID)

	noWaiting(t, d.sync(t))
	if txt, why := candidate(t, a); txt != "changed on the atrium" || why != vault.DeletedWhileChanged {
		t.Fatalf("%q %s", txt, why)
	}
	if live(a.v, g.ID) {
		t.Fatal("the delete did not stand")
	}
	noWaiting(t, d.sync(t))
}

// Restored on the atrium after the delete synced: the device restores it too.
func TestRestoredOnAtrium(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(d.v.CreateNote("n.md", []byte("n")))
	d.sync(t)
	a.v.Trash(g.ID)
	d.sync(t)
	if err := a.v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	noWaiting(t, d.sync(t))
	if text(t, d.v, g.ID) != "n" || inTrash(t, d.v, g.ID) {
		t.Fatal("not restored on the device")
	}
	if b := must(d.e.rec.base(g.ID)); b.Deleted != 0 {
		t.Fatal("base still says deleted")
	}
}

// Restored on a device after the delete synced: restored everywhere.
func TestRestoredOnDevice(t *testing.T) {
	for _, purged := range []bool{false, true} {
		t.Run(fmt.Sprintf("atrium trash emptied %v", purged), func(t *testing.T) {
			a := newAtrium(t)
			d1, d2 := a.device(t), a.device(t)
			g := must(d1.v.CreateNote("n.md", []byte("n")))
			d1.sync(t)
			d2.sync(t)
			d1.v.Trash(g.ID)
			d1.sync(t)
			d2.sync(t)
			if purged {
				a.v.DeleteForever(g.ID)
			}
			if err := d2.v.Restore(g.ID, ""); err != nil {
				t.Fatal(err)
			}
			noWaiting(t, d2.sync(t))
			if text(t, a.v, g.ID) != "n" {
				t.Fatal("not restored on the atrium")
			}
			noWaiting(t, d1.sync(t))
			if text(t, d1.v, g.ID) != "n" {
				t.Fatal("not restored on the other device")
			}
		})
	}
}

// A device away longer than the atrium kept the delete does a full check: an
// unchanged copy goes to its trash, a changed one waits.
func TestForgottenDeleteFullCheck(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	same := must(d.v.CreateNote("same.md", []byte("s")))
	edited := must(d.v.CreateNote("edited.md", []byte("e")))
	d.sync(t)
	write(t, d.v, edited.ID, "edited while away")
	for _, id := range []string{same.ID, edited.ID} {
		a.v.Trash(id)
		a.v.DeleteForever(id)
	}
	noWaiting(t, d.sync(t))
	if live(d.v, same.ID) || !inTrash(t, d.v, same.ID) {
		t.Fatal("the unchanged copy was not trashed")
	}
	if txt, _ := candidate(t, a); txt != "edited while away" || live(d.v, edited.ID) {
		t.Fatal("the edited copy is not a candidate")
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

// Pull side, same content: the local copy takes the atrium's ID, keeps its
// history, and nothing is renamed or downloaded.
func TestIdenticalNoteTakesAtriumID(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	theirs := must(a.v.CreateNote("Same.md", []byte("same text")))
	must(a.v.AddFile(theirs.ID, "pic.png", strings.NewReader("PNG")))
	mine := must(d.v.CreateNote("Same.md", []byte("same text")))
	must(d.v.AddFile(mine.ID, "pic.png", strings.NewReader("PNG")))
	hist := len(must(d.v.History(mine.ID)))

	noWaiting(t, d.sync(t))
	if _, err := d.v.Get(mine.ID); !errors.Is(err, vault.ErrNotFound) {
		t.Fatalf("the device's own ID is still there: %v", err)
	}
	if got := must(d.v.Get(theirs.ID)); got.Note.Path != "Same.md" {
		t.Fatalf("at %s", got.Note.Path)
	}
	if n := len(must(d.v.History(theirs.ID))); n != hist || n == 0 {
		t.Fatalf("history: %d versions, had %d", n, hist)
	}
	if b := must(d.e.rec.base(theirs.ID)); b == nil {
		t.Fatal("no base recorded")
	}
	for _, v := range []*vault.Vault{a.v, d.v} {
		if gs := must(v.List()); len(gs) != 1 {
			t.Fatalf("%d groups, want 1", len(gs))
		}
	}
	noWaiting(t, d.sync(t))
}

// Same note text but a different attached file: renamed as before.
func TestNearlyIdenticalNoteIsRenamed(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	theirs := must(a.v.CreateNote("Same.md", []byte("same text")))
	must(a.v.AddFile(theirs.ID, "pic.png", strings.NewReader("PNG")))
	mine := must(d.v.CreateNote("Same.md", []byte("same text")))

	noWaiting(t, d.sync(t))
	if p := must(d.v.Get(mine.ID)).Note.Path; p != "Same 2.md" {
		t.Fatalf("local note at %s", p)
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

// renameOnAtrium renames a note on the atrium, as another device's push would.
func renameOnAtrium(t *testing.T, v *vault.Vault, id, name string) {
	t.Helper()
	g := must(v.Get(id))
	n := *g.Note
	n.Path = name
	g.Note = &n
	if _, err := v.Apply(id, g.Rev, g); err != nil {
		t.Fatalf("rename %s to %s: %v", id, name, err)
	}
}

// swapped makes notes a.md and b.md on a synced device, then swaps their
// names on the atrium (through a third name, since nothing overwrites).
func swapped(t *testing.T) (*atrium, *device, vault.Group, vault.Group) {
	a := newAtrium(t)
	d := a.device(t)
	x := must(d.v.CreateNote("a.md", []byte("A")))
	y := must(d.v.CreateNote("b.md", []byte("B")))
	d.sync(t)
	renameOnAtrium(t, a.v, x.ID, "spare.md")
	renameOnAtrium(t, a.v, y.ID, "a.md")
	renameOnAtrium(t, a.v, x.ID, "b.md")
	return a, d, x, y
}

func checkSwapped(t *testing.T, a *atrium, d *device, x, y vault.Group) {
	t.Helper()
	for _, c := range []struct {
		id, path, text string
	}{{x.ID, "b.md", "A"}, {y.ID, "a.md", "B"}} {
		if p := must(d.v.Get(c.id)).Note.Path; p != c.path || text(t, d.v, c.id) != c.text {
			t.Fatalf("%s at %s with %q", c.id, p, text(t, d.v, c.id))
		}
		if b := must(d.e.rec.base(c.id)); b.Parked != nil || b.Note.Path != c.path {
			t.Fatalf("base %+v", b)
		}
	}
	ents := must(os.ReadDir(d.v.Root()))
	for _, e := range ents {
		if strings.Contains(e.Name(), "moving") {
			t.Fatalf("left behind: %s", e.Name())
		}
	}
	if p := must(a.v.Get(x.ID)).Note.Path; p != "b.md" {
		t.Fatalf("the atrium's copy moved: %s", p)
	}
}

func TestSwappedNamesSync(t *testing.T) {
	a, d, x, y := swapped(t)
	aSeq := a.v.Seq()
	noWaiting(t, d.sync(t))
	checkSwapped(t, a, d, x, y)
	if a.v.Seq() != aSeq {
		t.Fatal("the swap pushed something to the atrium")
	}
}

// A crash right after parking (or right after writing the park down, before
// the move) is finished by the next pass, and the spare name never reaches
// the atrium.
func TestCrashWhileParked(t *testing.T) {
	for _, moved := range []bool{true, false} {
		a, d, x, y := swapped(t)
		g := must(d.v.Get(y.ID))
		b := must(d.e.rec.base(y.ID))
		p := &pass{waiting: map[string]string{}}
		if moved {
			if err := d.e.park(p, g, b, nil); err != nil {
				t.Fatal(err)
			}
			if must(d.v.Get(y.ID)).Note.Path != "b (moving).md" {
				t.Fatal("not parked")
			}
		} else {
			b.Parked = &Parked{Note: &vault.File{Path: "b (moving).md", Version: g.Note.Version}, Files: []vault.File{}}
			d.e.rec.saveBase(y.ID, *b)
		}
		aSeq := a.v.Seq()
		noWaiting(t, d.sync(t))
		checkSwapped(t, a, d, x, y)
		if a.v.Seq() != aSeq {
			t.Fatalf("moved=%v: the spare name was pushed", moved)
		}
	}
}

// A parked note whose pull never comes (the atrium has it where it was) is
// moved back, not pushed under its spare name.
func TestParkedWithoutRemoteChangeGoesBack(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(d.v.CreateNote("a.md", []byte("A")))
	d.sync(t)
	if err := d.e.park(&pass{}, must(d.v.Get(g.ID)), must(d.e.rec.base(g.ID)), nil); err != nil {
		t.Fatal(err)
	}
	aSeq := a.v.Seq()
	noWaiting(t, d.sync(t))
	if p := must(d.v.Get(g.ID)).Note.Path; p != "a.md" {
		t.Fatalf("not moved back: %s", p)
	}
	if b := must(d.e.rec.base(g.ID)); b.Parked != nil {
		t.Fatal("park not cleared")
	}
	if a.v.Seq() != aSeq {
		t.Fatal("pushed")
	}
}

// A note in the way that has local edits is not parked: it waits, and
// nothing is lost.
func TestEditedNoteInTheWayIsNotParked(t *testing.T) {
	a, d, x, y := swapped(t)
	d.e.Merge = nil
	write(t, d.v, y.ID, "B edited here")
	s := d.sync(t)
	if len(s.Waiting) == 0 {
		t.Fatal("nothing waiting")
	}
	if p := must(d.v.Get(y.ID)).Note.Path; p != "b.md" || text(t, d.v, y.ID) != "B edited here" {
		t.Fatalf("edited note touched: %s", p)
	}
	if text(t, d.v, x.ID) != "A" || text(t, a.v, y.ID) != "B" {
		t.Fatal("a side lost its text")
	}
}

// Restored here and trashed again before syncing, while the atrium restored
// and changed it: this person's delete stands, the atrium's version is kept.
func TestRetrashedHereWhileRestoredThere(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(d.v.CreateNote("n.md", []byte("n")))
	d.sync(t)
	a.v.Trash(g.ID)
	d.sync(t) // applies the delete
	if err := a.v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	appendTo(t, a.v, g.ID, " changed there")
	if err := d.v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	if err := d.v.Trash(g.ID); err != nil {
		t.Fatal(err)
	}
	noWaiting(t, d.sync(t))
	if txt, _ := candidate(t, a); txt != "n changed there" || live(a.v, g.ID) || live(d.v, g.ID) {
		t.Fatalf("%q", txt)
	}
}

// Deleted here and on the atrium independently, then restored on another
// device that saw only the atrium's delete. The restore replaced the delete in
// the feed before this device synced, so its own delete is sent and applies on
// top: the later sync wins, and the note is in the atrium's trash, not lost.
// (Had this device seen the atrium's delete first, it would wait, flagged.)
func TestOwnDeleteMeetsRestoreElsewhere(t *testing.T) {
	a := newAtrium(t)
	d1, d2 := a.device(t), a.device(t)
	g := must(d1.v.CreateNote("n.md", []byte("n")))
	d1.sync(t)
	d2.sync(t)
	a.v.Trash(g.ID)
	d1.v.Trash(g.ID) // offline, not knowing of the atrium's delete
	d2.sync(t)
	if err := d2.v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	noWaiting(t, d2.sync(t))
	noWaiting(t, d1.sync(t))
	if live(a.v, g.ID) || !inTrash(t, a.v, g.ID) {
		t.Fatal("want the note in the atrium's trash")
	}
	settle(t, a, d1, d2)
}

// Pulled, its base lost (a crash), then trashed here: the delete is sent.
func TestTrashedWithLostBaseSendsDelete(t *testing.T) {
	a := newAtrium(t)
	d := a.device(t)
	g := must(a.v.CreateNote("n.md", []byte("n")))
	d.sync(t)
	d.e.rec.removeBase(g.ID)
	d.e.rec.saveCursor(Cursor{}) // and the cursor, so the feed lists it again
	d.v.Trash(g.ID)
	noWaiting(t, d.sync(t))
	if live(a.v, g.ID) {
		t.Fatal("not deleted on the atrium")
	}
}
