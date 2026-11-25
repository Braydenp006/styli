package vault

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Tests write and scan files straight away, so do not skip "fresh" files.
func init() { Settle = 0 }

func open(t *testing.T) (*Vault, string) {
	t.Helper()
	root := t.TempDir()
	v, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	return v, root
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err) // a test panic fails that test with the error shown
	}
	return v
}

func TestCreateReadWriteMoveKeepID(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("Math/limits.md", []byte("# Limits")))
	b, ver, err := v.ReadNote(g.ID)
	if err != nil || string(b) != "# Limits" {
		t.Fatalf("read: %q %v", b, err)
	}
	ver2 := must(v.WriteNote(g.ID, []byte("# Limits v2"), ver))
	if ver2 == ver {
		t.Fatal("version should change")
	}
	if err := v.Move(g.ID, "Calculus"); err != nil {
		t.Fatal(err)
	}
	got := must(v.Get(g.ID))
	if got.Note.Path != "Calculus/limits.md" {
		t.Fatalf("path %q", got.Note.Path)
	}
	if b, _, _ := v.ReadNote(g.ID); string(b) != "# Limits v2" {
		t.Fatalf("content lost after move: %q", b)
	}
}

func TestCreateRefusesOverwrite(t *testing.T) {
	v, _ := open(t)
	must(v.CreateNote("a.md", []byte("one")))
	if _, err := v.CreateNote("a.md", []byte("two")); !errors.Is(err, ErrExists) {
		t.Fatalf("want ErrExists, got %v", err)
	}
}

func TestStaleWriteRejected(t *testing.T) {
	v, _ := open(t)
	g := must(v.CreateNote("a.md", []byte("v1")))
	_, base, _ := v.ReadNote(g.ID)
	must(v.WriteNote(g.ID, []byte("v2"), base)) // another editor wins
	if _, err := v.WriteNote(g.ID, []byte("mine"), base); !errors.Is(err, ErrConflict) {
		t.Fatalf("want ErrConflict, got %v", err)
	}
	if b, _, _ := v.ReadNote(g.ID); string(b) != "v2" {
		t.Fatalf("stale write changed the file: %q", b)
	}
}

func TestBadPaths(t *testing.T) {
	v, root := open(t)
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Skip("symlinks unavailable:", err)
	}
	for _, p := range []string{
		"../x.md", "/etc/x.md", "a/../../x.md", ".ids/x.md", "link/x.md",
		`a\b.md`, "c:x.md", ".styli-tmp-1.md", "",
	} {
		if _, err := v.CreateNote(p, []byte("x")); !errors.Is(err, ErrBadPath) {
			t.Errorf("%q: want ErrBadPath, got %v", p, err)
		}
	}
	if _, err := v.CreateNote("notmarkdown.txt", nil); !errors.Is(err, ErrBadPath) {
		t.Errorf("non-.md note: got %v", err)
	}
	if entries, _ := os.ReadDir(outside); len(entries) != 0 {
		t.Fatal("something was written outside the vault")
	}
}

func TestPDFFirstThenMarkdownSameID(t *testing.T) {
	v, root := open(t)
	g := must(v.ImportFile("Lectures/week1.pdf", strings.NewReader("%PDF-sample")))
	if g.Note != nil {
		t.Fatal("PDF-first group should have no note")
	}
	if _, _, err := v.ReadNote(g.ID); !errors.Is(err, ErrNoNote) {
		t.Fatalf("want ErrNoNote, got %v", err)
	}
	g2 := must(v.SetNote(g.ID, "week1.md", []byte("# Week 1")))
	if g2.ID != g.ID || g2.Note.Path != "Lectures/week1.md" {
		t.Fatalf("note not joined to group: %+v", g2)
	}
	if _, err := os.Stat(filepath.Join(root, "Lectures/week1.md")); err != nil {
		t.Fatal(err)
	}
	if _, err := v.SetNote(g.ID, "other.md", nil); !errors.Is(err, ErrHasNote) {
		t.Fatalf("want ErrHasNote, got %v", err)
	}
	gs := must(v.List())
	if len(gs) != 1 {
		t.Fatalf("want 1 group, got %d", len(gs))
	}
}

func TestMoveMovesWholeGroup(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a/n.md", []byte("note")))
	must(v.AddFile(g.ID, "src.pdf", strings.NewReader("pdf")))
	must(v.AddFile(g.ID, "sketch.png", strings.NewReader("png")))
	if err := v.Move(g.ID, "b/c"); err != nil {
		t.Fatal(err)
	}
	for _, f := range []string{"n.md", "src.pdf", "sketch.png"} {
		if _, err := os.Stat(filepath.Join(root, "b/c", f)); err != nil {
			t.Errorf("%s did not move: %v", f, err)
		}
		if _, err := os.Stat(filepath.Join(root, "a", f)); err == nil {
			t.Errorf("%s left behind", f)
		}
	}
}

