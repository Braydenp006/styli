package store

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"styli/core/internal/vault"
)

func must[T any](v T, err error) T {
	if err != nil {
		panic(err) // a test panic fails that test with the error shown
	}
	return v
}

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func newVault(t *testing.T) *vault.Vault {
	t.Helper()
	return must(vault.Open(t.TempDir()))
}

// dump is everything the index holds, minus created_at (which is "now").
func dump(t *testing.T, s *Store) []string {
	t.Helper()
	var out []string
	for _, q := range []string{
		`SELECT id, IFNULL(note_path,''), IFNULL(note_version,''), title, modified_at FROM groups ORDER BY id`,
		`SELECT g.id, f.path, f.version FROM files f JOIN groups g ON g.n = f.group_n ORDER BY 1, 2`,
		`SELECT g.id, t.tag FROM tags t JOIN groups g ON g.n = t.group_n ORDER BY 1, 2`,
		`SELECT g.id, fts.title, fts.body, fts.folder FROM notes_fts fts JOIN groups g ON g.n = fts.rowid ORDER BY 1`,
	} {
		rows, err := s.db.Query(q)
		if err != nil {
			t.Fatal(err)
		}
		cols, _ := rows.Columns()
		for rows.Next() {
			vals := make([]any, len(cols))
			ptrs := make([]any, len(cols))
			for i := range vals {
				ptrs[i] = &vals[i]
			}
			if err := rows.Scan(ptrs...); err != nil {
				t.Fatal(err)
			}
			out = append(out, fmt.Sprint(vals...))
		}
		rows.Close()
	}
	return out
}

func TestFreshDatabaseMigratesAndReopens(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	var v int
	s.db.QueryRow(`PRAGMA user_version`).Scan(&v)
	if v != 1 {
		t.Fatalf("user_version %d", v)
	}
	s.Close()
	s, err = Open(dir)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	s.Close()
	if _, err := os.Stat(filepath.Join(dir, "index.db.bak-v1")); err == nil {
		t.Fatal("no backup expected when nothing migrated")
	}
}

func TestNewerDatabaseRefused(t *testing.T) {
	dir := t.TempDir()
	s, _ := Open(dir)
	s.db.Exec(`PRAGMA user_version = 999`)
	s.Close()
	if _, err := Open(dir); !errors.Is(err, ErrNewer) {
		t.Fatalf("want ErrNewer, got %v", err)
	}
}

