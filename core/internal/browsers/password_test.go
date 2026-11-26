package browsers

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// The real cost, kept before init lowers it so the tests run quickly.
var defaultCost = HashCost

func init() { HashCost.Time, HashCost.MemoryKiB, HashCost.Threads = 1, 64, 1 }

const pw = "correct horse battery"

// newBrowser trusts a browser through a link and returns it and its secret.
func newBrowser(t *testing.T, l *List, name string) (Browser, string) {
	t.Helper()
	b, secret, err := l.Redeem(must3(l.NewLink(LinkLife)), name)
	if err != nil {
		t.Fatal(err)
	}
	return b, secret
}

func TestThePasswordSignsATrustedBrowserBackIn(t *testing.T) {
	root := t.TempDir()
	l, _ := clock(t, root)
	b, secret := newBrowser(t, l, "iPhone")
	if l.HasPassword() {
		t.Fatal("a new atrium has a password")
	}
	if err := l.SetPassword(b.ID, "", pw); err != nil {
		t.Fatal(err)
	}
	if kept, err := l.SignOut(b.ID); err != nil || !kept {
		t.Fatalf("sign out: kept %v, %v", kept, err)
	}
	if _, _, ok := l.Check(secret); ok {
		t.Fatal("still signed in after signing out")
	}
	if _, ok := l.Known(secret); !ok {
		t.Fatal("signing out forgot the browser")
	}
	if _, err := l.SignIn(secret, pw+"x"); !errors.Is(err, ErrBadPassword) {
		t.Fatalf("a wrong password: %v", err)
	}
	if got, err := l.SignIn(secret, pw); err != nil || got.ID != b.ID {
		t.Fatalf("the right password: %+v %v", got, err)
	}
	if _, _, ok := l.Check(secret); !ok {
		t.Fatal("not signed in after the right password")
	}

	// Kept on disk as a hash only, and still works after a restart.
	raw := string(must(os.ReadFile(filepath.Join(root, ".ids", "browsers.json"))))
	if strings.Contains(raw, pw) || !strings.Contains(raw, `"password": "$argon2id$v=19$m=64,t=1,p=1$`) {
		t.Fatalf("stored as:\n%s", raw)
	}
	l2, _ := clock(t, root)
	l2.SignOut(b.ID)
	if !l2.HasPassword() {
		t.Fatal("the password was lost on reopening")
	}
	if _, err := l2.SignIn(secret, pw); err != nil {
		t.Fatal(err)
	}
}

// A password is never a way in for a browser that isn't trusted.
func TestThePasswordNeverAddsABrowser(t *testing.T) {
	l, _ := clock(t, t.TempDir())
	b, secret := newBrowser(t, l, "iPhone")
	if _, err := l.SignIn(secret, pw); !errors.Is(err, ErrBadPassword) {
		t.Fatalf("with no password set: %v", err)
	}
	l.SetPassword(b.ID, "", pw)
	for name, s := range map[string]string{"no cookie": "", "an unknown cookie": secret + "x"} {
		if _, err := l.SignIn(s, pw); !errors.Is(err, ErrBadPassword) {
			t.Errorf("%s: %v", name, err)
		}
	}
	other, otherSecret := newBrowser(t, l, "Old laptop")
	l.Remove(other.ID)
	if _, err := l.SignIn(otherSecret, pw); !errors.Is(err, ErrBadPassword) {
		t.Fatalf("a removed browser: %v", err)
	}
	if l.Count() != 1 {
		t.Fatalf("%d browsers", l.Count())
	}
}

// A session ends after 30 days unused but the browser stays known for
// KnownLife, renewed whenever the session is.
func TestKnownOutlivesTheSession(t *testing.T) {
	l, now := clock(t, t.TempDir())
	b, secret := newBrowser(t, l, "iPhone")
	l.SetPassword(b.ID, "", pw)

	*now = now.Add(SessionLife)
	if _, _, ok := l.Check(secret); ok {
		t.Fatal("the session outlived its life")
	}
	got, err := l.SignIn(secret, pw)
	if err != nil || !got.KnownUntil.Equal(now.Add(KnownLife)) || !got.SessionExpires.Equal(now.Add(SessionLife)) {
		t.Fatalf("%+v %v", got, err)
	}

	*now = now.Add(RenewEvery)
	if b, renewed, _ := l.Check(secret); !renewed || !b.KnownUntil.Equal(now.Add(KnownLife)) {
		t.Fatalf("renewal did not extend KnownUntil: %+v", b)
	}

	*now = now.Add(KnownLife)
	if _, ok := l.Known(secret); ok {
		t.Fatal("known past KnownLife")
	}
	if _, err := l.SignIn(secret, pw); !errors.Is(err, ErrBadPassword) {
		t.Fatalf("past KnownLife: %v", err)
	}
}

