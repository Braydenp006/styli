// Package pairing adds a new device to the atrium with four magic words and
// an emoji check. See "Devices and pairing" in docs/DESIGN.md.
//
// How it works, for both sides:
//
//  1. The atrium opens a pairing window (5 minutes) and shows four words.
//  2. The device connects to the atrium's sync port over TLS 1.3, each side
//     showing its own key. Neither knows the other's key yet.
//  3. The two run CPace (a PAKE, from filippo.io/cpace) with the words as the
//     password, bound to both keys and to this exact TLS connection. Each side
//     then proves it got the same result. Wrong words, or anyone relaying the
//     connection, makes that proof fail, and the words are used up after one
//     try either way.
//  4. Both screens show six emoji, worked out from this TLS connection and both
//     keys (not from the PAKE, so a flaw in one check is caught by the other).
//     The person confirms on both devices.
//  5. Only then does the atrium add the device's key to its paired devices, and
//     the device saves the atrium's key. From then on sync uses those keys.
//
// No cryptography is written here: CPace, HKDF, HMAC, SHA-256 and TLS all come
// from vetted libraries. This package only decides what goes into them.
package pairing

import (
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"filippo.io/cpace"

	"styli/core/internal/devicekey"
)

const (
	WordCount  = 4
	EmojiCount = 6
	// Window is how long the words work, and how long the emoji wait to be
	// compared.
	Window = 5 * time.Minute

	protocolLabel = "styli pairing v1"
	// ExporterLabel names the value both sides take from their TLS connection.
	ExporterLabel = "EXPORTER-styli-pairing-v1"
)

var (
	ErrNoPairing  = errors.New("pairing: no pairing is open for this device")
	ErrWrongWords = errors.New("pairing: the words did not match")
	ErrKnownKey   = errors.New("pairing: this device's key is already paired")
	ErrRevokedKey = errors.New("pairing: this device's key was revoked")
	ErrCancelled  = errors.New("pairing: cancelled")
	ErrBadWords   = errors.New("pairing: not four magic words")
	ErrBadName    = errors.New("pairing: bad device name")
)

// Symbol is one emoji of the check.
type Symbol struct {
	Char string `json:"char"`
	Name string `json:"name"`
}

// newWords picks WordCount words at random.
func newWords() string {
	out := make([]string, WordCount)
	for i := range out {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(words))))
		if err != nil {
			panic(err) // the OS random source failing is not recoverable
		}
		out[i] = words[n.Int64()]
	}
	return strings.Join(out, "-")
}

var known = func() map[string]bool {
	m := map[string]bool{}
	for _, w := range words {
		m[w] = true
	}
	return m
}()

// NormalizeWords turns what the person typed ("Copper otter, lantern  fig")
// into the form both sides use ("copper-otter-lantern-fig"). It refuses
// anything that is not four words from the list, so a typing mistake is caught
// here and does not use up the one try.
func NormalizeWords(typed string) (string, error) {
	ws := strings.FieldsFunc(strings.ToLower(typed), func(r rune) bool {
		return unicode.IsSpace(r) || r == '-' || r == ',' || r == '.'
	})
	if len(ws) != WordCount {
		return "", ErrBadWords
	}
	for _, w := range ws {
		if !known[w] {
			return "", fmt.Errorf("%w: %q is not one of the words", ErrBadWords, w)
		}
	}
	return strings.Join(ws, "-"), nil
}

// contextInfo binds the PAKE to both keys and to this TLS connection, so the
// result is only the same if the two sides really talk to each other.
func contextInfo(deviceID, atriumID string, exporter []byte) *cpace.ContextInfo {
	return cpace.NewContextInfo(deviceID, atriumID, append([]byte(protocolLabel+"\x00"), exporter...))
}

// proof is what one side sends to show it derived the same key. Each side's
// proof is different, so one cannot simply echo the other's.
func proof(key []byte, who string) []byte {
	b, err := hkdf.Expand(sha256.New, key, protocolLabel+" proof "+who, 32)
	if err != nil {
		panic(err) // only for a length HKDF cannot give
	}
	return b
}

// emojiFor works out the emoji row from the TLS connection and both keys.
func emojiFor(exporter []byte, atriumID, deviceID string) []Symbol {
	h := sha256.New()
	h.Write([]byte(protocolLabel + " emoji\x00"))
	h.Write(exporter)
	h.Write([]byte("\x00" + atriumID + "\x00" + deviceID))
	sum := h.Sum(nil)
	// 6 bits per emoji, taken in order from the hash.
	var acc uint64
	for _, b := range sum[:8] {
		acc = acc<<8 | uint64(b)
	}
	out := make([]Symbol, EmojiCount)
	for i := range out {
		e := emoji[(acc>>(58-6*i))&63]
		out[i] = Symbol{e.Char, e.Name}
	}
	return out
}

// ExporterLen is the length of the value taken from the TLS connection.
const ExporterLen = 32

// certID returns the fingerprint of the first certificate in raw.
func certID(raw [][]byte) (string, error) {
	if len(raw) == 0 {
		return "", errors.New("pairing: no certificate")
	}
	c, err := x509.ParseCertificate(raw[0])
	if err != nil {
		return "", err
	}
	return devicekey.Fingerprint(c), nil
}

// ValidName reports whether a device name is acceptable: up to 100
// characters, none of them control characters, not empty.
func ValidName(n string) bool {
	if n == "" || !utf8.ValidString(n) || utf8.RuneCountInString(n) > 100 {
		return false
	}
	for _, r := range n {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}
