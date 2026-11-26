package web

import (
	"encoding/json"
	"encoding/xml"
	"net/http"
	"strings"
	"testing"
	"time"

	"styli/core/internal/browsers"
	"styli/core/internal/maid"
)

const noCookie = ""

// as sends a request as the browser with this session ("" for none).
func (e *tenv) as(t *testing.T, session, method, path string, body any) (*http.Response, map[string]any) {
	t.Helper()
	c := "Cookie"
	v := ""
	if session != "" {
		v = e.cookieName + "=" + session
	}
	resp, b := e.req(t, method, path, body, c, v)
	var m map[string]any
	json.Unmarshal(b, &m)
	return resp, m
}

func sessionFrom(t *testing.T, resp *http.Response) *http.Cookie {
	t.Helper()
	for _, c := range resp.Cookies() {
		if c.Name == sessionCookie || c.Name == "__Host-"+sessionCookie {
			return c
		}
	}
	return nil
}

// Every API call needs a signed-in browser; the page and its files do not
// (they hold no notes, and the page is what shows how to sign in).
func TestTheAPINeedsASignedInBrowser(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "secret.md", "private text")
	for _, c := range []struct{ method, path string }{
		{"GET", "/api/notes"},
		{"GET", "/api/notes/" + id},
		{"POST", "/api/notes"},
		{"GET", "/api/settings"},
		{"GET", "/api/session"},
		{"GET", "/api/browsers"},
		{"POST", "/api/browsers/links"},
		{"GET", "/api/join"}, // only POST is open
		{"POST", "/api/signout"},
		{"POST", "/api/password"},
		{"GET", "/api/no-such-thing"}, // the whole of /api/, not a list of routes
	} {
		for name, session := range map[string]string{"no cookie": noCookie, "a forged cookie": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"} {
			resp, m := e.as(t, session, c.method, c.path, map[string]string{"path": "x.md", "text": "x"})
			if resp.StatusCode != 401 || m["code"] != "login_required" {
				t.Errorf("%s %s with %s: %d %v", c.method, c.path, name, resp.StatusCode, m)
			}
		}
	}
	for _, p := range []string{"/", "/static/js/app.js"} {
		if resp, b := e.req(t, "GET", p, nil, "Cookie", ""); resp.StatusCode != 200 || strings.Contains(string(b), "private text") {
			t.Errorf("%s without a session: %d", p, resp.StatusCode)
		}
	}
}

func TestABrowserJoinsWithALinkOnce(t *testing.T) {
	e := newEnv(t)
	link := e.call(t, "POST", "/api/browsers/links", nil, 201)
	url, _ := link["url"].(string)
	prefix := "http://" + e.host + "/#/join/"
	if !strings.HasPrefix(url, prefix) {
		t.Fatalf("url %q", url)
	}
	token := strings.TrimPrefix(url, prefix)
	if exp, _ := time.Parse(time.RFC3339, link["expires_at"].(string)); time.Until(exp) > browsers.LinkLife || time.Until(exp) < browsers.LinkLife-time.Minute {
		t.Fatalf("expires %v", link["expires_at"])
	}
	svg, _ := link["qr_svg"].(string)
	if err := xml.Unmarshal([]byte(svg), new(struct{})); err != nil || !strings.HasPrefix(svg, "<svg") || strings.Contains(svg, "script") {
		t.Fatalf("QR code: %v %.80q", err, svg)
	}

	resp, m := e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": token, "name": "iPhone Safari"})
	c := sessionFrom(t, resp)
	if resp.StatusCode != 201 || c == nil || c.Value == "" {
		t.Fatalf("join: %d %v %v", resp.StatusCode, m, c)
	}
	if !c.HttpOnly || c.SameSite != http.SameSiteStrictMode || c.Path != "/" || c.MaxAge != int(browsers.KnownLife.Seconds()) {
		t.Fatalf("cookie flags: %+v", c)
	}
	if resp, m := e.as(t, c.Value, "GET", "/api/session", nil); resp.StatusCode != 200 || m["browser"].(map[string]any)["name"] != "iPhone Safari" {
		t.Fatalf("session: %d %v", resp.StatusCode, m)
	}
	_, m = e.as(t, c.Value, "GET", "/api/browsers", nil)
	list := m["browsers"].([]any)
	if len(list) != 2 {
		t.Fatalf("browsers: %v", list)
	}
	for _, x := range list {
		b := x.(map[string]any)
		if (b["name"] == "iPhone Safari") != (b["this"] == true) {
			t.Fatalf("which one is this browser: %v", list)
		}
	}

	// The same link again, and a made-up one, get the same answer.
	for _, tok := range []string{token, "made-up"} {
		resp, m := e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": tok, "name": "Second"})
		if resp.StatusCode != 400 || m["code"] != "bad_link" || sessionFrom(t, resp) != nil {
			t.Fatalf("token %q: %d %v", tok, resp.StatusCode, m)
		}
	}
}

// Joining is still a write: other sites cannot make a browser do it.
func TestJoinFromAnotherSiteIsRefused(t *testing.T) {
	e := newEnv(t)
	token := strings.TrimPrefix(e.call(t, "POST", "/api/browsers/links", nil, 201)["url"].(string), "http://"+e.host+"/#/join/")
	resp, _ := e.req(t, "POST", "/api/join", map[string]string{"token": token, "name": "x"}, "Cookie", "", "Sec-Fetch-Site", "cross-site")
	if resp.StatusCode != 403 {
		t.Fatalf("cross-site join: %d", resp.StatusCode)
	}
	if len(e.br.List()) != 1 {
		t.Fatal("a cross-site join made a browser")
	}
}

