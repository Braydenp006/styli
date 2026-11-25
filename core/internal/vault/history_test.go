package vault

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// fakeClock lets a test move time forward without sleeping.
func fakeClock(v *Vault) *time.Time {
	t := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	v.now = func() time.Time { return t }
	return &t
}

func versions(t *testing.T, v *Vault, id string) []string { // newest first, as text
	t.Helper()
	var out []string
	for _, ver := range must(v.History(id)) {
		out = append(out, string(must(v.ReadVersion(id, ver.ID))))
	}
	return out
}

func write2(t *testing.T, v *Vault, id, text string) string {
	t.Helper()
	_, base, err := v.ReadNote(id)
	if err != nil {
		t.Fatal(err)
	}
	return must(v.WriteNote(id, []byte(text), base))
}

func eq(t *testing.T, got []string, want ...string) {
	t.Helper()
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestEditsInsideTheWindowFoldIntoOneVersion(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("v1")))
	*clock = clock.Add(10 * time.Minute)
	write2(t, v, g.ID, "v2")
	eq(t, versions(t, v, g.ID), "v2") // the window's latest text, not both

	*clock = clock.Add(31 * time.Minute) // past the window
	write2(t, v, g.ID, "v3")
	eq(t, versions(t, v, g.ID), "v3", "v2") // the last state of the first session is kept
}

func TestIdenticalContentAddsNothing(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("same")))
	*clock = clock.Add(2 * time.Hour)
	write2(t, v, g.ID, "same")
	eq(t, versions(t, v, g.ID), "same")
}

func TestHistoryIsOnByDefaultAndWindowIsConfigurable(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("a")))
	if on, _ := v.HistoryEnabled(g.ID); !on {
		t.Fatal("history should default to on")
	}
	v.SetHistoryWindow(time.Minute)
	*clock = clock.Add(2 * time.Minute)
	write2(t, v, g.ID, "b")
	eq(t, versions(t, v, g.ID), "b", "a")
	v.SetHistoryWindow(0) // every change is a version
	write2(t, v, g.ID, "c")
	write2(t, v, g.ID, "d")
	eq(t, versions(t, v, g.ID), "d", "c", "b", "a")
}

func TestRestoreAddsAVersionAndKeepsTheReplacedText(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("v1")))
	first := must(v.History(g.ID))[0].ID
	*clock = clock.Add(31 * time.Minute)
	write2(t, v, g.ID, "v2")
	*clock = clock.Add(time.Minute) // inside the window, yet a restore must not fold
	_, base, _ := v.ReadNote(g.ID)
	if _, err := v.RestoreVersion(g.ID, first, base); err != nil {
		t.Fatal(err)
	}
	if b, _, _ := v.ReadNote(g.ID); string(b) != "v1" {
		t.Fatalf("note text %q", b)
	}
	eq(t, versions(t, v, g.ID), "v1", "v2", "v1")
}

func TestRestoreUsesTheStaleWriteCheck(t *testing.T) {
	v, _ := open(t)
	fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("v1")))
	id := must(v.History(g.ID))[0].ID
	_, base, _ := v.ReadNote(g.ID)
	write2(t, v, g.ID, "v2")
	if _, err := v.RestoreVersion(g.ID, id, base); !errors.Is(err, ErrConflict) {
		t.Fatalf("want ErrConflict, got %v", err)
	}
}

func TestToggleStopsNewVersionsButKeepsOldOnes(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("v1")))
	off := false
	if err := v.SetHistory(g.ID, &off); err != nil {
		t.Fatal(err)
	}
	*clock = clock.Add(time.Hour)
	write2(t, v, g.ID, "v2")
	eq(t, versions(t, v, g.ID), "v1") // old kept, nothing new
	if on, _ := v.HistoryEnabled(g.ID); on {
		t.Fatal("should be off")
	}
	if err := v.SetHistory(g.ID, nil); err != nil { // back to the default (on)
		t.Fatal(err)
	}
	*clock = clock.Add(time.Hour)
	write2(t, v, g.ID, "v3")
	eq(t, versions(t, v, g.ID), "v3", "v2", "v1") // v2 was saved as the seed
}

func TestVaultDefaultOffAndPerNoteOn(t *testing.T) {
	v, _ := open(t)
	fakeClock(v)
	v.SetHistoryDefault(false)
	a := must(v.CreateNote("a.md", []byte("a")))
	b := must(v.CreateNote("b.md", []byte("b")))
	on := true
	v.SetHistory(b.ID, &on)
	if len(must(v.History(a.ID))) != 0 {
		t.Fatal("note a should have no history")
	}
	write2(t, v, b.ID, "b2")
	eq(t, versions(t, v, b.ID), "b2", "b") // seeded with the text from before
}

func TestSeedKeepsTextFromBeforeHistoryStarted(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	v.SetHistoryDefault(false)
	g := must(v.CreateNote("n.md", []byte("written while history was off")))
	v.SetHistoryDefault(true)
	*clock = clock.Add(time.Minute)
	write2(t, v, g.ID, "after")
	eq(t, versions(t, v, g.ID), "after", "written while history was off")
}

