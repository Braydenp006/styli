// Package webtls gives the atrium's web UI HTTPS without any outside service:
// the atrium is its own small certificate authority (CA) and signs its web
// certificate with it. Each phone or laptop installs the CA once, and then
// sees the atrium as an ordinary secure site.
//
// The CA can vouch for nothing but the atrium: its name constraints list only
// the atrium's own names (and 127.0.0.1 and ::1), and it may only sign
// certificates for web servers. A stolen CA key could therefore impersonate
// the atrium, but not your bank or your router. Its key stays in the atrium's
// config folder, outside the vault and its backups.
//
// Everything here is Go's standard library (crypto/x509, crypto/ecdsa); no
// cryptography is written by hand.
package webtls

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"io/fs"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"styli/core/internal/atomicfile"
)

// The files, in the atrium's config folder. The keys are private (0600).
const (
	caKeyFile   = "web-ca.key"
	caCertFile  = "web-ca.crt"
	webKeyFile  = "web.key"
	webCertFile = "web.crt"
)

// How long certificates last. The web certificate stays under the 398 days
// browsers accept, and is renewed at start well before it ends.
var (
	caLife    = 10 * 365 * 24 * time.Hour
	webLife   = 397 * 24 * time.Hour
	renewLeft = 30 * 24 * time.Hour
)

// loopbackIPs are always in the certificate, so the atrium's own browser can
// use it too.
var loopbackIPs = []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")}

// ErrNotCovered means a name was asked for that the existing CA may not vouch
// for (it was added after the CA was made). Making a new CA fixes it, but
// every phone and laptop must then install the new one.
var ErrNotCovered = errors.New("webtls: a name is not covered by this atrium's certificate authority")

// Setup is what the web server needs.
type Setup struct {
	Cert        tls.Certificate // the web certificate, followed by the CA
	CADER       []byte          // the CA certificate, for phones to install
	Fingerprint string          // SHA-256 of the CA certificate, as phones show it: "AB CD …"
	Names       []string        // the host names the certificate is for
}

// Load returns the atrium's CA and web certificate for names (host names; the
// loopback addresses are added), making or renewing what is needed. newCA
// retires the existing CA first (its files are renamed, never deleted).
func Load(dir string, names []string, newCA bool, now time.Time) (*Setup, error) {
	names = cleanNames(names)
	if len(names) == 0 {
		return nil, errors.New("webtls: no host name for the certificate")
	}
	if newCA {
		if err := retireCA(dir, now); err != nil {
			return nil, err
		}
	}
	caKey, ca, err := loadCA(dir, names, now)
	if err != nil {
		return nil, err
	}
	leafKey, leaf, err := loadLeaf(dir, ca, names, now)
	if err != nil {
		return nil, err
	}
	if leaf == nil {
		if leafKey, leaf, err = issueLeaf(dir, caKey, ca, names, now); err != nil {
			return nil, err
		}
	}
	sum := sha256.Sum256(ca.Raw)
	return &Setup{
		Cert:        tls.Certificate{Certificate: [][]byte{leaf.Raw, ca.Raw}, PrivateKey: leafKey, Leaf: leaf},
		CADER:       ca.Raw,
		Fingerprint: fingerprint(sum[:]),
		Names:       names,
	}, nil
}

func cleanNames(in []string) []string {
	var out []string
	for _, n := range in {
		n = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(n), "."))
		if n != "" && net.ParseIP(n) == nil && !slices.Contains(out, n) {
			out = append(out, n)
		}
	}
	return out
}

func fingerprint(b []byte) string {
	parts := make([]string, len(b))
	for i, x := range b {
		parts[i] = fmt.Sprintf("%02X", x)
	}
	return strings.Join(parts, " ")
}

// loadCA reads the CA, or makes one limited to names if there is none.
func loadCA(dir string, names []string, now time.Time) (*ecdsa.PrivateKey, *x509.Certificate, error) {
	key, err := readKey(filepath.Join(dir, caKeyFile))
	if errors.Is(err, fs.ErrNotExist) {
		return makeCA(dir, names, now)
	}
	if err != nil {
		return nil, nil, err
	}
	ca, err := readCert(filepath.Join(dir, caCertFile))
	if err != nil {
		return nil, nil, err
	}
	if !ca.IsCA || !keyMatches(ca, key) {
		return nil, nil, fmt.Errorf("webtls: %s does not belong with %s; restore both from a backup, or start with --new-ca", caCertFile, caKeyFile)
	}
	return key, ca, nil
}

func makeCA(dir string, names []string, now time.Time) (*ecdsa.PrivateKey, *x509.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	var ipRanges []*net.IPNet
	for _, ip := range loopbackIPs {
		bits := 8 * len(ip.To16())
		if ip.To4() != nil {
			ip, bits = ip.To4(), 32
		}
		ipRanges = append(ipRanges, &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)})
	}
	tmpl := &x509.Certificate{
		SerialNumber:          serial(),
		Subject:               pkix.Name{CommonName: "Styli atrium " + names[0], Organization: []string{"Styli"}},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(caLife),
		IsCA:                  true,
		BasicConstraintsValid: true,
		MaxPathLenZero:        true, // it signs web certificates only, never another CA
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		// The name constraints: only the atrium's names and the loopback
		// addresses, marked critical so a client that cannot read them refuses.
		PermittedDNSDomainsCritical: true,
		PermittedDNSDomains:         names,
		PermittedIPRanges:           ipRanges,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	ca, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, nil, err
	}
	// Key first: a crash after it leaves a key without a certificate, which
	// loadCA reports rather than silently replacing.
	if err := writeKey(filepath.Join(dir, caKeyFile), key, false); err != nil {
		return nil, nil, err
	}
	if err := writePEM(filepath.Join(dir, caCertFile), "CERTIFICATE", der, 0o644); err != nil {
		return nil, nil, err
	}
	return key, ca, nil
}