func TestJoinAttemptsAreLimited(t *testing.T) {
	e := newEnv(t)
	token := strings.TrimPrefix(e.call(t, "POST", "/api/browsers/links", nil, 201)["url"].(string), "http://"+e.host+"/#/join/")
	for i := 0; i < joinFailLimit; i++ {
		if resp, _ := e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": "guess", "name": "x"}); resp.StatusCode != 400 {
			t.Fatalf("guess %d: %d", i, resp.StatusCode)
		}
	}
	// Even a real link waits now, and is not used up by waiting.
	if resp, m := e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": token, "name": "x"}); resp.StatusCode != 429 || m["code"] != "too_many_attempts" {
		t.Fatalf("after %d guesses: %d %v", joinFailLimit, resp.StatusCode, m)
	}
	timeNow = func() time.Time { return time.Now().Add(joinFailWindow) } // a minute later
	t.Cleanup(func() { timeNow = time.Now })
	if resp, _ := e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": token, "name": "Patient"}); resp.StatusCode != 201 {
		t.Fatalf("after the wait: %d", resp.StatusCode)
	}
}

func TestRemovingABrowserSignsItOutAtOnce(t *testing.T) {
	e := newEnv(t)
	other := e.joinNew(t, "Old phone")
	_, m := e.as(t, e.cookie, "GET", "/api/browsers", nil)
	var otherID string
	for _, x := range m["browsers"].([]any) {
		if b := x.(map[string]any); b["name"] == "Old phone" {
			otherID = b["id"].(string)
		}
	}
	if resp, m := e.as(t, e.cookie, "POST", "/api/browsers/"+otherID+"/remove", nil); resp.StatusCode != 200 || m["signed_out"] != false {
		t.Fatalf("remove another: %d %v", resp.StatusCode, m)
	}
	if resp, _ := e.as(t, other, "GET", "/api/notes", nil); resp.StatusCode != 401 {
		t.Fatalf("a removed browser: %d", resp.StatusCode)
	}

	// Removing this browser signs it out, cookie and all.
	me := e.call(t, "GET", "/api/session", nil, 200)["browser"].(map[string]any)["id"].(string)
	resp, m := e.as(t, e.cookie, "POST", "/api/browsers/"+me+"/remove", nil)
	if c := sessionFrom(t, resp); resp.StatusCode != 200 || m["signed_out"] != true || c == nil || c.MaxAge >= 0 {
		t.Fatalf("remove this one: %d %v %+v", resp.StatusCode, m, c)
	}
	if resp, _ := e.as(t, e.cookie, "GET", "/api/notes", nil); resp.StatusCode != 401 {
		t.Fatalf("after signing itself out: %d", resp.StatusCode)
	}
	if resp, _ := e.as(t, e.cookie, "POST", "/api/browsers/nope/remove", nil); resp.StatusCode != 401 {
		t.Fatalf("a signed-out browser removing: %d", resp.StatusCode)
	}
}

// A session in use is renewed, and the cookie is sent again with its new life.
func TestASessionInUseIsRenewed(t *testing.T) {
	old := browsers.RenewEvery
	browsers.RenewEvery = 0 // renew on every request
	t.Cleanup(func() { browsers.RenewEvery = old })
	e := newEnv(t)
	resp, _ := e.as(t, e.cookie, "GET", "/api/notes", nil)
	if c := sessionFrom(t, resp); resp.StatusCode != 200 || c == nil || c.Value != e.cookie || c.MaxAge != int(browsers.KnownLife.Seconds()) {
		t.Fatalf("renewed cookie: %d %+v", resp.StatusCode, c)
	}
}

// A device's own page is for that machine only and keeps no browser list.
func TestADeviceNeedsNoSignIn(t *testing.T) {
	e := newEnv(t)
	e.ts.Config.Handler = New(Deps{Vault: e.v, Store: e.st, Settings: e.set, Maid: maid.New(e.v, e.set, t.Logf), Logf: t.Logf, Addr: e.host})
	if resp, _ := e.as(t, noCookie, "GET", "/api/notes", nil); resp.StatusCode != 200 {
		t.Fatalf("notes on a device: %d", resp.StatusCode)
	}
	for _, c := range []struct{ method, path string }{{"GET", "/api/session"}, {"POST", "/api/join"}, {"GET", "/api/browsers"}, {"POST", "/api/browsers/links"},
		{"GET", "/api/signin"}, {"POST", "/api/signin"}, {"POST", "/api/signout"}, {"POST", "/api/password"}} {
		if resp, m := e.as(t, noCookie, c.method, c.path, map[string]string{}); resp.StatusCode != 404 || m["code"] != "not_atrium" {
			t.Errorf("%s %s on a device: %d %v", c.method, c.path, resp.StatusCode, m)
		}
	}
}

// joinNew signs in another browser and returns its session.
func (e *tenv) joinNew(t *testing.T, name string) string {
	t.Helper()
	token := strings.TrimPrefix(e.call(t, "POST", "/api/browsers/links", nil, 201)["url"].(string), "http://"+e.host+"/#/join/")
	resp, m := e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": token, "name": name})
	c := sessionFrom(t, resp)
	if resp.StatusCode != 201 || c == nil {
		t.Fatalf("join %q: %d %v", name, resp.StatusCode, m)
	}
	return c.Value
}
