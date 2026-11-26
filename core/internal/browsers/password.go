package browsers

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
)

// The atrium's password lets a browser that is already trusted sign back in
// once its session has ended (30 days unused, or signed out). It never adds a
// browser: that still takes a one-time link. So the password is useless
// without a trusted browser's cookie, and a backup holding its hash cannot
// sign anyone in.

var (
	// KnownLife is how long a browser stays known (its cookie lasts this long),
	// renewed with its session. 400 days is the most browsers keep a cookie.
	KnownLife = 400 * 24 * time.Hour

	// MaxTries wrong passwords on one browser lock it out of password sign-in
	// for LockFor. A link still works.
	MaxTries = 5
	LockFor  = 15 * time.Minute

	// HashCost is Argon2id's cost for new hashes: time (passes), memory in KiB,
	// threads. Each hash keeps its own cost, so changing this later is safe.
	// Tests lower it.
	HashCost = struct {
		Time, MemoryKiB uint32
		Threads         uint8
	}{3, 64 * 1024, 4}
)

var (
	// ErrBadPassword is the one answer for a wrong password, a browser that is
	// not known, or an atrium with no password.
	ErrBadPassword  = errors.New("browsers: that password isn't right")
	ErrLocked       = errors.New("browsers: too many wrong passwords on this browser; wait, or sign in with a link")
	ErrWeakPassword = errors.New("browsers: a password must be 8 to 256 characters")
)

// hashing allows two Argon2 runs at once, so a flood of sign-in tries cannot
// take all the atrium's memory (64 MiB each).
var hashing = make(chan struct{}, 2)

type tries struct {
	n     int
	until time.Time // locked until then
}

// HasPassword says whether the atrium has a password.
func (l *List) HasPassword() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.password != ""
}

// Known finds the trusted browser this cookie secret belongs to, signed in or
// not. ok is false for an unknown secret or one past its KnownUntil.
func (l *List) Known(secret string) (Browser, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	br := l.known(secret, l.now())
	if br == nil {
		return Browser{}, false
	}
	return *br, true
}

func (l *List) known(secret string, now time.Time) *Browser {
	if secret == "" {
		return nil
	}
	hash := hashOf(secret)
	var br *Browser
	for _, x := range l.bs {
		if subtle.ConstantTimeCompare([]byte(x.SessionHash), []byte(hash)) == 1 {
			br = x
		}
	}
	if br == nil || !now.Before(knownUntil(br)) {
		return nil
	}
	return br
}

// knownUntil reads lists from before the password: such a browser is known
// for as long as its session lasts, and gets KnownUntil at its next renewal.
func knownUntil(br *Browser) time.Time {
	if br.KnownUntil.After(br.SessionExpires) {
		return br.KnownUntil
	}
	return br.SessionExpires
}

// SignIn signs a known browser back in with the atrium's password and starts
// a new session on the same cookie secret.
func (l *List) SignIn(secret, password string) (Browser, error) {
	l.mu.Lock()
	br := l.known(secret, l.now())
	hash := l.password
	if br == nil || hash == "" {
		l.mu.Unlock()
		return Browser{}, ErrBadPassword
	}
	id := br.ID
	if l.locked(id) {
		l.mu.Unlock()
		return Browser{}, ErrLocked
	}
	l.mu.Unlock()

	ok := verify(hash, password) // slow, so outside the lock

	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	br = l.bs[id]
	if br == nil || l.password != hash { // removed, or the password changed meanwhile
		return Browser{}, ErrBadPassword
	}
	if !ok {
		l.failed(id)
		return Browser{}, ErrBadPassword
	}
	delete(l.tries, id)
	was := *br
	br.SessionExpires, br.KnownUntil, br.LastSeen = now.Add(SessionLife).UTC(), now.Add(KnownLife).UTC(), now.UTC()
	if err := l.save(); err != nil {
		*br = was
		return Browser{}, err
	}
	return *br, nil
}

// SignOut ends this browser's session. With a password set the browser stays
// trusted, so it can sign back in with it (kept is true), for KnownLife from
// now; without one it is removed, or it could never get back in without a
// link anyway.
func (l *List) SignOut(id string) (kept bool, err error) {
	l.mu.Lock()
	br, ok := l.bs[id]
	if !ok {
		l.mu.Unlock()
		return false, ErrNotFound
	}
	if l.password == "" {
		l.mu.Unlock()
		return false, l.Remove(id)
	}
	defer l.mu.Unlock()
	was := *br
	now := l.now()
	// Signing out is a use: the browser stays known as long as after any other
	// (and the web layer sends its cookie again with that life).
	br.KnownUntil, br.LastSeen = now.Add(KnownLife).UTC(), now.UTC()
	br.SessionExpires = now.UTC() // Check needs now before it
	if err := l.save(); err != nil {
		*br = was
		return false, err
	}
	return true, nil
}

