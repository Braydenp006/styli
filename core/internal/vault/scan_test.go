package vault

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"
)

func write(t *testing.T, root, rel, content string) {
	t.Helper()
	p := filepath.Join(root, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func rescan(t *testing.T, v *Vault) []Change {
	t.Helper()
	c, _, err := v.Rescan()
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestRescanModified(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("n.md", []byte("one")))
	if c := rescan(t, v); len(c) != 0 {
		t.Fatalf("app's own write looked like a change: %+v", c)
	}
	write(t, root, "n.md", "edited elsewhere")
	if c := rescan(t, v); !reflect.DeepEqual(c, []Change{{Modified, g.ID}}) {
		t.Fatalf("got %+v", c)
	}
	b, ver, _ := v.ReadNote(g.ID)
	if got := must(v.Get(g.ID)); string(b) != "edited elsewhere" || got.Note.Version != ver {
		t.Fatalf("record not updated: %+v", got)
	}
	if c := rescan(t, v); len(c) != 0 {
		t.Fatalf("second scan: %+v", c)
	}
}

func TestFreshVaultInstanceFindsNothingToDo(t *testing.T) {
	v, root := open(t)
	must(v.CreateNote("n.md", []byte("one")))
	must(v.ImportFile("p.pdf", strings.NewReader("pdf")))
	v2 := must(Open(root))
	if c := rescan(t, v2); len(c) != 0 {
		t.Fatalf("got %+v", c)
	}
}

func TestRescanMovedFileAndFolder(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a/n.md", []byte("note")))
	must(v.AddFile(g.ID, "src.pdf", strings.NewReader("pdf")))
	h := must(v.CreateNote("x.md", []byte("loose")))

	if err := os.Rename(filepath.Join(root, "a"), filepath.Join(root, "renamed")); err != nil { // whole folder
		t.Fatal(err)
	}
	os.MkdirAll(filepath.Join(root, "y"), 0o755)
	if err := os.Rename(filepath.Join(root, "x.md"), filepath.Join(root, "y/x2.md")); err != nil { // one file, new name
		t.Fatal(err)
	}
	c := rescan(t, v)
	if !reflect.DeepEqual(c, []Change{{Moved, g.ID}, {Moved, h.ID}}) && !reflect.DeepEqual(c, []Change{{Moved, h.ID}, {Moved, g.ID}}) {
		t.Fatalf("got %+v", c)
	}
	got := must(v.Get(g.ID))
	if got.Note.Path != "renamed/n.md" || got.Files[0].Path != "renamed/src.pdf" {
		t.Fatalf("%+v", got)
	}
	if must(v.Get(h.ID)).Note.Path != "y/x2.md" {
		t.Fatal("renamed file lost its ID")
	}
	if len(must(v.List())) != 2 {
		t.Fatal("a move created a new group")
	}
}

func TestRescanMissingKeepsRecord(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("n.md", []byte("precious")))
	if err := os.Remove(filepath.Join(root, "n.md")); err != nil {
		t.Fatal(err)
	}
	if c := rescan(t, v); !reflect.DeepEqual(c, []Change{{WentMissing, g.ID}}) {
		t.Fatalf("got %+v", c)
	}
	if c := rescan(t, v); len(c) != 0 {
		t.Fatalf("reported twice: %+v", c)
	}
	m := must(v.Missing())
	if !reflect.DeepEqual(m, []MissingGroup{{g.ID, []string{"n.md"}}}) {
		t.Fatalf("got %+v", m)
	}
	if _, err := v.Get(g.ID); err != nil {
		t.Fatal("record was deleted")
	}
	write(t, root, "n.md", "precious") // restored
	if c := rescan(t, v); len(c) != 0 {
		t.Fatalf("restored file: %+v", c)
	}
	if m := must(v.Missing()); len(m) != 0 {
		t.Fatalf("still missing: %+v", m)
	}
}

func TestMissingIsReportedAgainAfterRestart(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("n.md", []byte("x")))
	os.Remove(filepath.Join(root, "n.md"))
	v2 := must(Open(root))
	if c := rescan(t, v2); !reflect.DeepEqual(c, []Change{{WentMissing, g.ID}}) {
		t.Fatalf("got %+v", c)
	}
}

func TestRescanAdoptsNewFilesAndIgnoresNoise(t *testing.T) {
	v, root := open(t)
	outside := t.TempDir()
	write(t, root, "Notes/new.md", "hello")
	write(t, root, "Notes/slides.pdf", "%PDF")
	write(t, root, "Notes/.hidden.md", "no")
	write(t, root, "Notes/backup.md~", "no")
	write(t, root, "Notes/x.md.swp", "no")
	write(t, root, ".git/config", "no")
	write(t, root, "Notes/.styli-tmp-123", "no")
	write(t, outside, "real.md", "no")
	if err := os.Symlink(filepath.Join(outside, "real.md"), filepath.Join(root, "link.md")); err != nil {
		t.Skip("symlinks unavailable:", err)
	}
	c := rescan(t, v)
	if len(c) != 2 || c[0].Kind != Added || c[1].Kind != Added {
		t.Fatalf("got %+v", c)
	}
	gs := must(v.List())
	var notes, pdfs int
	for _, g := range gs {
		if g.Note != nil && g.Note.Path == "Notes/new.md" {
			notes++
		}
		if g.Note == nil && len(g.Files) == 1 && g.Files[0].Path == "Notes/slides.pdf" {
			pdfs++
		}
	}
	if notes != 1 || pdfs != 1 || len(gs) != 2 {
		t.Fatalf("groups: %+v", gs)
	}
	if c := rescan(t, v); len(c) != 0 {
		t.Fatalf("second scan: %+v", c)
	}
}

func TestRescanSkipsFreshFiles(t *testing.T) {
	old := Settle
	Settle = time.Hour
	defer func() { Settle = old }()
	v, root := open(t)
	write(t, root, "new.md", "half writ")
	c, pending, err := v.Rescan()
	if err != nil || len(c) != 0 || !pending {
		t.Fatalf("changes %+v pending %v err %v", c, pending, err)
	}
	Settle = 0
	if c := rescan(t, v); len(c) != 1 || c[0].Kind != Added {
		t.Fatalf("got %+v", c)
	}
}

func TestEmptyFilesAreNeverMatchedAsMoves(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a.md", nil))
	os.Remove(filepath.Join(root, "a.md"))
	write(t, root, "b.md", "") // a different empty file
	c := rescan(t, v)
	if len(c) != 2 || c[0].Kind != Added || c[1] != (Change{WentMissing, g.ID}) {
		t.Fatalf("got %+v", c)
	}
	if must(v.Get(g.ID)).Note.Path != "a.md" {
		t.Fatal("record moved onto an unrelated empty file")
	}
}

func TestIdenticalContentDoesNotShareAnID(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("orig.md", []byte("same text")))
	os.Remove(filepath.Join(root, "orig.md"))
	write(t, root, "copy1.md", "same text")
	write(t, root, "copy2.md", "same text")
	c := rescan(t, v)
	var moved, added int
	for _, ch := range c {
		switch ch.Kind {
		case Moved:
			moved++
			if ch.ID != g.ID {
				t.Error("moved the wrong group")
			}
		case Added:
			added++
		}
	}
	if moved != 1 || added != 1 || len(must(v.List())) != 2 {
		t.Fatalf("got %+v", c)
	}
}
