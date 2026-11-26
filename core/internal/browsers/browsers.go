// Package browsers is the atrium's list of trusted browsers: who may use its
// web UI. A browser joins with a one-time link made on a browser that is
// already trusted (or printed by the atrium when none is), and then carries a
// session cookie.
//
// The list lives in the vault (.ids/browsers.json), like the device list, so
// it travels with backups. Only hashes of secrets are stored: a copy of the
// file, or of a backup, cannot be used to sign in. Links live in memory only:
// they last minutes, and a restart simply needs a new one.
package browsers

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"styli/core/internal/atomicfile"
)

const fileVersion = 1

// How long things last. Variables so tests can shorten them.
var (
	SessionLife   = 30 * 24 * time.Hour // a session is renewed when used, at most once a day
	RenewEvery    = 24 * time.Hour
	LinkLife      = 10 * time.Minute // a link made on a trusted browser
	SetupLinkLife = 30 * time.Minute // the link the atrium prints when no browser is trusted
	maxLinks      = 10               // links waiting at once; making another drops the oldest
)

var (
	// ErrBadLink is the one answer for a link that is unknown, used or
	// expired, so trying links tells an attacker nothing.
	ErrBadLink  = errors.New("browsers: this link has expired or was already used")
	ErrNotFound = errors.New("browsers: no such browser")
	ErrBadName  = errors.New("browsers: a browser's name must be 1 to 60 characters, with no control characters")
)

var idRe = regexp.MustCompile(`^[0-9a-f]{32}$`)

// Browser is one trusted browser.
type Browser struct {
	ID       string    `json:"id"`
	Name     string    `json:"name"`
	AddedAt  time.Time `json:"added_at"`
	LastSeen time.Time `json:"last_seen"` // updated when the session is renewed, so to the day
	// SessionHash is the SHA-256 of the session cookie's secret, never the
	// secret itself. SessionExpires is when the session ends unless renewed.
	// The same secret identifies the browser for KnownUntil (its cookie's life),
	// so once the session ends it can sign back in with the atrium's password.
	SessionHash    string    `json:"session_hash"`
	SessionExpires time.Time `json:"session_expires"`
	KnownUntil     time.Time `json:"known_until,omitzero"`
}

type file struct {
	Version  int       `json:"version"`
	Browsers []Browser `json:"browsers"`
	Password string    `json:"password,omitempty"` // the atrium's password, as an Argon2id hash
}

type link struct {
	hash    string
	expires time.Time
}

// List is the trusted browsers and the links waiting to be used. Safe for
// concurrent use.
type List struct {
	path  string
	mu    sync.Mutex
	bs    map[string]*Browser
	links []link
	now   func() time.Time

	password string            // Argon2id hash, "" for none
	tries    map[string]*tries // wrong passwords per browser ID
}

// Open reads the list from the vault. A missing file is an empty list; a
// damaged one, or one from a newer build, is an error, never an empty list.
func Open(vaultRoot string) (*List, error) {
	l := &List{path: filepath.Join(vaultRoot, ".ids", "browsers.json"), bs: map[string]*Browser{}, now: time.Now, tries: map[string]*tries{}}
	b, err := os.ReadFile(l.path)
	if errors.Is(err, fs.ErrNotExist) {
		return l, nil
	}
	if err != nil {
		return nil, err
	}
	var f file
	if err := json.Unmarshal(b, &f); err != nil {
		return nil, fmt.Errorf("browsers: %s is damaged: %w", l.path, err)
	}
	if f.Version > fileVersion {
		return nil, fmt.Errorf("browsers: %s was written by a newer version of Styli; update this one", l.path)
	}
	for i := range f.Browsers {
		br := f.Browsers[i]
		if !idRe.MatchString(br.ID) || len(br.SessionHash) != 64 {
			return nil, fmt.Errorf("browsers: %s is damaged: bad entry %q", l.path, br.ID)
		}
		l.bs[br.ID] = &br
	}
	if f.Password != "" {
		if _, err := parseHash(f.Password); err != nil {
			return nil, fmt.Errorf("browsers: %s is damaged: %w", l.path, err)
		}
		l.password = f.Password
	}
	return l, nil
}

// Count is how many browsers are trusted.
func (l *List) Count() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.bs)
}

