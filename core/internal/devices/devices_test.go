package devices

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var (
	idA = strings.Repeat("a", 64)
	idB = strings.Repeat("b", 64)
)

func open(t *testing.T) (*List, string) {
	t.Helper()
	root := t.TempDir()
	os.Mkdir(filepath.Join(root, ".ids"), 0o700)
	l, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	return l, root
}

func TestPairRevokeAndReopen(t *testing.T) {
	l, root := open(t)
	if l.Trusted(idA) {
		t.Fatal("unknown device trusted")
	}
	if err := l.Add(idA, "Laptop"); err != nil {
		t.Fatal(err)
	}
	if err := l.Add(idB, "Tablet"); err != nil {
		t.Fatal(err)
	}
	if !l.Trusted(idA) || !l.Trusted(idB) {
		t.Fatal("paired device not trusted")
	}
	var hooked string
	l.OnRevoke = func(id string) { hooked = id }
	if err := l.Revoke(idA); err != nil || hooked != idA {
		t.Fatalf("revoke: %v, hook %q", err, hooked)
	}
	if l.Trusted(idA) {
		t.Fatal("revoked device still trusted")
	}
	// A revoked key cannot simply be paired again.
	if err := l.Add(idA, "Laptop"); !errors.Is(err, ErrExists) {
		t.Fatalf("re-add revoked: %v", err)
	}

	l2, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	if l2.Trusted(idA) || !l2.Trusted(idB) {
		t.Fatal("trust changed on reopen")
	}
	ds := l2.List()
	if len(ds) != 2 || ds[0].Name != "Laptop" || ds[0].RevokedAt == nil || ds[1].Name != "Tablet" {
		t.Fatalf("list: %+v", ds)
	}
	if fi, _ := os.Stat(filepath.Join(root, ".ids", "devices.json")); fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode %v", fi.Mode())
	}
}

func TestBadInput(t *testing.T) {
	l, _ := open(t)
	if err := l.Add("short", "x"); !errors.Is(err, ErrBadID) {
		t.Fatalf("bad id: %v", err)
	}
	if err := l.Revoke(idA); !errors.Is(err, ErrNotFound) {
		t.Fatalf("revoke unknown: %v", err)
	}
}

// A damaged list must never read as "no devices", which would lock everyone
// out without saying why, nor as anything else.
func TestDamagedListIsAnError(t *testing.T) {
	for name, content := range map[string]string{
		"garbage": "{not json",
		"newer":   `{"version":99,"devices":[]}`,
		"bad id":  `{"version":1,"devices":[{"id":"nope","name":"x"}]}`,
	} {
		t.Run(name, func(t *testing.T) {
			_, root := open(t)
			os.WriteFile(filepath.Join(root, ".ids", "devices.json"), []byte(content), 0o600)
			if _, err := Open(root); err == nil {
				t.Fatal("damaged list opened")
			}
		})
	}
}

func TestLastSeenIsWrittenNowAndThen(t *testing.T) {
	l, root := open(t)
	l.Add(idA, "Laptop")
	now := time.Now()
	l.Seen(idA, now)
	l.Seen(idA, now.Add(time.Minute)) // kept in memory only
	if got := l.List()[0].LastSeen; !got.Equal(now.Add(time.Minute).UTC()) {
		t.Fatalf("in memory: %v", got)
	}
	l2, _ := Open(root)
	if got := l2.List()[0].LastSeen; !got.Equal(now.UTC()) {
		t.Fatalf("on disk: %v, want %v", got, now.UTC())
	}
	l.Seen(idB, now) // unknown device: ignored
}
