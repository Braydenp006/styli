package vault

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// umask works out the process's umask by making a folder that asks for everything.
func umask(t *testing.T) os.FileMode {
	t.Helper()
	p := filepath.Join(t.TempDir(), "probe")
	if err := os.Mkdir(p, 0o777); err != nil {
		t.Fatal(err)
	}
	fi, _ := os.Stat(p)
	return 0o777 &^ fi.Mode().Perm()
}

func perm(t *testing.T, p string) os.FileMode {
	t.Helper()
	fi, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	return fi.Mode().Perm()
}

func TestIdsFolderIsPrivateAndTightened(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permissions")
	}
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, ".ids"), 0o755) // as an older build left it
	if _, err := Open(root); err != nil {
		t.Fatal(err)
	}
	if m := perm(t, filepath.Join(root, ".ids")); m != 0o700 {
		t.Fatalf(".ids is %o", m)
	}
}

func TestNewFoldersCopyTheVaultFoldersPermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permissions")
	}
	mask := umask(t)
	for _, rootMode := range []os.FileMode{0o700, 0o750, 0o755} {
		root := filepath.Join(t.TempDir(), "vault")
		os.Mkdir(root, rootMode)
		os.Chmod(root, rootMode)
		v := must(Open(root))
		want := rootMode &^ mask

		g := must(v.CreateNote("a/b/n.md", []byte("x")))
		must(v.AddFile(g.ID, "f.pdf", strings.NewReader("pdf")))
		if err := v.Move(g.ID, "c/d"); err != nil {
			t.Fatal(err)
		}
		v.Trash(g.ID)
		if err := v.Restore(g.ID, "e/f"); err != nil {
			t.Fatal(err)
		}
		for _, d := range []string{"a", "a/b", "c", "c/d", "e", "e/f"} {
			if m := perm(t, filepath.Join(root, d)); m != want {
				t.Errorf("vault %o: folder %s is %o, want %o", rootMode, d, m, want)
			}
		}
		// Notes the app writes stay private either way.
		if m := perm(t, filepath.Join(root, "e/f/n.md")); m != 0o600 {
			t.Errorf("note is %o", m)
		}
	}
}

func TestHugeNotesAreKeptButNotReadIntoMemory(t *testing.T) {
	old := MaxNoteSize
	MaxNoteSize = 1024
	defer func() { MaxNoteSize = old }()
	v, root := open(t)
	write(t, root, "big.md", strings.Repeat("x", 2048))
	c := rescan(t, v) // adopted without reading it whole, and without error
	if len(c) != 1 || c[0].Kind != Added {
		t.Fatalf("%+v", c)
	}
	if _, _, err := v.ReadNote(c[0].ID); err != ErrTooLarge {
		t.Fatalf("got %v", err)
	}
	if n := len(must(v.History(c[0].ID))); n != 0 {
		t.Fatalf("kept %d versions of a huge note", n)
	}
	write(t, root, "big.md", strings.Repeat("y", 4096)) // edited outside: still tracked
	if c := rescan(t, v); len(c) != 1 || c[0].Kind != Modified {
		t.Fatalf("%+v", c)
	}
}
