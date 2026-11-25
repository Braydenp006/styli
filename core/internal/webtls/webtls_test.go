package webtls

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var (
	t0    = time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	names = []string{"styli-1a2b3c4d.local", "localhost", "notes.example.ts.net"}
)

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

func roots(s *Setup) *x509.CertPool {
	p := x509.NewCertPool()
	p.AddCert(must(x509.ParseCertificate(s.CADER)))
	return p
}

// The web certificate verifies, as a browser that installed the CA would
// check it, for each of the atrium's names and the loopback addresses.
func TestTheCertificateVerifiesForTheAtriumsNames(t *testing.T) {
	dir := t.TempDir()
	s := must(Load(dir, names, false, t0))
	for _, n := range append(names, "127.0.0.1", "::1") {
		if _, err := s.Cert.Leaf.Verify(x509.VerifyOptions{Roots: roots(s), DNSName: n, CurrentTime: t0}); err != nil {
			t.Errorf("%s: %v", n, err)
		}
	}
	if len(s.Cert.Certificate) != 2 {
		t.Fatal("the CA is not sent with the certificate")
	}
	if len(strings.Fields(s.Fingerprint)) != 32 {
		t.Fatalf("fingerprint %q", s.Fingerprint)
	}
	if left := s.Cert.Leaf.NotAfter.Sub(t0); left > 398*24*time.Hour {
		t.Fatalf("the web certificate lasts %v, longer than browsers accept", left)
	}
}

// The CA cannot vouch for any other site, even if its key were stolen and
// used to sign a certificate for one: the constraints are in the CA itself.
func TestTheCACannotVouchForOtherSites(t *testing.T) {
	dir := t.TempDir()
	s := must(Load(dir, names, false, t0))
	caKey := must(readKey(filepath.Join(dir, caKeyFile)))
	ca := must(x509.ParseCertificate(s.CADER))

	forged := func(dns []string, ips []net.IP, eku x509.ExtKeyUsage) *x509.Certificate {
		key := must(ecdsa.GenerateKey(elliptic.P256(), rand.Reader))
		tmpl := &x509.Certificate{SerialNumber: serial(), Subject: pkix.Name{CommonName: "forged"}, NotBefore: t0.Add(-time.Hour), NotAfter: t0.Add(time.Hour),
			KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{eku}, DNSNames: dns, IPAddresses: ips}
		return must(x509.ParseCertificate(must(x509.CreateCertificate(rand.Reader, tmpl, ca, &key.PublicKey, caKey))))
	}
	for name, c := range map[string]struct {
		cert *x509.Certificate
		dns  string
	}{
		"a bank":                     {forged([]string{"bank.example.com"}, nil, x509.ExtKeyUsageServerAuth), "bank.example.com"},
		"another .local machine":     {forged([]string{"printer.local"}, nil, x509.ExtKeyUsageServerAuth), "printer.local"},
		"the router by IP":           {forged(nil, []net.IP{net.ParseIP("192.168.1.1")}, x509.ExtKeyUsageServerAuth), "192.168.1.1"},
		"a name ending like ours":    {forged([]string{"evil-styli-1a2b3c4d.local"}, nil, x509.ExtKeyUsageServerAuth), "evil-styli-1a2b3c4d.local"},
		"our name plus another name": {forged([]string{"styli-1a2b3c4d.local", "bank.example.com"}, nil, x509.ExtKeyUsageServerAuth), "styli-1a2b3c4d.local"},
	} {
		if _, err := c.cert.Verify(x509.VerifyOptions{Roots: roots(s), DNSName: c.dns, CurrentTime: t0}); err == nil {
			t.Errorf("%s: a forged certificate verified", name)
		}
	}
	// Nor can it sign anything but web servers (no client logins, no code signing).
	for name, eku := range map[string]x509.ExtKeyUsage{"client login": x509.ExtKeyUsageClientAuth, "code signing": x509.ExtKeyUsageCodeSigning} {
		c := forged([]string{"styli-1a2b3c4d.local"}, nil, eku)
		if _, err := c.Verify(x509.VerifyOptions{Roots: roots(s), KeyUsages: []x509.ExtKeyUsage{eku}, CurrentTime: t0}); err == nil {
			t.Errorf("%s: verified", name)
		}
	}
}

