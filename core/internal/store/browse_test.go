package store

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"styli/core/internal/vault"
)

func note(id, path string) vault.Group {
	return vault.Group{ID: id, Note: &vault.File{Path: path, Version: "v-" + id}}
}

func ids(hits []Hit) []string {
	var out []string
	for _, h := range hits {
		out = append(out, h.ID)
	}
	return out
}

func put(t *testing.T, s *Store, g vault.Group, text string, mod time.Time) {
	t.Helper()
	if err := s.Upsert(g, []byte(text), mod); err != nil {
		t.Fatal(err)
	}
}

func at(day int) time.Time { return time.Date(2026, 3, day, 12, 0, 0, 0, time.UTC) }

func TestBrowseListsNewestFirstWithPaging(t *testing.T) {
	s := newStore(t)
	put(t, s, note("a", "one.md"), "# One", at(1))
	put(t, s, note("b", "two.md"), "# Two", at(3))
	put(t, s, note("c", "three.md"), "# Three", at(2))
	got := must(s.Find(Query{}))
	if !reflect.DeepEqual(ids(got), []string{"b", "c", "a"}) || got[0].Snippet != "" || !got[0].Modified.Equal(at(3)) {
		t.Fatalf("%+v", got)
	}
	if len(must(s.Find(Query{Limit: 2}))) != 2 {
		t.Fatal("limit")
	}
	if !reflect.DeepEqual(ids(must(s.Find(Query{Limit: 1, Offset: 1}))), []string{"c"}) {
		t.Fatal("offset")
	}
	if len(must(s.Find(Query{Offset: -5}))) != 3 {
		t.Fatal("negative offset")
	}
}

func TestFolderFilterCoversSubfoldersOnly(t *testing.T) {
	s := newStore(t)
	put(t, s, note("root", "r.md"), "x", at(1))
	put(t, s, note("a", "a/n.md"), "x", at(1))
	put(t, s, note("deep", "a/b/n.md"), "x", at(1))
	put(t, s, note("ab", "ab/n.md"), "x", at(1)) // must not match folder "a"
	put(t, s, note("pct", "50%_off/n.md"), "x", at(1))
	put(t, s, note("other", "5000/n.md"), "x", at(1))
	for folder, want := range map[string][]string{
		"a":       {"a", "deep"},
		"a/":      {"a", "deep"},
		"a/b":     {"deep"},
		"50%_off": {"pct"}, // % and _ are not wildcards
		"nowhere": nil,
	} {
		if got := ids(must(s.Find(Query{Folder: folder}))); !sameSet(got, want) {
			t.Errorf("%q: got %v want %v", folder, got, want)
		}
	}
	if len(must(s.Find(Query{Folder: ""}))) != 6 || len(must(s.Find(Query{Folder: "."}))) != 6 {
		t.Fatal("empty folder should mean anywhere")
	}
}

func sameSet(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	m := map[string]int{}
	for _, x := range a {
		m[x]++
	}
	for _, x := range b {
		m[x]--
	}
	for _, n := range m {
		if n != 0 {
			return false
		}
	}
	return true
}

func TestTagFilterNeedsEveryTag(t *testing.T) {
	s := newStore(t)
	put(t, s, note("a", "a.md"), "<!-- styli:tags tcp, net -->", at(1))
	put(t, s, note("b", "b.md"), "<!-- styli:tags tcp -->", at(2))
	put(t, s, note("c", "c.md"), "no tags", at(3))
	if got := ids(must(s.Find(Query{Tags: []string{"tcp"}}))); !sameSet(got, []string{"a", "b"}) {
		t.Fatalf("one tag: %v", got)
	}
	if got := ids(must(s.Find(Query{Tags: []string{"#TCP", " net "}}))); !sameSet(got, []string{"a"}) {
		t.Fatalf("two tags, mixed case: %v", got)
	}
	if got := must(s.Find(Query{Tags: []string{"tcp", "missing"}})); len(got) != 0 {
		t.Fatalf("%v", ids(got))
	}
	if got := must(s.Find(Query{Tags: []string{"", " "}})); len(got) != 3 {
		t.Fatal("blank tags should be ignored")
	}
	want := []TagCount{{"tcp", 2}, {"net", 1}}
	if got := must(s.Tags()); !reflect.DeepEqual(got, want) {
		t.Fatalf("tags: %+v", got)
	}
}