func TestWrongPasswordsLockOneBrowserOut(t *testing.T) {
	l, now := clock(t, t.TempDir())
	b, secret := newBrowser(t, l, "iPhone")
	other, otherSecret := newBrowser(t, l, "Laptop")
	l.SetPassword(b.ID, "", pw)
	l.SignOut(b.ID)
	l.SignOut(other.ID)

	for i := range MaxTries {
		if _, err := l.SignIn(secret, "wrong"); !errors.Is(err, ErrBadPassword) {
			t.Fatalf("try %d: %v", i+1, err)
		}
	}
	if _, err := l.SignIn(secret, pw); !errors.Is(err, ErrLocked) {
		t.Fatalf("locked, with the right password: %v", err)
	}
	if err := l.SetPassword(b.ID, pw, "new password"); !errors.Is(err, ErrLocked) {
		t.Fatalf("locked, changing the password: %v", err)
	}
	if _, err := l.SignIn(otherSecret, pw); err != nil {
		t.Fatalf("another browser was locked too: %v", err)
	}

	*now = now.Add(LockFor)
	if _, err := l.SignIn(secret, pw); err != nil {
		t.Fatalf("after the lock: %v", err)
	}

	// A wrong current password when changing it counts too, and a success
	// starts the count again.
	for range MaxTries - 1 {
		l.SetPassword(b.ID, "wrong", "new password")
	}
	l.SignOut(b.ID)
	l.SignIn(secret, pw)
	for range MaxTries - 1 {
		l.SignIn(secret, "wrong")
	}
	if _, err := l.SignIn(secret, pw); err != nil {
		t.Fatalf("a success did not reset the count: %v", err)
	}
	if err := l.SetPassword(b.ID, "wrong", "new password"); !errors.Is(err, ErrBadPassword) {
		t.Fatal(err)
	}
}

func TestSettingChangingAndRemovingThePassword(t *testing.T) {
	l, _ := clock(t, t.TempDir())
	b, secret := newBrowser(t, l, "iPhone")
	for name, p := range map[string]string{"7 characters": "1234567", "257 bytes": strings.Repeat("a", 257), "not UTF-8": "abcdefg\xff"} {
		if err := l.SetPassword(b.ID, "", p); !errors.Is(err, ErrWeakPassword) {
			t.Errorf("%s: %v", name, err)
		}
	}
	if err := l.SetPassword(b.ID, "", "ëëëëëëëë"); err != nil { // 8 characters, 16 bytes
		t.Fatal(err)
	}
	if err := l.SetPassword(b.ID, "", pw); !errors.Is(err, ErrBadPassword) {
		t.Fatalf("changed without the current password: %v", err)
	}
	if err := l.SetPassword(b.ID, "ëëëëëëëë", pw); err != nil {
		t.Fatal(err)
	}
	if err := l.SetPassword(b.ID, "wrong", ""); !errors.Is(err, ErrBadPassword) || !l.HasPassword() {
		t.Fatalf("removed without the current password: %v", err)
	}
	if err := l.SetPassword("0123456789abcdef0123456789abcdef", pw, ""); !errors.Is(err, ErrNotFound) {
		t.Fatalf("an unknown browser: %v", err)
	}
	if err := l.SetPassword(b.ID, pw, ""); err != nil || l.HasPassword() {
		t.Fatalf("remove: %v", err)
	}

	// With no password, signing out removes the browser: it could not get back in.
	if kept, err := l.SignOut(b.ID); err != nil || kept || l.Count() != 0 {
		t.Fatalf("kept %v, %v, %d browsers", kept, err, l.Count())
	}
	if _, ok := l.Known(secret); ok {
		t.Fatal("still known")
	}
	if _, err := l.SignOut(b.ID); !errors.Is(err, ErrNotFound) {
		t.Fatal(err)
	}
}