// Restarting keeps the same CA and certificate; it is renewed near its end,
// or when the names change, always with the same CA.
func TestTheCertificateIsKeptAndRenewed(t *testing.T) {
	dir := t.TempDir()
	s1 := must(Load(dir, names, false, t0))
	s2 := must(Load(dir, names, false, t0.Add(24*time.Hour)))
	if string(s2.CADER) != string(s1.CADER) || !s2.Cert.Leaf.Equal(s1.Cert.Leaf) {
		t.Fatal("a restart changed the certificate")
	}
	near := s1.Cert.Leaf.NotAfter.Add(-renewLeft + time.Hour)
	s3 := must(Load(dir, names, false, near))
	if string(s3.CADER) != string(s1.CADER) || s3.Cert.Leaf.Equal(s1.Cert.Leaf) || !s3.Cert.Leaf.NotAfter.After(s1.Cert.Leaf.NotAfter) {
		t.Fatal("not renewed near its end, or the CA changed")
	}
	fewer := must(Load(dir, names[:2], false, near))
	if string(fewer.CADER) != string(s1.CADER) || len(fewer.Cert.Leaf.DNSNames) != 2 {
		t.Fatalf("names changed: %v", fewer.Cert.Leaf.DNSNames)
	}
}

// A name added after the CA was made is not covered: starting says so, and a
// new CA (which every phone installs again) covers it. The old one is kept.
func TestANameAddedLaterNeedsANewCA(t *testing.T) {
	dir := t.TempDir()
	old := must(Load(dir, names[:1], false, t0))
	if _, err := Load(dir, names, false, t0); !errors.Is(err, ErrNotCovered) || !strings.Contains(err.Error(), "--new-ca") {
		t.Fatalf("a name the CA may not vouch for: %v", err)
	}
	if b, _ := os.ReadFile(filepath.Join(dir, webCertFile)); b == nil {
		t.Fatal("the refused attempt removed the old certificate")
	}
	s := must(Load(dir, names, true, t0))
	if string(s.CADER) == string(old.CADER) {
		t.Fatal("--new-ca kept the old CA")
	}
	retired, _ := filepath.Glob(filepath.Join(dir, caKeyFile+".retired-*"))
	if len(retired) != 1 {
		t.Fatalf("the old CA key was not kept aside: %v", retired)
	}
}

func TestKeysArePrivateAndDamageIsReported(t *testing.T) {
	dir := t.TempDir()
	must(Load(dir, names, false, t0))
	for _, f := range []string{caKeyFile, webKeyFile} {
		p := filepath.Join(dir, f)
		os.Chmod(p, 0o644)
		must(Load(dir, names, false, t0))
		if fi := must(os.Stat(p)); fi.Mode().Perm() != 0o600 {
			t.Errorf("%s is %o", f, fi.Mode().Perm())
		}
	}
	os.WriteFile(filepath.Join(dir, caKeyFile), []byte("not a key"), 0o600)
	if _, err := Load(dir, names, false, t0); err == nil {
		t.Fatal("a damaged CA key was replaced quietly")
	}
	// A CA certificate that does not match its key is reported, not replaced.
	dir2 := t.TempDir()
	must(Load(dir2, names, false, t0))
	other := t.TempDir()
	must(Load(other, names, false, t0))
	b := must(os.ReadFile(filepath.Join(other, caCertFile)))
	os.WriteFile(filepath.Join(dir2, caCertFile), b, 0o644)
	if _, err := Load(dir2, names, false, t0); err == nil || !strings.Contains(err.Error(), "--new-ca") {
		t.Fatalf("a mismatched CA: %v", err)
	}
	if _, err := Load(t.TempDir(), nil, false, t0); err == nil {
		t.Fatal("no names accepted")
	}
}
