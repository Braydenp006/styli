package web

import (
	"net"
	"net/http"
	"strings"
	"testing"
)

// tokenFrom takes the secret out of a sign-in link.
func tokenFrom(t *testing.T, url string) string {
	t.Helper()
	_, tok, ok := strings.Cut(url, "/#/join/")
	if !ok {
		t.Fatalf("not a sign-in link: %q", url)
	}
	return tok
}

// Over HTTPS the session cookie is __Host- and Secure: sent only over TLS, and
// only to this exact host. The plain-http name is not accepted there.
func TestOverHTTPSTheCookieIsHostOnlyAndSecure(t *testing.T) {
	e := newTLSEnv(t)
	link := e.call(t, "POST", "/api/browsers/links", nil, 201)
	resp, m := e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": tokenFrom(t, link["url"].(string)), "name": "iPhone"})
	c := sessionFrom(t, resp)
	if resp.StatusCode != 201 || c == nil || c.Name != "__Host-styli_session" || !c.Secure || !c.HttpOnly || c.Path != "/" || c.Domain != "" || c.SameSite != http.SameSiteStrictMode {
		t.Fatalf("join over https: %d %v %+v", resp.StatusCode, m, c)
	}
	if resp, _ := e.as(t, c.Value, "GET", "/api/session", nil); resp.StatusCode != 200 {
		t.Fatalf("the https session: %d", resp.StatusCode)
	}
	if resp, _ := e.req(t, "GET", "/api/session", nil, "Cookie", "styli_session="+c.Value); resp.StatusCode != 401 {
		t.Fatalf("the plain-http cookie name over https: %d", resp.StatusCode)
	}
}

// A write's Origin must be the page's own, scheme included.
func TestTheOriginMustMatchTheScheme(t *testing.T) {
	for name, c := range map[string]struct {
		env  func(*testing.T) *tenv
		good string
		bad  string
	}{
		"https": {newTLSEnv, "https", "http"},
		"http":  {newEnv, "http", "https"},
	} {
		e := c.env(t)
		note := map[string]string{"path": name + ".md", "text": "x"}
		if resp, b := e.req(t, "POST", "/api/notes", note, "Origin", c.bad+"://"+e.host); resp.StatusCode != 403 {
			t.Errorf("%s with a %s origin: %d %s", name, c.bad, resp.StatusCode, b)
		}
		if resp, b := e.req(t, "POST", "/api/notes", note, "Origin", c.good+"://"+e.host); resp.StatusCode != 201 {
			t.Errorf("%s with its own origin: %d %s", name, resp.StatusCode, b)
		}
	}
}

// The certificate's names are accepted on the server's port; any other name,
// a LAN address included (the certificate has none), is not.
func TestTheCertificatesNamesAreTheAcceptedHosts(t *testing.T) {
	e := newTLSEnv(t)
	_, port, _ := net.SplitHostPort(e.host)
	for host, want := range map[string]int{
		"styli-1a2b3c4d.local:" + port: 200,
		"STYLI-1A2B3C4D.LOCAL:" + port: 200,
		"localhost:" + port:            200,
		"styli-1a2b3c4d.local:1":       403,
		"evil.example:" + port:         403,
		"192.168.1.20:" + port:         403,
	} {
		if resp, _ := e.req(t, "GET", "/api/notes", nil, "Host", host); resp.StatusCode != want {
			t.Errorf("Host %s: %d, want %d", host, resp.StatusCode, want)
		}
	}
}

// Phones install the CA before they can sign in, so it needs no session; it is
// still not something another site can fetch.
func TestTheCACertificateNeedsNoSignIn(t *testing.T) {
	e := newTLSEnv(t)
	resp, b := e.req(t, "GET", "/ca.crt", nil, "Cookie", "")
	if resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "application/x-x509-ca-cert" || string(b) != string(e.tls.CADER) {
		t.Fatalf("ca.crt: %d %q (%d bytes)", resp.StatusCode, resp.Header.Get("Content-Type"), len(b))
	}
	if resp, _ := e.req(t, "GET", "/ca.crt", nil, "Cookie", "", "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "no-cors", "Sec-Fetch-Dest", "empty"); resp.StatusCode != 403 {
		t.Fatalf("fetched by another site: %d", resp.StatusCode)
	}
	if resp, _ := newEnv(t).req(t, "GET", "/ca.crt", nil, "Cookie", ""); resp.StatusCode != 404 {
		t.Fatalf("an atrium without its own CA: %d", resp.StatusCode)
	}
}

// Links name the atrium the way another device reaches it, and come with the
// certificate to install first and its fingerprint to check.
func TestLinksUseTheNameOtherDevicesReach(t *testing.T) {
	e := newTLSEnv(t)
	_, port, _ := net.SplitHostPort(e.host)
	link := e.call(t, "POST", "/api/browsers/links", nil, 201)
	if url := link["url"].(string); !strings.HasPrefix(url, "https://styli-1a2b3c4d.local:"+port+"/#/join/") {
		t.Fatalf("url %q", url)
	}
	ca, _ := link["ca"].(map[string]any)
	if ca == nil || ca["url"] != "https://styli-1a2b3c4d.local:"+port+"/ca.crt" || ca["fingerprint"] != e.tls.Fingerprint || !strings.HasPrefix(ca["qr_svg"].(string), "<svg") {
		t.Fatalf("ca: %v", ca)
	}
	if plain := newEnv(t).call(t, "POST", "/api/browsers/links", nil, 201); plain["ca"] != nil {
		t.Fatalf("a plain atrium offered a CA: %v", plain["ca"])
	}
}
