package web

import (
	"net/http"
	"testing"
	"time"

	"styli/core/internal/browsers"
)

func init() {
	browsers.HashCost.Time, browsers.HashCost.MemoryKiB, browsers.HashCost.Threads = 1, 64, 1
}

const testPassword = "correct horse battery"

func (e *tenv) setPassword(t *testing.T) {
	t.Helper()
	if m := e.call(t, "POST", "/api/password", map[string]string{"new": testPassword}, 200); m["password_set"] != true {
		t.Fatalf("%v", m)
	}
}

// A trusted browser that signed out (or whose session ran out) signs back in
// with the password, on the cookie it already has.
func TestSignBackInWithThePassword(t *testing.T) {
	e := newEnv(t)
	if m := e.call(t, "GET", "/api/session", nil, 200); m["password_set"] != false {
		t.Fatalf("session: %v", m)
	}
	e.setPassword(t)
	if m := e.call(t, "GET", "/api/session", nil, 200); m["password_set"] != true {
		t.Fatalf("session: %v", m)
	}

	resp, m := e.as(t, e.cookie, "POST", "/api/signout", nil)
	if resp.StatusCode != 200 || m["kept"] != true {
		t.Fatalf("sign out: %d %v", resp.StatusCode, m)
	}
	if c := sessionFrom(t, resp); c == nil || c.Value != e.cookie || c.MaxAge != int(browsers.KnownLife.Seconds()) {
		t.Fatalf("signing out with a password must keep the cookie, renewed: %+v", c)
	}
	if resp, _ := e.as(t, e.cookie, "GET", "/api/notes", nil); resp.StatusCode != 401 {
		t.Fatalf("notes after signing out: %d", resp.StatusCode)
	}

	for name, c := range map[string]struct {
		cookie string
		want   bool
	}{"this browser": {e.cookie, true}, "no cookie": {noCookie, false}, "a forged cookie": {"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", false}} {
		if resp, m := e.as(t, c.cookie, "GET", "/api/signin", nil); resp.StatusCode != 200 || m["password"] != c.want {
			t.Errorf("signin options for %s: %d %v", name, resp.StatusCode, m)
		}
	}

	if resp, m := e.as(t, e.cookie, "POST", "/api/signin", map[string]string{"password": "wrong password"}); resp.StatusCode != 400 || m["code"] != "wrong_password" {
		t.Fatalf("wrong password: %d %v", resp.StatusCode, m)
	}
	resp, m = e.as(t, e.cookie, "POST", "/api/signin", map[string]string{"password": testPassword})
	c := sessionFrom(t, resp)
	if resp.StatusCode != 200 || c == nil || c.Value != e.cookie || c.MaxAge != int(browsers.KnownLife.Seconds()) || !c.HttpOnly || c.SameSite != http.SameSiteStrictMode {
		t.Fatalf("right password: %d %v %+v", resp.StatusCode, m, c)
	}
	if resp, _ := e.as(t, e.cookie, "GET", "/api/notes", nil); resp.StatusCode != 200 {
		t.Fatalf("notes after signing back in: %d", resp.StatusCode)
	}
}

// The password never lets in a browser that isn't trusted, and the answer is
// the same as for a wrong password.
func TestThePasswordIsNoWayInWithoutATrustedBrowser(t *testing.T) {
	e := newEnv(t)
	for name, cookie := range map[string]string{"no cookie": noCookie, "a forged cookie": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"} {
		if resp, m := e.as(t, cookie, "POST", "/api/signin", map[string]string{"password": testPassword}); resp.StatusCode != 400 || m["code"] != "wrong_password" {
			t.Errorf("no password set, %s: %d %v", name, resp.StatusCode, m)
		}
	}
	e.setPassword(t)
	for name, cookie := range map[string]string{"no cookie": noCookie, "a forged cookie": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"} {
		resp, m := e.as(t, cookie, "POST", "/api/signin", map[string]string{"password": testPassword})
		if resp.StatusCode != 400 || m["code"] != "wrong_password" || sessionFrom(t, resp) != nil {
			t.Errorf("%s: %d %v", name, resp.StatusCode, m)
		}
	}
	if len(e.br.List()) != 1 {
		t.Fatal("a browser was added")
	}
}

