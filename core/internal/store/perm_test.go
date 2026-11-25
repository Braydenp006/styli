package store

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"styli/core/internal/vault"
)

func mode(t *testing.T, p string) os.FileMode {
	t.Helper()
	fi, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	return fi.Mode().Perm()
}

func TestIndexIsPrivateToThisUser(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permissions")
	}
	dir := filepath.Join(t.TempDir(), "cache", "styli", "abcd")
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	// Write something so the -wal and -shm files exist.
	if err := s.Upsert(vault.Group{ID: "a", Note: &vault.File{Path: "a.md", Version: "v"}}, []byte("private words"), time.Now()); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"index.db", "index.db-wal", "index.db-shm"} {
		if _, err := os.Stat(filepath.Join(dir, p)); err != nil {
			continue
		}
		if m := mode(t, filepath.Join(dir, p)); m != 0o600 {
			t.Errorf("%s is %o, want 600", p, m)
		}
	}
	if m := mode(t, dir); m != 0o700 {
		t.Errorf("index folder is %o, want 700", m)
	}
	s.Close()
}

func TestAnExistingReadableIndexIsTightened(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permissions")
	}
	dir := t.TempDir()
	s, _ := Open(dir)
	s.Close()
	os.Chmod(dir, 0o755)
	os.Chmod(filepath.Join(dir, "index.db"), 0o644)
	os.WriteFile(filepath.Join(dir, "index.db-wal"), nil, 0o644) // as an older build might have left it
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if m := mode(t, dir); m != 0o700 {
		t.Errorf("folder %o", m)
	}
	for _, p := range []string{"index.db", "index.db-wal"} {
		if m := mode(t, filepath.Join(dir, p)); m != 0o600 {
			t.Errorf("%s %o", p, m)
		}
	}
}

func TestMigrationBackupIsPrivate(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permissions")
	}
	dir := t.TempDir()
	db, path, _ := openDB(dir)
	db.Exec(`CREATE TABLE x (a)`)
	if err := backup(db, path, 1); err != nil {
		t.Fatal(err)
	}
	db.Close()
	if m := mode(t, path+".bak-v1"); m != 0o600 {
		t.Errorf("backup is %o", m)
	}
}