// SetPassword sets, changes or (next == "") removes the atrium's password,
// for the signed-in browser id. Changing or removing it needs the current
// one; a wrong one counts towards id's lock-out.
func (l *List) SetPassword(id, current, next string) error {
	if next != "" && (!utf8.ValidString(next) || utf8.RuneCountInString(next) < 8 || len(next) > 256) {
		return ErrWeakPassword
	}
	l.mu.Lock()
	if _, ok := l.bs[id]; !ok {
		l.mu.Unlock()
		return ErrNotFound
	}
	if l.locked(id) {
		l.mu.Unlock()
		return ErrLocked
	}
	old := l.password
	l.mu.Unlock()

	if old == "" && next == "" {
		return nil
	}
	if old != "" && !verify(old, current) {
		l.mu.Lock()
		defer l.mu.Unlock()
		l.failed(id)
		return ErrBadPassword
	}
	var hash string
	if next != "" {
		var err error
		if hash, err = hashPassword(next); err != nil {
			return err
		}
	}

	l.mu.Lock()
	defer l.mu.Unlock()
	if l.password != old { // changed meanwhile: current may no longer be right
		return ErrBadPassword
	}
	delete(l.tries, id)
	return l.setPassword(hash)
}

// ClearPassword removes the password without the current one, for
// styli serve --clear-password: having the atrium's machine is the proof.
func (l *List) ClearPassword() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.setPassword("")
}

func (l *List) setPassword(hash string) error {
	was := l.password
	l.password = hash
	if err := l.save(); err != nil {
		l.password = was
		return err
	}
	return nil
}

// locked and failed keep the lock-out count, in memory only: a restart
// clears it, which costs an attacker a restart they cannot cause.
func (l *List) locked(id string) bool {
	t := l.tries[id]
	return t != nil && l.now().Before(t.until)
}

func (l *List) failed(id string) {
	t := l.tries[id]
	if t == nil {
		t = &tries{}
		l.tries[id] = t
	}
	if t.n++; t.n >= MaxTries {
		t.n, t.until = 0, l.now().Add(LockFor)
	}
}

// hashPassword makes an Argon2id hash in the usual text form:
// $argon2id$v=19$m=65536,t=3,p=4$<salt>$<key>.
func hashPassword(pw string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	c := HashCost
	hashing <- struct{}{}
	key := argon2.IDKey([]byte(pw), salt, c.Time, c.MemoryKiB, c.Threads, 32)
	<-hashing
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version, c.MemoryKiB, c.Time, c.Threads, b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

type parsedHash struct {
	time, memory uint32
	threads      uint8
	salt, key    []byte
}

// parseHash reads a hash and refuses one whose cost is out of bounds, so a
// damaged file cannot make the atrium allocate gigabytes.
func parseHash(s string) (parsedHash, error) {
	bad := errors.New("browsers: the password hash is damaged")
	parts := strings.Split(s, "$")
	if len(parts) != 6 || parts[0] != "" || parts[1] != "argon2id" || parts[2] != fmt.Sprintf("v=%d", argon2.Version) {
		return parsedHash{}, bad
	}
	var p parsedHash
	var m, t, th uint64
	if n, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &m, &t, &th); err != nil || n != 3 {
		return parsedHash{}, bad
	}
	if t < 1 || t > 16 || th < 1 || th > 64 || m < 8*th || m > 1<<20 { // at most 1 GiB
		return parsedHash{}, bad
	}
	p.time, p.memory, p.threads = uint32(t), uint32(m), uint8(th)
	var err1, err2 error
	p.salt, err1 = base64.RawStdEncoding.DecodeString(parts[4])
	p.key, err2 = base64.RawStdEncoding.DecodeString(parts[5])
	if err1 != nil || err2 != nil || len(p.salt) < 8 || len(p.key) < 16 || len(p.key) > 64 {
		return parsedHash{}, bad
	}
	return p, nil
}

func verify(hash, pw string) bool {
	p, err := parseHash(hash)
	if err != nil {
		return false
	}
	hashing <- struct{}{}
	key := argon2.IDKey([]byte(pw), p.salt, p.time, p.memory, p.threads, uint32(len(p.key)))
	<-hashing
	return subtle.ConstantTimeCompare(key, p.key) == 1
}