func TestMigrationBacksUpFirst(t *testing.T) {
	dir := t.TempDir()
	m1 := fstest.MapFS{"migrations/0001_a.sql": {Data: []byte(`CREATE TABLE a (x TEXT); INSERT INTO a VALUES ('keep me');`)}}
	m2 := fstest.MapFS{
		"migrations/0001_a.sql": m1["migrations/0001_a.sql"],
		"migrations/0002_b.sql": {Data: []byte(`ALTER TABLE a ADD COLUMN y TEXT;`)},
	}
	db, path, err := openDB(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := migrate(db, path, m1); err != nil {
		t.Fatal(err)
	}
	db.Close()
	db, path, _ = openDB(dir)
	defer db.Close()
	if err := migrate(db, path, m2); err != nil {
		t.Fatal(err)
	}
	bak, err := os.ReadFile(path + ".bak-v1")
	if err != nil || !strings.Contains(string(bak), "keep me") {
		t.Fatalf("backup missing or incomplete: %v", err)
	}
	var v int
	db.QueryRow(`PRAGMA user_version`).Scan(&v)
	if v != 2 {
		t.Fatalf("user_version %d", v)
	}
}

func TestFailedMigrationRollsBack(t *testing.T) {
	dir := t.TempDir()
	bad := fstest.MapFS{"migrations/0001_a.sql": {Data: []byte(`CREATE TABLE a (x TEXT); NOT SQL;`)}}
	db, path, _ := openDB(dir)
	defer db.Close()
	if err := migrate(db, path, bad); err == nil {
		t.Fatal("want an error")
	}
	var v int
	db.QueryRow(`PRAGMA user_version`).Scan(&v)
	var n int
	db.QueryRow(`SELECT count(*) FROM sqlite_master WHERE name = 'a'`).Scan(&n)
	if v != 0 || n != 0 {
		t.Fatalf("half-applied migration: version %d, tables %d", v, n)
	}
}

func TestIndexAndSearch(t *testing.T) {
	v, s := newVault(t), newStore(t)
	g := must(v.CreateNote("Networking/tcp.md", []byte("# TCP handshake\n\nSYN, SYN-ACK, ACK.\n")))
	if err := s.Upsert(g, must(os.ReadFile(filepath.Join(v.Root(), "Networking/tcp.md"))), time.Now()); err != nil {
		t.Fatal(err)
	}
	for _, q := range []string{"handshake", "syn", "ack", "netw", "tcp hand"} { // body, title, folder, prefix
		hits := must(s.Search(q, 10))
		if len(hits) != 1 || hits[0].ID != g.ID || hits[0].Title != "TCP handshake" || hits[0].Path != "Networking/tcp.md" {
			t.Errorf("%q: got %+v", q, hits)
		}
	}
	if hits := must(s.Search("zebra", 10)); len(hits) != 0 {
		t.Errorf("unexpected hits %+v", hits)
	}
}

func TestSearchSyntaxCharsDoNotError(t *testing.T) {
	s := newStore(t)
	for _, q := range []string{`"`, `-`, `*`, `AND`, `foo"bar`, `(`, `NEAR(a b)`, `a OR`, `col:x`, ` `, ``, `^x`} {
		if _, err := s.Search(q, 10); err != nil {
			t.Errorf("%q: %v", q, err)
		}
	}
}

func TestMovedNoteKeepsRow(t *testing.T) {
	v, s := newVault(t), newStore(t)
	g := must(v.CreateNote("a/n.md", []byte("# Note\nbody")))
	s.Upsert(g, []byte("# Note\nbody"), time.Now())
	if err := v.Move(g.ID, "b"); err != nil {
		t.Fatal(err)
	}
	g = must(v.Get(g.ID))
	if err := s.Upsert(g, []byte("# Note\nbody"), time.Now()); err != nil {
		t.Fatal(err)
	}
	var n int
	s.db.QueryRow(`SELECT count(*) FROM groups`).Scan(&n)
	hits := must(s.Search("body", 10))
	if n != 1 || len(hits) != 1 || hits[0].Path != "b/n.md" {
		t.Fatalf("rows %d, hits %+v", n, hits)
	}
}

func TestDuplicatePathRejected(t *testing.T) {
	s := newStore(t)
	mk := func(id string) vault.Group {
		return vault.Group{ID: id, Note: &vault.File{Path: "same.md", Version: "v"}}
	}
	if err := s.Upsert(mk("a"), []byte("x"), time.Now()); err != nil {
		t.Fatal(err)
	}
	if err := s.Upsert(mk("b"), []byte("y"), time.Now()); err == nil {
		t.Fatal("want an error for a second group at the same path")
	}
	if hits := must(s.Search("y", 10)); len(hits) != 0 {
		t.Fatal("failed upsert left data behind")
	}
}

func TestPDFFirstGroupIndexedWithTitleFromFile(t *testing.T) {
	v, s := newVault(t), newStore(t)
	g := must(v.ImportFile("Lectures/week1.pdf", strings.NewReader("%PDF")))
	if err := s.Upsert(g, nil, time.Time{}); err != nil {
		t.Fatal(err)
	}
	var title string
	s.db.QueryRow(`SELECT title FROM groups WHERE id = ?`, g.ID).Scan(&title)
	if title != "week1" {
		t.Fatalf("title %q", title)
	}
}

func TestParseTags(t *testing.T) {
	got := parseTags([]byte("text\n<!-- styli:tags TCP, #handshake ,tcp,, net working -->\nmore <!--styli:tags z-->"))
	want := []string{"handshake", "net working", "tcp", "z"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %q want %q", got, want)
	}
	if parseTags([]byte("<!-- other comment -->")) != nil {
		t.Fatal("unrelated comment parsed as tags")
	}
}

func TestTagsFollowTheComment(t *testing.T) {
	s := newStore(t)
	g := vault.Group{ID: "a", Note: &vault.File{Path: "n.md", Version: "v"}}
	tags := func() []string {
		var out []string
		rows, _ := s.db.Query(`SELECT tag FROM tags ORDER BY tag`)
		defer rows.Close()
		for rows.Next() {
			var x string
			rows.Scan(&x)
			out = append(out, x)
		}
		return out
	}
	s.Upsert(g, []byte("<!-- styli:tags a, b -->"), time.Now())
	if got := tags(); !reflect.DeepEqual(got, []string{"a", "b"}) {
		t.Fatalf("got %q", got)
	}
	s.Upsert(g, []byte("<!-- styli:tags b, c -->"), time.Now())
	if got := tags(); !reflect.DeepEqual(got, []string{"b", "c"}) {
		t.Fatalf("got %q", got)
	}
}

func TestReindexEqualsIncrementalAndSelfHeals(t *testing.T) {
	v := newVault(t)
	a := must(v.CreateNote("a/one.md", []byte("# One\n<!-- styli:tags x, y -->\nalpha")))
	b := must(v.ImportFile("b/two.pdf", strings.NewReader("%PDF")))
	must(v.SetNote(b.ID, "two.md", []byte("beta")))
	must(v.AddFile(a.ID, "src.png", strings.NewReader("png")))

	inc := newStore(t) // built one group at a time, as the app will
	for _, id := range []string{a.ID, b.ID} {
		g := must(v.Get(id))
		content := must(os.ReadFile(filepath.Join(v.Root(), g.Note.Path)))
		fi := must(os.Stat(filepath.Join(v.Root(), g.Note.Path)))
		if err := inc.Upsert(g, content, fi.ModTime()); err != nil {
			t.Fatal(err)
		}
	}
	full := newStore(t) // rebuilt from the vault alone
	if err := full.Reindex(v); err != nil {
		t.Fatal(err)
	}
	if d1, d2 := dump(t, inc), dump(t, full); !reflect.DeepEqual(d1, d2) {
		t.Fatalf("incremental:\n%q\nreindex:\n%q", d1, d2)
	}

	// An outside edit and a stale row: Reindex fixes both.
	os.WriteFile(filepath.Join(v.Root(), "a/one.md"), []byte("# One\ngamma"), 0o644)
	inc.Upsert(vault.Group{ID: "ghost", Note: &vault.File{Path: "ghost.md", Version: "v"}}, []byte("phantom"), time.Now())
	if err := inc.Reindex(v); err != nil {
		t.Fatal(err)
	}
	if len(must(inc.Search("gamma", 10))) != 1 || len(must(inc.Search("alpha", 10))) != 0 || len(must(inc.Search("phantom", 10))) != 0 {
		t.Fatal("reindex did not pick up the outside edit or drop the stale group")
	}
}

func TestRemove(t *testing.T) {
	s := newStore(t)
	g := vault.Group{ID: "a", Note: &vault.File{Path: "n.md", Version: "v"}, Files: []vault.File{{Path: "n.pdf", Version: "p"}}}
	s.Upsert(g, []byte("hello <!-- styli:tags t -->"), time.Now())
	if err := s.Remove("a"); err != nil {
		t.Fatal(err)
	}
	if d := dump(t, s); len(d) != 0 {
		t.Fatalf("left behind: %q", d)
	}
	if err := s.Remove("a"); err != nil {
		t.Fatalf("removing twice: %v", err)
	}
}

func TestDataDirIsStablePerVault(t *testing.T) {
	a1, _ := DataDir("/x/vault")
	a2, _ := DataDir("/x/vault")
	b, _ := DataDir("/x/other")
	if a1 != a2 || a1 == b {
		t.Fatalf("%q %q %q", a1, a2, b)
	}
}
