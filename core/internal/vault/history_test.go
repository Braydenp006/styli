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
	t := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC) // a Thursday
	v.now = func() time.Time { return t }
	v.loc = time.UTC
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

// --- pruning ---

func day(y int, m time.Month, d, h int) time.Time { return time.Date(y, m, d, h, 0, 0, 0, time.UTC) }

// noteWithVersions creates a note and saves one version per (time, text) pair,
// with the policy still off, so tests can then set a policy and prune.
func noteWithVersions(t *testing.T, v *Vault, clock *time.Time, steps ...any) Group {
	t.Helper()
	v.SetHistoryWindow(0) // every change is its own version
	var g Group
	for i := 0; i < len(steps); i += 2 {
		*clock = steps[i].(time.Time)
		text := steps[i+1].(string)
		if i == 0 {
			g = must(v.CreateNote("n.md", []byte(text)))
		} else {
			write2(t, v, g.ID, text)
		}
	}
	return g
}

func setPolicy(t *testing.T, v *Vault, p HistoryPolicy) {
	t.Helper()
	if err := v.SetHistoryPolicy(p); err != nil {
		t.Fatal(err)
	}
}

func TestMaxVersionsKeepsTheNewest(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	setPolicy(t, v, HistoryPolicy{MaxVersions: 3})
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 9), "a", day(2026, 1, 1, 10), "b", day(2026, 1, 1, 11), "c",
		day(2026, 1, 1, 12), "d", day(2026, 1, 1, 13), "e")
	eq(t, versions(t, v, g.ID), "e", "d", "c") // pruned as each version was saved
}

func TestMaxAgeDropsOldVersionsAndDryRunDeletesNothing(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 0), "a", day(2026, 1, 2, 0), "b", day(2026, 1, 3, 0), "c", day(2026, 1, 4, 0), "d")
	*clock = day(2026, 1, 4, 8) // a is 80h old, b 56h, c 32h
	setPolicy(t, v, HistoryPolicy{MaxAge: 36 * time.Hour})
	if n, err := v.PruneHistory(true); n != 2 || err != nil {
		t.Fatalf("dry run: %d %v", n, err)
	}
	eq(t, versions(t, v, g.ID), "d", "c", "b", "a")
	if n, err := v.PruneHistory(false); n != 2 || err != nil {
		t.Fatalf("prune: %d %v", n, err)
	}
	eq(t, versions(t, v, g.ID), "d", "c")
	if n, _ := v.PruneHistory(false); n != 0 {
		t.Fatalf("second prune deleted %d", n)
	}
}

func TestNewestVersionIsNeverPruned(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 9), "only")
	*clock = day(2026, 6, 1, 9)
	setPolicy(t, v, HistoryPolicy{MaxAge: time.Hour, MaxVersions: 1, PerPeriod: 1, Period: PeriodDay})
	if n, _ := v.PruneHistory(false); n != 0 {
		t.Fatalf("deleted %d", n)
	}
	eq(t, versions(t, v, g.ID), "only")
}

func TestOneVersionPerDayThinsFinishedDaysOnly(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 10), "a1", day(2026, 1, 1, 12), "a2", day(2026, 1, 1, 18), "a3",
		day(2026, 1, 2, 9), "b1", day(2026, 1, 2, 15), "b2")
	*clock = day(2026, 1, 2, 20) // Jan 2 is still today
	setPolicy(t, v, HistoryPolicy{PerPeriod: 1, Period: PeriodDay})
	if n, _ := v.PruneHistory(true); n != 2 {
		t.Fatalf("dry run counted %d", n)
	}
	v.PruneHistory(false)
	eq(t, versions(t, v, g.ID), "b2", "b1", "a3") // yesterday: its last state; today: untouched

	*clock = day(2026, 1, 3, 8) // the day after: today's versions are thinned too
	v.PruneHistory(false)
	eq(t, versions(t, v, g.ID), "b2", "a3")
}

func TestPerPeriodCountAndASingleBusyDay(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 10), "a1", day(2026, 1, 1, 12), "a2", day(2026, 1, 1, 18), "a3")
	*clock = day(2026, 1, 9, 8)
	setPolicy(t, v, HistoryPolicy{PerPeriod: 2, Period: PeriodDay})
	v.PruneHistory(false)
	eq(t, versions(t, v, g.ID), "a3", "a2")
}

func TestWeeklyThinning(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 5, 9), "w1a", day(2026, 1, 7, 9), "w1b", day(2026, 1, 9, 9), "w1c", day(2026, 1, 12, 9), "w2a")
	*clock = day(2026, 1, 13, 9) // Mondays are Jan 5 and Jan 12
	setPolicy(t, v, HistoryPolicy{PerPeriod: 1, Period: PeriodWeek})
	v.PruneHistory(false)
	eq(t, versions(t, v, g.ID), "w2a", "w1c")
}

