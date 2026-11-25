package web

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"rsc.io/qr"

	"styli/core/internal/browsers"
)

// sessionCookie carries a trusted browser's session secret. HttpOnly keeps it
// from the page's scripts, SameSite=Strict from requests other sites start.
// Over HTTPS it is __Host-styli_session and Secure: never sent without TLS,
// and only to this exact host. Plain http is loopback only, where neither can
// be set.
const sessionCookie = "styli_session"

func cookieName(r *http.Request) string {
	if r.TLS != nil {
		return "__Host-" + sessionCookie
	}
	return sessionCookie
}

// Failed joins (an unknown, used or expired link) allowed per minute, from
// anyone, before joining is refused for a while. A link is 256 random bits, so
// this is a second wall, not the first.
const (
	joinFailLimit  = 10
	joinFailWindow = time.Minute
)

// timeNow is the join limit's clock; tests move it.
var timeNow = time.Now

type browserKey struct{}

// thisBrowser is the ID of the browser making the request ("" when the server
// keeps no browser list, as on a device).
func thisBrowser(r *http.Request) string {
	id, _ := r.Context().Value(browserKey{}).(string)
	return id
}

// auth lets a request into the API only from a signed-in browser, when this
// server keeps a browser list (the atrium). The page and its static files are
// open: they hold no notes, and the page shows how to sign in. Joining is the
// one API call that needs no session.
func (s *server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.Browsers == nil || !strings.HasPrefix(r.URL.Path, "/api/") || (r.Method == http.MethodPost && r.URL.Path == "/api/join") {
			next.ServeHTTP(w, r)
			return
		}
		var secret string
		if c, err := r.Cookie(cookieName(r)); err == nil {
			secret = c.Value
		}
		b, renewed, ok := s.Browsers.Check(secret)
		if !ok {
			fail(w, http.StatusUnauthorized, "login_required", "This browser isn't signed in to your atrium.")
			return
		}
		if renewed {
			setSession(w, r, secret)
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), browserKey{}, b.ID)))
	})
}

func setSession(w http.ResponseWriter, r *http.Request, secret string) {
	http.SetCookie(w, &http.Cookie{Name: cookieName(r), Value: secret, Path: "/", MaxAge: int(browsers.SessionLife.Seconds()),
		HttpOnly: true, Secure: r.TLS != nil, SameSite: http.SameSiteStrictMode})
}

func clearSession(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: cookieName(r), Value: "", Path: "/", MaxAge: -1,
		HttpOnly: true, Secure: r.TLS != nil, SameSite: http.SameSiteStrictMode})
}

// joinLimit counts failed joins in the last joinFailWindow.
type joinLimit struct {
	mu    sync.Mutex
	fails []time.Time
}

func (l *joinLimit) blocked(now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	kept := l.fails[:0]
	for _, t := range l.fails {
		if now.Sub(t) < joinFailWindow {
			kept = append(kept, t)
		}
	}
	l.fails = kept
	return len(l.fails) >= joinFailLimit
}

func (l *joinLimit) fail(now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.fails = append(l.fails, now)
}

type browserDTO struct {
	ID       string    `json:"id"`
	Name     string    `json:"name"`
	AddedAt  time.Time `json:"added_at"`
	LastSeen time.Time `json:"last_seen"`
	This     bool      `json:"this"` // the browser asking
}

func (s *server) browserList(r *http.Request) []browserDTO {
	me := thisBrowser(r)
	out := []browserDTO{}
	for _, b := range s.Browsers.List() {
		out = append(out, browserDTO{b.ID, b.Name, b.AddedAt, b.LastSeen, b.ID == me})
	}
	return out
}

// notAtrium answers for the browser endpoints on a device, which keeps no
// browser list (its page is for this machine only).
func (s *server) notAtrium(w http.ResponseWriter) bool {
	if s.Browsers != nil {
		return false
	}
	fail(w, http.StatusNotFound, "not_atrium", "Only the atrium signs browsers in.")
	return true
}

// getSession says which browser this is; the page asks first, and shows the
// sign-in screen on 401.
func (s *server) getSession(w http.ResponseWriter, r *http.Request) {
	if s.notAtrium(w) {
		return
	}
	for _, b := range s.browserList(r) {
		if b.This {
			writeJSON(w, http.StatusOK, map[string]any{"browser": b})
			return
		}
	}
	fail(w, http.StatusUnauthorized, "login_required", "This browser isn't signed in to your atrium.")
}