func TestPasswordTriesAreLimited(t *testing.T) {
	e := newEnv(t)
	e.setPassword(t)
	other := e.joinNew(t, "Laptop")
	e.call(t, "POST", "/api/signout", nil, 200)
	for i := range browsers.MaxTries {
		if resp, _ := e.as(t, e.cookie, "POST", "/api/signin", map[string]string{"password": "wrong password"}); resp.StatusCode != 400 {
			t.Fatalf("try %d: %d", i+1, resp.StatusCode)
		}
	}
	resp, m := e.as(t, e.cookie, "POST", "/api/signin", map[string]string{"password": testPassword})
	if resp.StatusCode != 429 || m["code"] != "locked" {
		t.Fatalf("this browser after %d wrong: %d %v", browsers.MaxTries, resp.StatusCode, m)
	}

	// Failed tries from anywhere, links and passwords together, stop both for a minute.
	for range joinFailLimit - browsers.MaxTries {
		e.as(t, noCookie, "POST", "/api/join", map[string]string{"token": "guess", "name": "x"})
	}
	if resp, m := e.as(t, other, "POST", "/api/password", map[string]string{"current": testPassword, "new": "a new password"}); resp.StatusCode != 429 || m["code"] != "too_many_attempts" {
		t.Fatalf("change after %d failures: %d %v", joinFailLimit, resp.StatusCode, m)
	}
	timeNow = func() time.Time { return time.Now().Add(joinFailWindow) }
	t.Cleanup(func() { timeNow = time.Now })
	if resp, m := e.as(t, other, "POST", "/api/password", map[string]string{"current": testPassword, "new": "a new password"}); resp.StatusCode != 200 {
		t.Fatalf("a minute later: %d %v", resp.StatusCode, m)
	}
}

func TestChangingAndRemovingThePassword(t *testing.T) {
	e := newEnv(t)
	if resp, m := e.as(t, e.cookie, "POST", "/api/password", map[string]string{"new": "short"}); resp.StatusCode != 400 || m["code"] != "invalid" {
		t.Fatalf("too short: %d %v", resp.StatusCode, m)
	}
	e.setPassword(t)
	if resp, m := e.as(t, e.cookie, "POST", "/api/password", map[string]string{"new": "a new password"}); resp.StatusCode != 400 || m["code"] != "wrong_password" {
		t.Fatalf("changed without the current one: %d %v", resp.StatusCode, m)
	}
	if m := e.call(t, "POST", "/api/password", map[string]string{"current": testPassword, "new": ""}, 200); m["password_set"] != false {
		t.Fatalf("remove: %v", m)
	}
	// With no password, signing out removes this browser and its cookie.
	resp, m := e.as(t, e.cookie, "POST", "/api/signout", nil)
	if c := sessionFrom(t, resp); resp.StatusCode != 200 || m["kept"] != false || c == nil || c.MaxAge >= 0 {
		t.Fatalf("sign out: %d %v %+v", resp.StatusCode, m, c)
	}
	if len(e.br.List()) != 0 {
		t.Fatal("the browser was kept")
	}
}

// Other sites cannot make a browser sign in or change the password.
func TestPasswordStepsFromAnotherSiteAreRefused(t *testing.T) {
	e := newEnv(t)
	e.setPassword(t)
	for _, c := range []struct {
		path string
		body map[string]string
	}{
		{"/api/signin", map[string]string{"password": testPassword}},
		{"/api/password", map[string]string{"current": testPassword, "new": ""}},
		{"/api/signout", nil},
	} {
		if resp, _ := e.req(t, "POST", c.path, c.body, "Sec-Fetch-Site", "cross-site"); resp.StatusCode != 403 {
			t.Errorf("cross-site %s: %d", c.path, resp.StatusCode)
		}
	}
	if !e.br.HasPassword() {
		t.Fatal("a cross-site request removed the password")
	}
}

// Over HTTPS the sign-in sends the __Host- cookie, Secure.
func TestSignInOverHTTPS(t *testing.T) {
	e := newTLSEnv(t)
	e.setPassword(t)
	e.call(t, "POST", "/api/signout", nil, 200)
	resp, _ := e.as(t, e.cookie, "POST", "/api/signin", map[string]string{"password": testPassword})
	if c := sessionFrom(t, resp); resp.StatusCode != 200 || c == nil || c.Name != "__Host-"+sessionCookie || !c.Secure {
		t.Fatalf("%d %+v", resp.StatusCode, c)
	}
}