// NewLink makes a one-time link secret that lasts life. Only the secret's
// hash is kept.
func (l *List) NewLink(life time.Duration) (secret string, expires time.Time, err error) {
	secret, hash, err := newSecret()
	if err != nil {
		return "", time.Time{}, err
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.dropExpired(now)
	if len(l.links) >= maxLinks {
		l.links = l.links[1:]
	}
	expires = now.Add(life)
	l.links = append(l.links, link{hash, expires})
	return secret, expires, nil
}

// Redeem uses a link: it is gone afterwards, whatever happens next. A new
// browser called name is trusted, and its session secret is returned for the
// cookie. Of two calls with the same link, exactly one succeeds.
func (l *List) Redeem(secret, name string) (Browser, string, error) {
	name, err := cleanName(name)
	if err != nil {
		return Browser{}, "", err
	}
	hash := hashOf(secret)
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.dropExpired(now)
	found := -1
	for i, k := range l.links {
		if subtle.ConstantTimeCompare([]byte(k.hash), []byte(hash)) == 1 {
			found = i
		}
	}
	if found < 0 {
		return Browser{}, "", ErrBadLink
	}
	l.links = append(l.links[:found], l.links[found+1:]...)

	id, err := newID()
	if err != nil {
		return Browser{}, "", err
	}
	session, sessionHash, err := newSecret()
	if err != nil {
		return Browser{}, "", err
	}
	br := &Browser{ID: id, Name: name, AddedAt: now.UTC(), LastSeen: now.UTC(), SessionHash: sessionHash, SessionExpires: now.Add(SessionLife).UTC(), KnownUntil: now.Add(KnownLife).UTC()}
	l.bs[id] = br
	if err := l.save(); err != nil {
		delete(l.bs, id)
		return Browser{}, "", err
	}
	return *br, session, nil
}

// Check finds the browser whose session secret this is. ok is false for an
// unknown or ended session. renewed says the session was extended, so the
// caller sends the cookie again with its new lifetime.
func (l *List) Check(session string) (b Browser, renewed, ok bool) {
	if session == "" {
		return Browser{}, false, false
	}
	hash := hashOf(session)
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	var br *Browser
	for _, x := range l.bs {
		if subtle.ConstantTimeCompare([]byte(x.SessionHash), []byte(hash)) == 1 {
			br = x
		}
	}
	if br == nil || !now.Before(br.SessionExpires) {
		return Browser{}, false, false
	}
	if br.SessionExpires.Sub(now) <= SessionLife-RenewEvery {
		was := *br
		br.SessionExpires, br.KnownUntil, br.LastSeen = now.Add(SessionLife).UTC(), now.Add(KnownLife).UTC(), now.UTC()
		if l.save() != nil { // keep the old times; the next request tries again
			*br = was
		} else {
			renewed = true
		}
	}
	return *br, renewed, true
}

// Remove stops a browser from using the web UI, at once.
func (l *List) Remove(id string) error {
	l.mu.Lock()
	defer l.mu.Unlock()
	br, ok := l.bs[id]
	if !ok {
		return ErrNotFound
	}
	delete(l.bs, id)
	if err := l.save(); err != nil {
		l.bs[id] = br
		return err
	}
	delete(l.tries, id)
	return nil
}

// List returns every trusted browser, oldest first, without session hashes.
func (l *List) List() []Browser {
	l.mu.Lock()
	defer l.mu.Unlock()
	out := make([]Browser, 0, len(l.bs))
	for _, br := range l.bs {
		b := *br
		b.SessionHash = ""
		out = append(out, b)
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].AddedAt.Equal(out[j].AddedAt) {
			return out[i].AddedAt.Before(out[j].AddedAt)
		}
		return out[i].ID < out[j].ID
	})
	return out
}

func (l *List) dropExpired(now time.Time) {
	kept := l.links[:0]
	for _, k := range l.links {
		if now.Before(k.expires) {
			kept = append(kept, k)
		}
	}
	l.links = kept
}

func (l *List) save() error {
	f := file{Version: fileVersion, Browsers: make([]Browser, 0, len(l.bs)), Password: l.password}
	for _, br := range l.bs {
		f.Browsers = append(f.Browsers, *br)
	}
	sort.Slice(f.Browsers, func(i, j int) bool { return f.Browsers[i].ID < f.Browsers[j].ID })
	b, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return err
	}
	_, err = atomicfile.Write(l.path, bytes.NewReader(b), true)
	return err
}

// newSecret returns 32 random bytes as text (for a link or a cookie) and the
// hash that is kept instead.
func newSecret() (secret, hash string, err error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", "", err
	}
	secret = base64.RawURLEncoding.EncodeToString(b)
	return secret, hashOf(secret), nil
}

func hashOf(secret string) string {
	s := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(s[:])
}

func newID() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func cleanName(n string) (string, error) {
	n = strings.TrimSpace(n)
	if n == "" || !utf8.ValidString(n) || utf8.RuneCountInString(n) > 60 {
		return "", ErrBadName
	}
	for _, r := range n {
		if unicode.IsControl(r) {
			return "", ErrBadName
		}
	}
	return n, nil
}
