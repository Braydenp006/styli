package devicekey

import (
	"os"
	"path/filepath"
	"testing"
)

func TestKeyIsMadeOnceAndKept(t *testing.T) {
	dir := t.TempDir()
	a, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(a.ID) != 64 {
		t.Fatalf("ID %q", a.ID)
	}
	fi, err := os.Stat(filepath.Join(dir, FileName))
	if err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("key file: %v %v", fi.Mode(), err)
	}
	b, err := Load(dir) // next start
	if err != nil || b.ID != a.ID {
		t.Fatalf("restart: %v, ID %s then %s", err, a.ID, b.ID)
	}
	// The certificate is made fresh each start, but the ID names the key.
	if string(b.Cert.Certificate[0]) == string(a.Cert.Certificate[0]) {
		t.Fatal("certificate was expected to be new")
	}
	if Fingerprint(b.Cert.Leaf) != a.ID {
		t.Fatal("fingerprint changed with the certificate")
	}
	other, _ := Load(t.TempDir())
	if other.ID == a.ID {
		t.Fatal("two devices share an ID")
	}
}

func TestLoosenedKeyFileIsTightened(t *testing.T) {
	dir := t.TempDir()
	Load(dir)
	p := filepath.Join(dir, FileName)
	os.Chmod(p, 0o644)
	if _, err := Load(dir); err != nil {
		t.Fatal(err)
	}
	if fi, _ := os.Stat(p); fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode %v", fi.Mode())
	}
}

func TestDamagedKeyIsNeverReplaced(t *testing.T) {
	for name, content := range map[string]string{
		"garbage":   "not a key",
		"wrong pem": "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n",
		"empty":     "",
	} {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			p := filepath.Join(dir, FileName)
			os.WriteFile(p, []byte(content), 0o600)
			if _, err := Load(dir); err == nil {
				t.Fatal("damaged key accepted")
			}
			if b, _ := os.ReadFile(p); string(b) != content {
				t.Fatal("damaged key was overwritten")
			}
		})
	}
}
