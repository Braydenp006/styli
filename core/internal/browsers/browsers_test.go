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

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

// clock is a list whose time the test moves by hand.
func clock(t *testing.T, root string) (*List, *time.Time) {
	t.Helper()
	l := must(Open(root))
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	l.now = func() time.Time { return now }
	return l, &now
}

func TestALinkWorksOnceAndMakesATrustedBrowser(t *testing.T) {
	root := t.TempDir()
	l, _ := clock(t, root)
	secret, _ := must2(l.NewLink(LinkLife))
	b, session, err := l.Redeem(secret, "  iPhone Safari ")
	if err != nil || b.Name != "iPhone Safari" || session == "" || l.Count() != 1 {
		t.Fatalf("%+v %q %v", b, session, err)
	}
	if _, _, err := l.Redeem(secret, "Again"); !errors.Is(err, ErrBadLink) {
		t.Fatalf("a used link worked again: %v", err)
	}
	if _, _, err := l.Redeem("not a link", "x"); !errors.Is(err, ErrBadLink) {
		t.Fatalf("an unknown link: %v", err)
	}
	got, _, ok := l.Check(session)
	if !ok || got.ID != b.ID {
		t.Fatalf("session not accepted: %+v %v", got, ok)
	}
	if _, _, ok := l.Check(session + "x"); ok {
		t.Fatal("a wrong session was accepted")
	}
	if _, _, ok := l.Check(""); ok {
		t.Fatal("an empty session was accepted")
	}

	// Kept on disk, without the secrets.
	raw := string(must(os.ReadFile(filepath.Join(root, ".ids", "browsers.json"))))
	if strings.Contains(raw, session) || strings.Contains(raw, secret) {
		t.Fatal("a secret was written to disk")
	}
	l2 := must(Open(root))
	if _, _, ok := l2.Check(session); !ok || l2.Count() != 1 {
		t.Fatal("the browser was not kept across a restart")
	}
	// Links are not: a restart needs a new one.
	secret2, _ := must2(l.NewLink(LinkLife))
	if _, _, err := must(Open(root)).Redeem(secret2, "x"); !errors.Is(err, ErrBadLink) {
		t.Fatalf("a link outlived a restart: %v", err)
	}
}

func TestLinksExpire(t *testing.T) {
	l, now := clock(t, t.TempDir())
	secret, expires := must2(l.NewLink(LinkLife))
	if !expires.Equal(now.Add(LinkLife)) {
		t.Fatalf("expires %v", expires)
	}
	*now = now.Add(LinkLife)
	if _, _, err := l.Redeem(secret, "Late"); !errors.Is(err, ErrBadLink) {
		t.Fatalf("an expired link worked: %v", err)
	}
	if l.Count() != 0 {
		t.Fatal("an expired link made a browser")
	}
}

func TestOnlySoManyLinksWait(t *testing.T) {
	l, _ := clock(t, t.TempDir())
	first, _ := must2(l.NewLink(LinkLife))
	for range maxLinks {
		must2(l.NewLink(LinkLife))
	}
	if _, _, err := l.Redeem(first, "x"); !errors.Is(err, ErrBadLink) {
		t.Fatalf("the oldest link was not dropped: %v", err)
	}
}

// Sessions end after SessionLife unless used; use renews them, at most once a
// day, and says so (so the cookie is sent again).
func TestSessionsEndUnlessUsed(t *testing.T) {
	root := t.TempDir()
	l, now := clock(t, root)
	_, session, _ := l.Redeem(must3(l.NewLink(LinkLife)), "Laptop")

	*now = now.Add(time.Hour)
	if _, renewed, ok := l.Check(session); !ok || renewed {
		t.Fatalf("an hour on: ok %v, renewed %v", ok, renewed)
	}
	*now = now.Add(RenewEvery)
	b, renewed, ok := l.Check(session)
	if !ok || !renewed || !b.SessionExpires.Equal(now.Add(SessionLife)) || !b.LastSeen.Equal(*now) {
		t.Fatalf("a day on: %+v ok %v renewed %v", b, ok, renewed)
	}
	if b2, _, _ := must(Open(root)).Check(session); !b2.SessionExpires.Equal(b.SessionExpires) {
		t.Fatal("the renewal was not saved")
	}

	*now = now.Add(SessionLife)
	if _, _, ok := l.Check(session); ok {
		t.Fatal("a session outlived its life unused")
	}
}

func TestRemoveEndsTheSessionAtOnce(t *testing.T) {
	root := t.TempDir()
	l, _ := clock(t, root)
	b, session, _ := l.Redeem(must3(l.NewLink(LinkLife)), "Old phone")
	other, otherSession, _ := l.Redeem(must3(l.NewLink(LinkLife)), "Laptop")
	if err := l.Remove(b.ID); err != nil {
		t.Fatal(err)
	}
	if _, _, ok := l.Check(session); ok {
		t.Fatal("a removed browser still works")
	}
	if got, _, ok := l.Check(otherSession); !ok || got.ID != other.ID {
		t.Fatal("removing one browser affected another")
	}
	if err := l.Remove(b.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("removing twice: %v", err)
	}
	if list := must(Open(root)).List(); len(list) != 1 || list[0].ID != other.ID || list[0].SessionHash != "" {
		t.Fatalf("after reopen: %+v", list)
	}
}

func TestNames(t *testing.T) {
	l, _ := clock(t, t.TempDir())
	for _, bad := range []string{"", "   ", strings.Repeat("a", 61), "tab\there", "bad\xffutf8"} {
		secret := must3(l.NewLink(LinkLife))
		if _, _, err := l.Redeem(secret, bad); !errors.Is(err, ErrBadName) {
			t.Errorf("%q: %v", bad, err)
		}
		// A refused name does not use the link up.
		if _, _, err := l.Redeem(secret, "Fine"); err != nil {
			t.Errorf("after %q the link was gone: %v", bad, err)
		}
	}
}

// Two browsers opening the same link at once: exactly one gets in.
func TestALinkRacedByManyBrowsersLetsOneIn(t *testing.T) {
	l := must(Open(t.TempDir()))
	secret := must3(l.NewLink(LinkLife))
	var wins atomic.Int32
	var wg sync.WaitGroup
	for range 50 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, _, err := l.Redeem(secret, "Racer"); err == nil {
				wins.Add(1)
			}
		}()
	}
	wg.Wait()
	if wins.Load() != 1 || l.Count() != 1 {
		t.Fatalf("%d got in, %d browsers", wins.Load(), l.Count())
	}
}

func TestADamagedFileIsAnErrorNotAnEmptyList(t *testing.T) {
	for name, text := range map[string]string{
		"not JSON":    "{",
		"newer build": `{"version":99,"browsers":[]}`,
		"bad ID":      `{"version":1,"browsers":[{"id":"x","session_hash":"` + strings.Repeat("a", 64) + `"}]}`,
		"no hash":     `{"version":1,"browsers":[{"id":"` + strings.Repeat("a", 32) + `"}]}`,
	} {
		root := t.TempDir()
		os.MkdirAll(filepath.Join(root, ".ids"), 0o700)
		os.WriteFile(filepath.Join(root, ".ids", "browsers.json"), []byte(text), 0o600)
		if _, err := Open(root); err == nil {
			t.Errorf("%s: opened", name)
		}
	}
}

func must2[A, B any](a A, b B, err error) (A, B) {
	if err != nil {
		panic(err)
	}
	return a, b
}

func must3[A, B any](a A, _ B, err error) A {
	if err != nil {
		panic(err)
	}
	return a
}
