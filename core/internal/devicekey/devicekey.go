// Package devicekey holds this device's own key, which proves who it is when
// devices sync. There is no certificate authority: each device makes one key
// the first time it runs and keeps it, and other devices trust it by its
// fingerprint, which pairing exchanges. See "Sync protocol" in docs/DESIGN.md.
package devicekey

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"io/fs"
	"math/big"
	"os"
	"path/filepath"
	"time"

	"styli/core/internal/atomicfile"
)

// FileName is the key's file in the device's config folder.
const FileName = "device.key"

// Key is this device's identity.
type Key struct {
	ID   string          // the fingerprint other devices know this one by
	Cert tls.Certificate // a self-signed certificate for the key, for TLS
}

// Load reads this device's key from dir, making one the first time. A key
// file that exists but cannot be read is an error: it is never replaced
// quietly, because a new key would silently undo every pairing.
func Load(dir string) (*Key, error) {
	p := filepath.Join(dir, FileName)
	b, err := os.ReadFile(p)
	if errors.Is(err, fs.ErrNotExist) {
		_, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		der, err := x509.MarshalPKCS8PrivateKey(priv)
		if err != nil {
			return nil, err
		}
		b = pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
		// Not overwrite: if another start made one meanwhile, keep that one.
		if _, err := atomicfile.Write(p, bytes.NewReader(b), false); err != nil && !errors.Is(err, atomicfile.ErrExists) {
			return nil, err
		}
		if b, err = os.ReadFile(p); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	if err := os.Chmod(p, 0o600); err != nil { // private, even if loosened by hand
		return nil, err
	}
	blk, _ := pem.Decode(b)
	if blk == nil || blk.Type != "PRIVATE KEY" {
		return nil, fmt.Errorf("devicekey: %s is damaged; restore it from a backup or pair this device again", p)
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	priv, ok := k.(ed25519.PrivateKey)
	if err != nil || !ok {
		return nil, fmt.Errorf("devicekey: %s is damaged; restore it from a backup or pair this device again", p)
	}
	return fromKey(priv)
}

// fromKey builds the certificate. It is made fresh on every start: trust
// comes from the key's fingerprint, which stays the same, not from the
// certificate's dates or signer.
func fromKey(priv ed25519.PrivateKey) (*Key, error) {
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	if err != nil {
		return nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		NotBefore:    time.Unix(0, 0),
		NotAfter:     time.Date(9999, 12, 31, 0, 0, 0, 0, time.UTC),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, priv.Public(), priv)
	if err != nil {
		return nil, err
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, err
	}
	return &Key{
		ID:   Fingerprint(leaf),
		Cert: tls.Certificate{Certificate: [][]byte{der}, PrivateKey: priv, Leaf: leaf},
	}, nil
}

// Fingerprint is the hex sha256 of a certificate's public key. It names the
// key, not the certificate, so it never changes for a device.
func Fingerprint(c *x509.Certificate) string {
	s := sha256.Sum256(c.RawSubjectPublicKeyInfo)
	return hex.EncodeToString(s[:])
}

// Retire moves this device's key aside (it was revoked on the atrium), so the
// next Load makes a new one. The old file is kept, renamed, never deleted.
func Retire(dir string) error {
	p := filepath.Join(dir, FileName)
	return os.Rename(p, fmt.Sprintf("%s.revoked-%d", p, time.Now().UnixMilli()))
}