func TestDateFilterIsInclusive(t *testing.T) {
	s := newStore(t)
	put(t, s, note("a", "a.md"), "x", at(1))
	put(t, s, note("b", "b.md"), "x", at(5))
	put(t, s, note("c", "c.md"), "x", at(9))
	for name, c := range map[string]struct {
		q    Query
		want []string
	}{
		"from":    {Query{From: at(5)}, []string{"b", "c"}},
		"to":      {Query{To: at(5)}, []string{"a", "b"}},
		"between": {Query{From: at(2), To: at(8)}, []string{"b"}},
		"empty":   {Query{From: at(6), To: at(8)}, nil},
	} {
		if got := ids(must(s.Find(c.q))); !sameSet(got, c.want) {
			t.Errorf("%s: got %v want %v", name, got, c.want)
		}
	}
}

func TestTextAndFiltersCombine(t *testing.T) {
	s := newStore(t)
	put(t, s, note("a", "net/tcp.md"), "# TCP\nhandshake <!-- styli:tags net -->", at(2))
	put(t, s, note("b", "net/udp.md"), "# UDP\nhandshake", at(4))
	put(t, s, note("c", "os/sched.md"), "# Scheduler\nhandshake <!-- styli:tags net -->", at(6))
	q := Query{Text: "handshake", Folder: "net", Tags: []string{"net"}, From: at(1), To: at(3)}
	got := must(s.Find(q))
	if !reflect.DeepEqual(ids(got), []string{"a"}) || !strings.Contains(got[0].Snippet, MatchStart+"handshake"+MatchEnd) {
		t.Fatalf("%+v", got)
	}
	if got := ids(must(s.Find(Query{Text: "handshake", Folder: "net"}))); !sameSet(got, []string{"a", "b"}) {
		t.Fatalf("%v", got)
	}
	if got := must(s.Find(Query{Text: `" - * (`, Tags: []string{"net"}})); len(got) != 0 {
		t.Fatalf("%v", got)
	}
}

func TestPDFFirstGroupAppearsWhenBrowsing(t *testing.T) {
	s := newStore(t)
	g := vault.Group{ID: "p", Files: []vault.File{{Path: "Lectures/week1.pdf", Version: "v"}, {Path: "Lectures/extra.png", Version: "w"}}}
	if err := s.Upsert(g, nil, at(1)); err != nil {
		t.Fatal(err)
	}
	got := must(s.Find(Query{Folder: "Lectures"}))
	if len(got) != 1 || got[0].Path != "Lectures/extra.png" || got[0].Title != "week1" {
		t.Fatalf("%+v", got)
	}
	if len(must(s.Find(Query{Text: "week1"}))) != 0 {
		t.Fatal("a group with no text cannot match a text search")
	}
}

func TestFoldersIncludeParentsAndSkipTheRoot(t *testing.T) {
	s := newStore(t)
	put(t, s, note("r", "root.md"), "x", at(1))
	put(t, s, note("a", "a/b/c/n.md"), "x", at(1))
	put(t, s, note("b", "a/x/n.md"), "x", at(1))
	want := []string{"a", "a/b", "a/b/c", "a/x"}
	if got := must(s.Folders()); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v", got)
	}
}

func TestMovedNoteChangesFolder(t *testing.T) {
	s := newStore(t)
	put(t, s, note("a", "old/n.md"), "x", at(1))
	put(t, s, note("a", "new/n.md"), "x", at(1))
	if len(must(s.Find(Query{Folder: "old"}))) != 0 || len(must(s.Find(Query{Folder: "new"}))) != 1 {
		t.Fatal("folder did not follow the move")
	}
	if got := must(s.Folders()); !reflect.DeepEqual(got, []string{"new"}) {
		t.Fatalf("%v", got)
	}
}

