package pairing

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"filippo.io/cpace"

	"styli/core/internal/devices"
)

var (
	atriumID = strings.Repeat("a", 64)
	devID    = strings.Repeat("d", 64)
	exporter = []byte(strings.Repeat("x", ExporterLen))
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func newTestAtrium(t *testing.T) (*Atrium, *devices.List, *clock) {
	t.Helper()
	root := t.TempDir()
	os.Mkdir(filepath.Join(root, ".ids"), 0o700)
	devs, err := devices.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	c := &clock{time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	a := NewAtrium(atriumID, devs)
	a.now = c.now
	return a, devs, c
}

// device plays the device's part of the PAKE with the given words.
func device(t *testing.T, a *Atrium, words string) (atriumProof, deviceProof []byte, err error) {
	t.Helper()
	msgA, st, err := cpace.Start(words, contextInfo(devID, atriumID, exporter))
	if err != nil {
		t.Fatal(err)
	}
	msgB, ap, err := a.Start(devID, "Laptop", exporter, msgA)
	if err != nil {
		return nil, nil, err
	}
	key, err := st.Finish(msgB)
	if err != nil {
		t.Fatal(err)
	}
	return ap, proof(key, "device"), nil
}

func TestListsAreSound(t *testing.T) {
	if len(words) < 256 {
		t.Fatalf("only %d words", len(words))
	}
	seen := map[string]bool{}
	ok := regexp.MustCompile(`^[a-z]{2,12}$`)
	for _, w := range words {
		if !ok.MatchString(w) || seen[w] {
			t.Errorf("bad or repeated word %q", w)
		}
		seen[w] = true
	}
	if len(emoji) != 64 {
		t.Fatalf("%d emoji, need 64 (6 bits each)", len(emoji))
	}
	chars, names := map[string]bool{}, map[string]bool{}
	for _, e := range emoji {
		if chars[e.Char] || names[e.Name] || e.Name == "" {
			t.Errorf("repeated or unnamed emoji %q %q", e.Char, e.Name)
		}
		chars[e.Char], names[e.Name] = true, true
		if strings.ContainsRune(e.Char, '️') || len([]rune(e.Char)) != 1 {
			t.Errorf("%q is not a single code point", e.Char)
		}
	}
}

func TestNormalizeWords(t *testing.T) {
	for in, want := range map[string]string{
		"amber anchor anvil apple":        "amber-anchor-anvil-apple",
		"  Amber-ANCHOR, anvil.  apple\n": "amber-anchor-anvil-apple",
		"amber\tanchor  anvil   apple":    "amber-anchor-anvil-apple",
	} {
		if got, err := NormalizeWords(in); err != nil || got != want {
			t.Errorf("%q: %q %v", in, got, err)
		}
	}
	for _, in := range []string{"", "amber anchor anvil", "amber anchor anvil apple arch", "amber anchor anvil appel"} {
		if _, err := NormalizeWords(in); !errors.Is(err, ErrBadWords) {
			t.Errorf("%q accepted", in)
		}
	}
}

func TestNewWordsAreFromTheList(t *testing.T) {
	seen := map[string]bool{}
	for range 50 {
		w := newWords()
		if _, err := NormalizeWords(w); err != nil {
			t.Fatalf("%q: %v", w, err)
		}
		seen[w] = true
	}
	if len(seen) < 49 {
		t.Fatal("words repeat: not random")
	}
}

func TestPairingWithTheRightWords(t *testing.T) {
	a, devs, _ := newTestAtrium(t)
	st := a.Open()
	if st.Phase != Waiting || st.Words == "" {
		t.Fatalf("open: %+v", st)
	}
	ap, dp, err := device(t, a, st.Words)
	if err != nil {
		t.Fatal(err)
	}
	if string(ap) == string(dp) {
		t.Fatal("the two proofs are the same; one side could echo the other")
	}
	row, err := a.Confirm(devID, dp)
	if err != nil || len(row) != EmojiCount {
		t.Fatalf("confirm: %v", err)
	}
	if got := a.Status(); got.Phase != Comparing || got.Words != "" || len(got.Emoji) != EmojiCount {
		t.Fatalf("status leaks words or lacks emoji: %+v", got)
	}
	// Nothing is saved until both sides approve.
	a.Approve(devID)
	if devs.Trusted(devID) {
		t.Fatal("paired after one approval")
	}
	if st, err := a.Approve(""); err != nil || st.Phase != Paired {
		t.Fatalf("approve: %+v %v", st, err)
	}
	if !devs.Trusted(devID) || devs.List()[0].Name != "Laptop" {
		t.Fatal("device not saved")
	}
}

func TestWrongWordsUseUpTheTry(t *testing.T) {
	a, devs, _ := newTestAtrium(t)
	st := a.Open()
	other := "amber-anchor-anvil-apple"
	if other == st.Words {
		other = "arch-arena-armor-arrow"
	}
	_, dp, err := device(t, a, other)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.Confirm(devID, dp); !errors.Is(err, ErrWrongWords) {
		t.Fatalf("wrong words: %v", err)
	}
	if got := a.Status(); got.Phase != Failed {
		t.Fatalf("phase %s", got.Phase)
	}
	// The right words now: too late.
	if _, _, err := device(t, a, st.Words); !errors.Is(err, ErrNoPairing) {
		t.Fatalf("second try: %v", err)
	}
	if len(devs.List()) != 0 {
		t.Fatal("something was saved")
	}
}

func TestPairingTimesOut(t *testing.T) {
	a, _, c := newTestAtrium(t)
	st := a.Open()
	c.t = c.t.Add(Window)
	if a.Admits(devID) {
		t.Fatal("expired window still admits strangers")
	}
	if _, _, err := device(t, a, st.Words); !errors.Is(err, ErrNoPairing) {
		t.Fatalf("after 5 minutes: %v", err)
	}
	if a.Status().Phase != Expired {
		t.Fatal("not expired")
	}

	// The emoji wait their own 5 minutes, then expire too.
	st = a.Open()
	_, dp, _ := device(t, a, st.Words)
	a.Confirm(devID, dp)
	c.t = c.t.Add(Window - time.Second)
	if a.Status().Phase != Comparing {
		t.Fatal("expired early")
	}
	c.t = c.t.Add(time.Second)
	if _, err := a.Approve(""); !errors.Is(err, ErrNoPairing) || a.Status().Phase != Expired {
		t.Fatal("approved after the time ran out")
	}
}

func TestCancelAndReopen(t *testing.T) {
	a, devs, _ := newTestAtrium(t)
	first := a.Open()
	second := a.Open() // a new pairing ends the last
	if first.Words == second.Words {
		t.Fatal("same words twice")
	}
	if _, _, err := device(t, a, first.Words); err != nil {
		t.Fatal(err) // the attempt itself is accepted...
	}
	if a.Status().Phase != Checking {
		t.Fatal("not checking")
	}
	a.Cancel("")
	if a.Status().Phase != Cancelled || a.Admits(devID) {
		t.Fatal("not cancelled")
	}
	if _, err := a.Approve(devID); !errors.Is(err, ErrNoPairing) || len(devs.List()) != 0 {
		t.Fatal("approved a cancelled pairing")
	}
	// Another device cannot cancel or watch someone else's pairing.
	st := a.Open()
	device(t, a, st.Words)
	a.Cancel(strings.Repeat("e", 64))
	if a.Status().Phase != Checking {
		t.Fatal("cancelled by a stranger")
	}
	if _, err := a.DeviceStatus(strings.Repeat("e", 64)); !errors.Is(err, ErrNoPairing) {
		t.Fatal("stranger sees the pairing")
	}
}

func TestKnownAndRevokedKeysDoNotUseTheWords(t *testing.T) {
	a, devs, _ := newTestAtrium(t)
	devs.Add(devID, "Old laptop")
	st := a.Open()
	if _, _, err := device(t, a, st.Words); !errors.Is(err, ErrKnownKey) {
		t.Fatalf("known: %v", err)
	}
	devs.Revoke(devID)
	if _, _, err := device(t, a, st.Words); !errors.Is(err, ErrRevokedKey) {
		t.Fatalf("revoked: %v", err)
	}
	if a.Status().Phase != Waiting {
		t.Fatal("the words were used up by a refused key")
	}
}

func TestAdmits(t *testing.T) {
	a, _, _ := newTestAtrium(t)
	if a.Admits(devID) {
		t.Fatal("admits with no pairing open")
	}
	st := a.Open()
	if !a.Admits(devID) || !a.Admits(strings.Repeat("e", 64)) {
		t.Fatal("waiting pairing must admit a new key")
	}
	device(t, a, st.Words)
	if !a.Admits(devID) || a.Admits(strings.Repeat("e", 64)) {
		t.Fatal("after the words are tried, only that device")
	}
}

func TestEmojiDependOnConnectionAndKeys(t *testing.T) {
	base := emojiFor(exporter, atriumID, devID)
	if !sameEmoji(base, emojiFor(exporter, atriumID, devID)) {
		t.Fatal("not repeatable")
	}
	other := []byte(strings.Repeat("y", ExporterLen))
	for name, row := range map[string][]Symbol{
		"connection": emojiFor(other, atriumID, devID),
		"atrium":     emojiFor(exporter, strings.Repeat("b", 64), devID),
		"device":     emojiFor(exporter, atriumID, strings.Repeat("e", 64)),
	} {
		if sameEmoji(base, row) {
			t.Errorf("emoji ignore the %s", name)
		}
	}
}