func TestMonthlyThinningAcrossNewYear(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2025, 12, 10, 9), "d1", day(2025, 12, 20, 9), "d2", day(2026, 1, 3, 9), "j1")
	*clock = day(2026, 1, 20, 9)
	setPolicy(t, v, HistoryPolicy{PerPeriod: 1, Period: PeriodMonth})
	v.PruneHistory(false)
	eq(t, versions(t, v, g.ID), "j1", "d2")
}

func TestDaysFollowTheVaultTimeZone(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	v.loc = time.FixedZone("UTC-8", -8*3600) // 20:00 and 03:00 UTC are both Jan 1 locally
	g := noteWithVersions(t, v, clock, time.Date(2026, 1, 1, 20, 0, 0, 0, time.UTC), "x1", time.Date(2026, 1, 2, 3, 0, 0, 0, time.UTC), "x2")
	*clock = day(2026, 1, 5, 9)
	setPolicy(t, v, HistoryPolicy{PerPeriod: 1, Period: PeriodDay})
	v.PruneHistory(false)
	eq(t, versions(t, v, g.ID), "x2")
}

func TestLimitsCombine(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 9), "a1", day(2026, 1, 1, 15), "a2", day(2026, 1, 2, 9), "b1",
		day(2026, 1, 2, 15), "b2", day(2026, 1, 3, 9), "c1")
	*clock = day(2026, 1, 3, 12)
	setPolicy(t, v, HistoryPolicy{PerPeriod: 1, Period: PeriodDay, MaxVersions: 2})
	v.PruneHistory(false) // thinning leaves a2, b2, c1; the count limit then drops a2
	eq(t, versions(t, v, g.ID), "c1", "b2")
}

func TestPruningLeavesEverythingElseAlone(t *testing.T) {
	v, root := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 9), "a", day(2026, 1, 2, 9), "b")
	trashed := must(v.CreateNote("t.md", []byte("trashed")))
	v.Trash(trashed.ID)
	dir := v.historyPath(g.ID)
	os.WriteFile(filepath.Join(dir, "notes.txt"), []byte("stray"), 0o600)
	os.WriteFile(filepath.Join(dir, ".styli-tmp-1"), []byte("half"), 0o600)
	*clock = day(2026, 6, 1, 9)
	setPolicy(t, v, HistoryPolicy{MaxAge: time.Hour, MaxVersions: 1})
	if n, err := v.PruneHistory(false); n != 1 || err != nil {
		t.Fatalf("%d %v", n, err)
	}
	for _, f := range []string{"notes.txt", ".styli-tmp-1"} {
		if _, err := os.Stat(filepath.Join(dir, f)); err != nil {
			t.Errorf("%s was deleted", f)
		}
	}
	if _, err := os.Stat(v.historyPath(trashed.ID)); err != nil {
		t.Error("a trashed note's history was pruned")
	}
	if b, _, err := v.ReadNote(g.ID); err != nil || string(b) != "b" || !exists(root, "n.md") {
		t.Fatal("pruning touched the note itself")
	}
}

func TestRestoreStillWorksAfterPruning(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2026, 1, 1, 9), "a", day(2026, 1, 2, 9), "b", day(2026, 1, 3, 9), "c")
	setPolicy(t, v, HistoryPolicy{MaxVersions: 2})
	v.PruneHistory(false)
	vs := must(v.History(g.ID))
	_, base, _ := v.ReadNote(g.ID)
	if _, err := v.RestoreVersion(g.ID, vs[1].ID, base); err != nil { // "b"
		t.Fatal(err)
	}
	if b, _, _ := v.ReadNote(g.ID); string(b) != "b" {
		t.Fatalf("got %q", b)
	}
}

func TestBadPolicyIsRefused(t *testing.T) {
	v, _ := open(t)
	for _, p := range []HistoryPolicy{
		{MaxVersions: -1}, {MaxAge: -time.Hour}, {PerPeriod: -1},
		{PerPeriod: 1}, {PerPeriod: 1, Period: 9},
	} {
		if err := v.SetHistoryPolicy(p); !errors.Is(err, ErrBadPolicy) {
			t.Errorf("%+v: %v", p, err)
		}
	}
	if err := v.SetHistoryPolicy(HistoryPolicy{}); err != nil { // all off is fine
		t.Fatal(err)
	}
}

func TestNothingIsPrunedByDefault(t *testing.T) {
	v, _ := open(t)
	clock := fakeClock(v)
	g := noteWithVersions(t, v, clock, day(2020, 1, 1, 9), "a", day(2021, 1, 1, 9), "b", day(2022, 1, 1, 9), "c")
	*clock = day(2030, 1, 1, 9)
	if n, _ := v.PruneHistory(false); n != 0 {
		t.Fatalf("deleted %d with no policy set", n)
	}
	eq(t, versions(t, v, g.ID), "c", "b", "a")
}