func TestOutsideEditsAndAdoptedNotesGetVersions(t *testing.T) {
	v, root := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("v1")))
	*clock = clock.Add(time.Hour)
	write(t, root, "n.md", "edited elsewhere")
	write(t, root, "found.md", "adopted")
	rescan(t, v)
	eq(t, versions(t, v, g.ID), "edited elsewhere", "v1")
	for _, gr := range must(v.List()) {
		if gr.Note.Path == "found.md" {
			eq(t, versions(t, v, gr.ID), "adopted")
		}
	}
	if c := rescan(t, v); len(c) != 0 {
		t.Fatalf("second scan: %+v", c)
	}
}

func TestHistorySurvivesMoveTrashAndRestore(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("a/n.md", []byte("v1")))
	*clock = clock.Add(time.Hour)
	write2(t, v, g.ID, "v2")
	if err := v.Move(g.ID, "b"); err != nil {
		t.Fatal(err)
	}
	eq(t, versions(t, v, g.ID), "v2", "v1")
	v.Trash(g.ID)
	if _, err := v.History(g.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("history of a trashed note: %v", err)
	}
	if err := v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	eq(t, versions(t, v, g.ID), "v2", "v1")
}

func TestPurgeRemovesHistory(t *testing.T) {
	v, _ := open(t)
	fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("v1")))
	keep := must(v.CreateNote("keep.md", []byte("keep")))
	v.Trash(g.ID)
	age(t, v, g.ID, 90*24*time.Hour)
	if n, err := v.Purge(time.Now().Add(-time.Hour)); n != 1 || err != nil {
		t.Fatalf("%d %v", n, err)
	}
	if _, err := os.Stat(v.historyPath(g.ID)); !os.IsNotExist(err) {
		t.Fatal("history of a purged note is still on disk")
	}
	if len(must(v.History(keep.ID))) != 1 {
		t.Fatal("purge touched another note's history")
	}
}

func TestFailedVersionSaveRefusesTheWrite(t *testing.T) {
	v, root := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("safe")))
	*clock = clock.Add(time.Hour)
	// A plain file where the history folder should be makes saving a version fail.
	os.RemoveAll(filepath.Join(root, ".ids/history"))
	os.WriteFile(filepath.Join(root, ".ids/history"), []byte("in the way"), 0o600)
	_, base, _ := v.ReadNote(g.ID)
	if _, err := v.WriteNote(g.ID, []byte("new"), base); err == nil {
		t.Fatal("write should have been refused")
	}
	if b, _, _ := v.ReadNote(g.ID); string(b) != "safe" {
		t.Fatalf("note changed anyway: %q", b)
	}
}

func TestFailedCreateLeavesNoHistoryBehind(t *testing.T) {
	v, root := open(t)
	fakeClock(v)
	must(v.CreateNote("n.md", []byte("one")))
	if _, err := v.CreateNote("n.md", []byte("two")); !errors.Is(err, ErrExists) {
		t.Fatalf("got %v", err)
	}
	if ents, _ := os.ReadDir(filepath.Join(root, ".ids/history")); len(ents) != 1 {
		t.Fatalf("history folders: %d", len(ents))
	}
}

func TestBadVersionAndNoteIDs(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("n.md", []byte("x")))
	for _, id := range []string{"", "../x", "1", "9999999999999", "abcdefghijklm"} {
		if _, err := v.ReadVersion(g.ID, id); !errors.Is(err, ErrNotFound) {
			t.Errorf("ReadVersion %q: %v", id, err)
		}
		if _, err := v.RestoreVersion(g.ID, id, "x"); !errors.Is(err, ErrNotFound) {
			t.Errorf("RestoreVersion %q: %v", id, err)
		}
	}
	if _, err := v.History("../x"); !errors.Is(err, ErrNotFound) {
		t.Errorf("History bad id: %v", err)
	}
}

func TestStrayFilesInHistoryFolderAreIgnored(t *testing.T) {
	v, _ := open(t)
	fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("x")))
	dir := v.historyPath(g.ID)
	os.WriteFile(filepath.Join(dir, ".styli-tmp-1"), []byte("half"), 0o600)
	os.WriteFile(filepath.Join(dir, "notes.txt"), []byte("hi"), 0o600)
	eq(t, versions(t, v, g.ID), "x")
}

func TestOutsideEditIsKeptWhenTheAppWritesBeforeTheScan(t *testing.T) {
	v, root := open(t)
	clock := fakeClock(v)
	g := must(v.CreateNote("n.md", []byte("v1")))
	*clock = clock.Add(time.Hour)
	write(t, root, "n.md", "edited elsewhere") // no Rescan yet
	write2(t, v, g.ID, "app edit")
	eq(t, versions(t, v, g.ID), "app edit", "edited elsewhere", "v1")
}