func TestUpgradeFromVersion1KeepsDataAndReindexFillsFolders(t *testing.T) {
	// A real version-1 database holding one row for a real vault note.
	vt := newVault(t)
	g := must(vt.CreateNote("a/n.md", []byte("# Old title")))
	dir := t.TempDir()
	only1 := fstest.MapFS{"migrations/0001_init.sql": {Data: must(migrationFiles.ReadFile("migrations/0001_init.sql"))}}
	db, path, err := openDB(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := migrate(db, path, only1); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO groups (id, note_path, note_version, title, created_at, modified_at)
		VALUES (?, 'a/n.md', 'v', 'Old title', 1, 1)`, g.ID); err != nil {
		t.Fatal(err)
	}
	db.Close()

	s, err := Open(dir) // upgrades to the current version
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err := os.Stat(filepath.Join(dir, "index.db.bak-v1")); err != nil {
		t.Fatal("no backup before upgrading")
	}
	if got := must(s.Find(Query{})); len(got) != 1 || got[0].ID != g.ID {
		t.Fatalf("data lost in the upgrade: %+v", got)
	}
	if len(must(s.Find(Query{Folder: "a"}))) != 0 {
		t.Fatal("folder should be empty until a reindex")
	}
	if err := s.Reindex(vt); err != nil { // runs at every startup
		t.Fatal(err)
	}
	if got := must(s.Find(Query{Folder: "a"})); len(got) != 1 || got[0].ID != g.ID {
		t.Fatalf("after reindex: %+v", got)
	}
}

func TestSnippetMarkersAreNotBrackets(t *testing.T) {
	s := newStore(t)
	put(t, s, note("a", "a.md"), "# Links\nsee [plum](x) and [ ] plum jam [done]", at(1))
	got := must(s.Find(Query{Text: "plum"}))
	if len(got) != 1 || !strings.Contains(got[0].Snippet, MatchStart+"plum"+MatchEnd) {
		t.Fatalf("%q", got)
	}
	// Brackets in the note stay brackets, so a UI can tell them from match marks.
	if !strings.Contains(got[0].Snippet, "[") || strings.Count(got[0].Snippet, MatchStart) != strings.Count(got[0].Snippet, MatchEnd) {
		t.Fatalf("%q", got[0].Snippet)
	}
}

func TestNoteInfoHasTitleModifiedAndTags(t *testing.T) {
	s := newStore(t)
	put(t, s, note("a", "Net/tcp.md"), "# TCP\n<!-- styli:tags tcp, net -->", at(4))
	put(t, s, note("b", "other.md"), "# Other", at(5))
	info := must(s.Note("a"))
	if info.ID != "a" || info.Title != "TCP" || info.Path != "Net/tcp.md" || !info.Modified.Equal(at(4)) ||
		!reflect.DeepEqual(info.Tags, []string{"net", "tcp"}) {
		t.Fatalf("%+v", info)
	}
	if got := must(s.Note("b")); got.Tags == nil || len(got.Tags) != 0 {
		t.Fatalf("a note with no tags should have an empty list, got %#v", got.Tags)
	}
	if _, err := s.Note("missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("got %v", err)
	}
}

func TestCounts(t *testing.T) {
	s := newStore(t)
	if n, f, err := s.Counts(); n != 0 || f != 0 || err != nil {
		t.Fatalf("empty: %d %d %v", n, f, err)
	}
	g := note("a", "a.md")
	g.Files = []vault.File{{Path: "a.pdf", Version: "p"}, {Path: "a.png", Version: "q"}}
	put(t, s, g, "x", at(1))
	put(t, s, note("b", "b.md"), "y", at(1))
	pdfOnly := vault.Group{ID: "c", Files: []vault.File{{Path: "c.pdf", Version: "r"}}}
	if err := s.Upsert(pdfOnly, nil, at(1)); err != nil {
		t.Fatal(err)
	}
	if n, f, _ := s.Counts(); n != 2 || f != 3 {
		t.Fatalf("got %d notes, %d files", n, f)
	}
	s.Remove("a")
	if n, f, _ := s.Counts(); n != 1 || f != 1 {
		t.Fatalf("after remove: %d notes, %d files", n, f)
	}
}