func TestMoveCollisionMovesNothing(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a/n.md", []byte("note")))
	must(v.AddFile(g.ID, "src.pdf", strings.NewReader("pdf")))
	must(v.ImportFile("b/src.pdf", strings.NewReader("someone else's")))
	if err := v.Move(g.ID, "b"); !errors.Is(err, ErrExists) {
		t.Fatalf("want ErrExists, got %v", err)
	}
	for _, f := range []string{"a/n.md", "a/src.pdf", "b/src.pdf"} {
		if _, err := os.Stat(filepath.Join(root, f)); err != nil {
			t.Errorf("%s should be untouched: %v", f, err)
		}
	}
	if _, err := os.Stat(filepath.Join(root, "b/n.md")); err == nil {
		t.Error("note moved even though the move was refused")
	}
}

func TestIDsRecoverFromFolderAlone(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a/n.md", []byte("note")))
	must(v.AddFile(g.ID, "src.pdf", strings.NewReader("pdf")))
	want := must(v.List())
	v2, err := Open(root) // new process, only the folder survives
	if err != nil {
		t.Fatal(err)
	}
	if got := must(v2.List()); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v want %+v", got, want)
	}
}

func TestRescanRepairsSplitGroup(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a/n.md", []byte("note")))
	must(v.AddFile(g.ID, "src.pdf", strings.NewReader("pdf")))
	// Simulate a crash mid-move: the PDF moved, the record did not.
	if err := os.MkdirAll(filepath.Join(root, "b"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(root, "a/src.pdf"), filepath.Join(root, "b/src.pdf")); err != nil {
		t.Fatal(err)
	}
	v2, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := v2.Rescan(); err != nil {
		t.Fatal(err)
	}
	got := must(v2.Get(g.ID))
	if got.Files[0].Path != "b/src.pdf" || got.Note.Path != "a/n.md" {
		t.Fatalf("not repaired: %+v", got)
	}
}

func TestLeftoverTempFileIgnored(t *testing.T) {
	v, root := open(t)
	must(v.CreateNote("n.md", []byte("x")))
	// A crash during a write leaves temp files, in the folder and in .ids.
	for _, p := range []string{".styli-tmp-1", ".ids/.styli-tmp-2"} {
		if err := os.WriteFile(filepath.Join(root, p), []byte("half"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	v2, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	if gs := must(v2.List()); len(gs) != 1 {
		t.Fatalf("want 1 group, got %d", len(gs))
	}
}

func TestBadIDs(t *testing.T) {
	v, _ := open(t)
	for _, id := range []string{"", "../x", "abc", strings.Repeat("a", 26)} {
		if _, err := v.Get(id); !errors.Is(err, ErrNotFound) {
			t.Errorf("%q: want ErrNotFound, got %v", id, err)
		}
	}
	if _, err := v.AddFile("../x", "a.pdf", bytes.NewReader(nil)); !errors.Is(err, ErrNotFound) {
		t.Errorf("AddFile bad id: %v", err)
	}
}

func TestOpenFileServesOnlyTheGroupsFiles(t *testing.T) {
	v, root := open(t)
	g := must(v.CreateNote("a/n.md", []byte("note")))
	must(v.AddFile(g.ID, "one.pdf", strings.NewReader("%PDF-one")))
	must(v.AddFile(g.ID, "two.png", strings.NewReader("png")))
	f, p, err := v.OpenFile(g.ID, 1)
	if err != nil || p != "a/two.png" {
		t.Fatalf("%q %v", p, err)
	}
	b := make([]byte, 10)
	n, _ := f.Read(b)
	f.Close()
	if string(b[:n]) != "png" {
		t.Fatalf("read %q", b[:n])
	}
	for _, i := range []int{-1, 2, 99} { // the note itself is not a file entry
		if _, _, err := v.OpenFile(g.ID, i); !errors.Is(err, ErrNotFound) {
			t.Errorf("index %d: %v", i, err)
		}
	}
	if _, _, err := v.OpenFile("../x", 0); !errors.Is(err, ErrNotFound) {
		t.Errorf("bad id: %v", err)
	}
	os.Remove(filepath.Join(root, "a/one.pdf"))
	if _, _, err := v.OpenFile(g.ID, 0); !errors.Is(err, ErrNotFound) {
		t.Errorf("missing file: %v", err)
	}
}