// join trusts a new browser through a one-time link and signs it in.
func (s *server) join(w http.ResponseWriter, r *http.Request) {
	if s.notAtrium(w) {
		return
	}
	var req struct {
		Token string `json:"token"`
		Name  string `json:"name"`
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	now := timeNow()
	if s.joins.blocked(now) {
		fail(w, http.StatusTooManyRequests, "too_many_attempts", "Too many links that didn't work. Wait a minute, then try again.")
		return
	}
	b, secret, err := s.Browsers.Redeem(req.Token, req.Name)
	switch {
	case errors.Is(err, browsers.ErrBadLink):
		s.joins.fail(now)
		fail(w, http.StatusBadRequest, "bad_link", "This link has expired or was already used. Make a new one on a browser that's signed in: Settings, Browsers, Add a browser.")
		return
	case errors.Is(err, browsers.ErrBadName):
		fail(w, http.StatusBadRequest, "invalid", "Give this browser a name of up to 60 characters.")
		return
	case err != nil:
		s.failErr(w, err)
		return
	}
	s.Logf("web: a new browser was signed in: %q", b.Name)
	setSession(w, r, secret)
	writeJSON(w, http.StatusCreated, map[string]any{"browser": browserDTO{b.ID, b.Name, b.AddedAt, b.LastSeen, true}})
}

func (s *server) listBrowsers(w http.ResponseWriter, r *http.Request) {
	if s.notAtrium(w) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"browsers": s.browserList(r)})
}

// newBrowserLink makes a one-time link for another browser, as an address and
// a QR code. The address uses the name this browser reached the atrium by,
// which the guard has already checked.
func (s *server) newBrowserLink(w http.ResponseWriter, r *http.Request) {
	if s.notAtrium(w) {
		return
	}
	secret, expires, err := s.Browsers.NewLink(browsers.LinkLife)
	if err != nil {
		s.failErr(w, err)
		return
	}
	host := s.LinkHost
	if host == "" {
		host = r.Host
	}
	url := JoinURL(r.TLS != nil, host, secret)
	svg, err := qrSVG(url)
	if err != nil {
		s.failErr(w, err)
		return
	}
	out := map[string]any{"url": url, "qr_svg": svg, "expires_at": expires}
	if s.CA != nil {
		// The other device installs the atrium's certificate first, once.
		caURL := scheme(r) + "://" + host + "/ca.crt"
		caSVG, err := qrSVG(caURL)
		if err != nil {
			s.failErr(w, err)
			return
		}
		out["ca"] = map[string]any{"url": caURL, "qr_svg": caSVG, "fingerprint": s.CA.Fingerprint}
	}
	writeJSON(w, http.StatusCreated, out)
}

func (s *server) removeBrowser(w http.ResponseWriter, r *http.Request) {
	if s.notAtrium(w) {
		return
	}
	id := r.PathValue("id")
	if err := s.Browsers.Remove(id); errors.Is(err, browsers.ErrNotFound) {
		fail(w, http.StatusNotFound, "not_found", "That browser wasn't found.")
		return
	} else if err != nil {
		s.failErr(w, err)
		return
	}
	self := id == thisBrowser(r)
	if self {
		clearSession(w, r)
	}
	writeJSON(w, http.StatusOK, map[string]any{"browsers": s.browserList(r), "signed_out": self})
}

// JoinURL is the address a new browser opens (https is how it reaches the atrium). The secret is in the part after
// "#", which browsers never send to a server, so it stays out of logs and
// Referer headers; the page reads it and posts it.
func JoinURL(https bool, host, secret string) string {
	scheme := "http"
	if https {
		scheme = "https"
	}
	return scheme + "://" + host + "/#/join/" + secret
}

// qrSVG draws text as a QR code. It is black on white whatever the theme:
// that is what phone cameras read best.
func qrSVG(text string) (string, error) {
	c, err := qr.Encode(text, qr.M)
	if err != nil {
		return "", err
	}
	const quiet = 4 // the blank border scanners need
	n := c.Size + 2*quiet
	var b strings.Builder
	fmt.Fprintf(&b, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges"><rect width="%d" height="%d" fill="#fff"/><path fill="#000" d="`, n, n, n, n)
	for y := 0; y < c.Size; y++ {
		for x := 0; x < c.Size; x++ {
			if c.Black(x, y) {
				fmt.Fprintf(&b, "M%d %dh1v1h-1z", x+quiet, y+quiet)
			}
		}
	}
	b.WriteString(`"/></svg>`)
	return b.String(), nil
}