func retireCA(dir string, now time.Time) error {
	suffix := ".retired-" + now.UTC().Format("20060102T150405")
	for _, f := range []string{caKeyFile, caCertFile, webKeyFile, webCertFile} {
		p := filepath.Join(dir, f)
		if err := os.Rename(p, p+suffix); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
	}
	return nil
}

// loadLeaf returns the saved web certificate if it can still be used: signed
// by this CA, for exactly these names, with more than renewLeft to go. A nil
// certificate means a new one is needed.
func loadLeaf(dir string, ca *x509.Certificate, names []string, now time.Time) (*ecdsa.PrivateKey, *x509.Certificate, error) {
	key, err := readKey(filepath.Join(dir, webKeyFile))
	if err != nil {
		return nil, nil, nil
	}
	leaf, err := readCert(filepath.Join(dir, webCertFile))
	if err != nil || !keyMatches(leaf, key) || leaf.CheckSignatureFrom(ca) != nil ||
		!slices.Equal(leaf.DNSNames, names) || leaf.NotAfter.Sub(now) < renewLeft {
		return nil, nil, nil
	}
	return key, leaf, nil
}

func issueLeaf(dir string, caKey *ecdsa.PrivateKey, ca *x509.Certificate, names []string, now time.Time) (*ecdsa.PrivateKey, *x509.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial(),
		Subject:      pkix.Name{CommonName: names[0]},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(webLife),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     names,
		IPAddresses:  loopbackIPs,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca, &key.PublicKey, caKey)
	if err != nil {
		return nil, nil, err
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, nil, err
	}
	// Signing does not check the CA's constraints; verifying does, as a
	// browser would. A name the CA may not vouch for stops here.
	roots := x509.NewCertPool()
	roots.AddCert(ca)
	for _, n := range names {
		if _, err := leaf.Verify(x509.VerifyOptions{Roots: roots, DNSName: n, CurrentTime: now}); err != nil {
			var inv x509.CertificateInvalidError
			if errors.As(err, &inv) && inv.Reason == x509.CANotAuthorizedForThisName {
				return nil, nil, fmt.Errorf("%w: %q (start with --new-ca to make a new one; every phone and laptop must then install it again)", ErrNotCovered, n)
			}
			return nil, nil, fmt.Errorf("webtls: the new web certificate does not verify for %q: %w", n, err)
		}
	}
	if err := writeKey(filepath.Join(dir, webKeyFile), key, true); err != nil {
		return nil, nil, err
	}
	if err := writePEM(filepath.Join(dir, webCertFile), "CERTIFICATE", der, 0o644); err != nil {
		return nil, nil, err
	}
	return key, leaf, nil
}

func serial() *big.Int {
	n, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	if err != nil {
		panic(err) // crypto/rand does not fail on supported systems
	}
	return n
}

func keyMatches(c *x509.Certificate, key *ecdsa.PrivateKey) bool {
	pub, ok := c.PublicKey.(*ecdsa.PublicKey)
	return ok && pub.Equal(&key.PublicKey)
}

func readKey(p string) (*ecdsa.PrivateKey, error) {
	b, err := os.ReadFile(p)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(p, 0o600); err != nil { // private, even if loosened by hand
		return nil, err
	}
	blk, _ := pem.Decode(b)
	if blk == nil || blk.Type != "PRIVATE KEY" {
		return nil, fmt.Errorf("webtls: %s is damaged", p)
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	key, ok := k.(*ecdsa.PrivateKey)
	if err != nil || !ok {
		return nil, fmt.Errorf("webtls: %s is damaged", p)
	}
	return key, nil
}

func readCert(p string) (*x509.Certificate, error) {
	b, err := os.ReadFile(p)
	if err != nil {
		return nil, err
	}
	blk, _ := pem.Decode(b)
	if blk == nil || blk.Type != "CERTIFICATE" {
		return nil, fmt.Errorf("webtls: %s is damaged", p)
	}
	return x509.ParseCertificate(blk.Bytes)
}

func writeKey(p string, key *ecdsa.PrivateKey, overwrite bool) error {
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	if err := writePEMOpt(p, "PRIVATE KEY", der, overwrite); err != nil {
		return err
	}
	return os.Chmod(p, 0o600)
}

func writePEM(p, typ string, der []byte, mode os.FileMode) error {
	if err := writePEMOpt(p, typ, der, true); err != nil {
		return err
	}
	return os.Chmod(p, mode)
}

func writePEMOpt(p, typ string, der []byte, overwrite bool) error {
	_, err := atomicfile.Write(p, bytes.NewReader(pem.EncodeToMemory(&pem.Block{Type: typ, Bytes: der})), overwrite)
	return err
}