func TestClearPasswordNeedsNoCurrentOne(t *testing.T) {
	root := t.TempDir()
	l, _ := clock(t, root)
	b, _ := newBrowser(t, l, "iPhone")
	l.SetPassword(b.ID, "", pw)
	if err := l.ClearPassword(); err != nil || l.HasPassword() || must(Open(root)).HasPassword() {
		t.Fatalf("not cleared: %v", err)
	}
}

// A list written before the password has no known_until: such a browser is
// known for as long as its session lasts, and stays known when signed out.
func TestAListFromBeforeThePassword(t *testing.T) {
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, ".ids"), 0o700)
	secret, hash := must2(newSecret())
	id := strings.Repeat("a", 32)
	os.WriteFile(filepath.Join(root, ".ids", "browsers.json"), []byte(`{"version":1,"browsers":[{"id":"`+id+`","name":"Old","session_hash":"`+hash+`","session_expires":"2026-12-20T12:00:00Z"}]}`), 0o600)
	l, now := clock(t, root)
	if _, ok := l.Known(secret); !ok { // Known, not Check: Check would renew it
		t.Fatal("an old browser is not known")
	}
	l.SetPassword(id, "", pw)
	l.SignOut(id)
	*now = now.Add(60 * 24 * time.Hour) // past the old session's end
	if _, err := l.SignIn(secret, pw); err != nil {
		t.Fatalf("an old browser could not sign back in: %v", err)
	}
}

func TestADamagedPasswordHashIsAnError(t *testing.T) {
	salt, key := "AAAAAAAAAAAAAAAAAAAAAA", strings.Repeat("A", 43)
	for name, h := range map[string]string{
		"junk":         "junk",
		"argon2i":      "$argon2i$v=19$m=64,t=1,p=1$" + salt + "$" + key,
		"old version":  "$argon2id$v=16$m=64,t=1,p=1$" + salt + "$" + key,
		"huge memory":  "$argon2id$v=19$m=99999999,t=1,p=1$" + salt + "$" + key,
		"zero passes":  "$argon2id$v=19$m=64,t=0,p=1$" + salt + "$" + key,
		"bad salt":     "$argon2id$v=19$m=64,t=1,p=1$!!$" + key,
		"short key":    "$argon2id$v=19$m=64,t=1,p=1$" + salt + "$AAAA",
		"extra fields": "$argon2id$v=19$m=64,t=1,p=1$" + salt + "$" + key + "$x",
	} {
		root := t.TempDir()
		os.MkdirAll(filepath.Join(root, ".ids"), 0o700)
		os.WriteFile(filepath.Join(root, ".ids", "browsers.json"), []byte(`{"version":1,"browsers":[],"password":"`+h+`"}`), 0o600)
		if _, err := Open(root); err == nil {
			t.Errorf("%s: opened", name)
		}
	}
}

// The real cost is the one in the design: 64 MiB, 3 passes, 4 threads.
func TestTheRealHashCost(t *testing.T) {
	if defaultCost.Time != 3 || defaultCost.MemoryKiB != 64*1024 || defaultCost.Threads != 4 {
		t.Fatalf("%+v", defaultCost)
	}
	was := HashCost
	HashCost = defaultCost
	defer func() { HashCost = was }()
	h := must(hashPassword(pw))
	if !strings.HasPrefix(h, "$argon2id$v=19$m=65536,t=3,p=4$") || !verify(h, pw) || verify(h, pw+" ") {
		t.Fatal(h)
	}
}

// Many sign-ins at once, with a password change in the middle: no races, and
// each one either works or gets the one answer.
func TestSignInsAtOnce(t *testing.T) {
	l, _ := clock(t, t.TempDir())
	b, _ := newBrowser(t, l, "Laptop")
	l.SetPassword(b.ID, "", pw)
	var secrets []string
	for range 8 {
		_, s := newBrowser(t, l, "Phone")
		secrets = append(secrets, s)
	}
	var wg sync.WaitGroup
	var odd atomic.Int32
	for _, s := range secrets {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := l.SignIn(s, pw); err != nil && !errors.Is(err, ErrBadPassword) {
				odd.Add(1)
			}
		}()
	}
	wg.Add(1)
	go func() { defer wg.Done(); l.SetPassword(b.ID, pw, "another password") }()
	wg.Wait()
	if odd.Load() != 0 {
		t.Fatalf("%d odd answers", odd.Load())
	}
	if _, err := l.SignIn(secrets[0], "another password"); err != nil {
		t.Fatal(err)
	}
}
