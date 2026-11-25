package web

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func (e *tenv) noteCount(t *testing.T) int { return len(e.search(t, "")) }

func TestHostHeaderMustBeOurOwn(t *testing.T) {
	e := newEnv(t)
	n := 0
	port := e.host[strings.LastIndex(e.host, ":")+1:]
	for host, want := range map[string]int{
		e.host:                            200,
		"localhost:" + port:               200,
		"LOCALHOST:" + port:               200,
		"[::1]:" + port:                   200,
		"evil.example.com":                403, // DNS rebinding
		"evil.example.com:" + port:        403,
		"127.0.0.1:1":                     403, // another port on this machine
		"localhost":                       403,
		"127.0.0.1." + "evil.com:" + port: 403,
	} {
		if resp, _ := e.req(t, "GET", "/api/folders", nil, "Host", host); resp.StatusCode != want {
			t.Errorf("GET with Host %q: %d, want %d", host, resp.StatusCode, want)
		}
		// A different note path per host, so a 409 cannot hide a result.
		n++
		path := fmt.Sprintf("h%d.md", n)
		if resp, _ := e.req(t, "POST", "/api/notes", map[string]string{"path": path, "text": "x"}, "Host", host); (resp.StatusCode == 201) != (want == 200) {
			t.Errorf("POST with Host %q: %d", host, resp.StatusCode)
		}
	}
}

func TestWritesNeedJSONAndASameOrigin(t *testing.T) {
	body := map[string]string{"path": "w.md", "text": "x"}
	for name, c := range map[string]struct {
		hdr  func(host string) []string
		want int
	}{
		"plain":               {func(h string) []string { return nil }, 201},
		"own origin":          {func(h string) []string { return []string{"Origin", "http://" + h} }, 201},
		"same-origin fetch":   {func(h string) []string { return []string{"Origin", "http://" + h, "Sec-Fetch-Site", "same-origin"} }, 201},
		"direct navigation":   {func(h string) []string { return []string{"Sec-Fetch-Site", "none"} }, 201},
		"json with charset":   {func(h string) []string { return []string{"Content-Type", "application/json; charset=utf-8"} }, 201},
		"foreign origin":      {func(h string) []string { return []string{"Origin", "http://evil.example.com"} }, 403},
		"foreign https":       {func(h string) []string { return []string{"Origin", "https://" + h} }, 403},
		"null origin":         {func(h string) []string { return []string{"Origin", "null"} }, 403},
		"garbage origin":      {func(h string) []string { return []string{"Origin", "::::"} }, 403},
		"cross-site fetch":    {func(h string) []string { return []string{"Sec-Fetch-Site", "cross-site"} }, 403},
		"same-site fetch":     {func(h string) []string { return []string{"Sec-Fetch-Site", "same-site"} }, 403},
		"text/plain (a form)": {func(h string) []string { return []string{"Content-Type", "text/plain"} }, 415},
		"form encoded":        {func(h string) []string { return []string{"Content-Type", "application/x-www-form-urlencoded"} }, 415},
		"multipart":           {func(h string) []string { return []string{"Content-Type", "multipart/form-data; boundary=x"} }, 415},
		"no content type":     {func(h string) []string { return []string{"Content-Type", ""} }, 415},
	} {
		e := newEnv(t) // a clean vault each time, to see whether anything was written
		resp, b := e.req(t, "POST", "/api/notes", body, c.hdr(e.host)...)
		if resp.StatusCode != c.want {
			t.Errorf("%s: %d, want %d (%s)", name, resp.StatusCode, c.want, b)
		}
		if (e.noteCount(t) == 1) != (c.want == 201) {
			t.Errorf("%s: a refused request changed something, or an allowed one did not", name)
		}
	}
}

func TestEveryWriteMethodIsGuarded(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "keep")
	for _, c := range []struct{ method, path string }{
		{"POST", "/api/notes"}, {"PUT", "/api/notes/" + id}, {"POST", "/api/notes/" + id + "/move"},
		{"POST", "/api/notes/" + id + "/trash"}, {"PUT", "/api/notes/" + id + "/history-enabled"},
		{"POST", "/api/trash/" + id + "/restore"}, {"POST", "/api/trash/" + id + "/delete-forever"},
		{"POST", "/api/maid/run"}, {"PUT", "/api/settings/hub"}, {"PUT", "/api/settings/device"},
		{"POST", "/api/notes/" + id + "/history/1234567890123/restore"},
	} {
		for _, hdr := range [][]string{{"Origin", "http://evil.example.com"}, {"Content-Type", "text/plain"}, {"Host", "evil.example.com"}} {
			if resp, _ := e.req(t, c.method, c.path, `{}`, hdr...); resp.StatusCode != 403 && resp.StatusCode != 415 {
				t.Errorf("%s %s with %v: %d", c.method, c.path, hdr, resp.StatusCode)
			}
		}
	}
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["text"] != "keep" || e.noteCount(t) != 1 {
		t.Fatal("a refused request changed something")
	}
}

func TestNoCORSAtAll(t *testing.T) {
	e := newEnv(t)
	for _, c := range []struct{ method, path string }{{"GET", "/api/folders"}, {"GET", "/"}, {"OPTIONS", "/api/notes"}, {"GET", "/nope"}} {
		resp, _ := e.req(t, c.method, c.path, nil, "Origin", "http://evil.example.com", "Access-Control-Request-Method", "POST")
		for k := range resp.Header {
			if strings.HasPrefix(strings.ToLower(k), "access-control-") {
				t.Errorf("%s %s sent %s", c.method, c.path, k)
			}
		}
	}
	// A preflight is refused (403 from the origin check, or 405 without one).
	for _, hdr := range [][]string{{"Origin", "http://evil.example.com"}, {"Origin", ""}} {
		if resp, _ := e.req(t, "OPTIONS", "/api/notes", nil, hdr...); resp.StatusCode != 403 && resp.StatusCode != 405 {
			t.Errorf("preflight with %v got %d", hdr, resp.StatusCode)
		}
	}
}

func TestSecurityHeadersOnEveryResponse(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "x")
	for _, c := range []struct {
		method, path string
		want         int
	}{
		{"GET", "/", 200}, {"GET", "/api/folders", 200}, {"GET", "/api/notes/" + id, 200}, {"GET", "/api/notes/nope", 404},
		{"GET", "/nope", 404}, {"POST", "/api/notes", 400}, {"DELETE", "/api/notes/" + id, 405},
	} {
		resp, _ := e.req(t, c.method, c.path, "{", "Host", e.host)
		if resp.StatusCode != c.want {
			t.Errorf("%s %s: %d", c.method, c.path, resp.StatusCode)
		}
		csp := resp.Header.Get("Content-Security-Policy")
		for _, part := range []string{"default-src 'none'", "script-src 'self'", "frame-ancestors 'none'", "form-action 'none'"} {
			if !strings.Contains(csp, part) {
				t.Errorf("%s %s: CSP lacks %q: %q", c.method, c.path, part, csp)
			}
		}
		if resp.Header.Get("X-Content-Type-Options") != "nosniff" || resp.Header.Get("Referrer-Policy") != "no-referrer" ||
			resp.Header.Get("X-Frame-Options") != "DENY" || resp.Header.Get("Cache-Control") != "no-store" {
			t.Errorf("%s %s: headers %v", c.method, c.path, resp.Header)
		}
	}
	// Refused requests carry the headers too.
	resp, _ := e.req(t, "GET", "/api/folders", nil, "Host", "evil.example.com")
	if resp.StatusCode != 403 || resp.Header.Get("Content-Security-Policy") == "" {
		t.Errorf("403 response: %d %v", resp.StatusCode, resp.Header)
	}
}

func TestMalformedBodies(t *testing.T) {
	e := newEnv(t)
	for name, body := range map[string]string{
		"unknown field":   `{"path":"a.md","text":"x","admin":true}`,
		"trailing object": `{"path":"a.md","text":"x"} {"path":"b.md"}`,
		"trailing junk":   `{"path":"a.md","text":"x"} garbage`,
		"not json":        `path=a.md`,
		"wrong type":      `{"path":5,"text":"x"}`,
		"array":           `[]`,
		"truncated":       `{"path":"a.md"`,
	} {
		resp, b := e.req(t, "POST", "/api/notes", body)
		if resp.StatusCode != 400 {
			t.Errorf("%s: %d %s", name, resp.StatusCode, b)
		}
	}
	if e.noteCount(t) != 0 {
		t.Fatal("a malformed request created a note")
	}
	if resp, _ := e.req(t, "POST", "/api/notes", ``); resp.StatusCode != 400 { // empty body: empty path is refused
		t.Errorf("empty body: %d", resp.StatusCode)
	}
}

func TestOversizedBodyIsRefusedAndChangesNothing(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "keep")
	ver := e.call(t, "GET", "/api/notes/"+id, nil, 200)["version"].(string)
	big := `{"base":"` + ver + `","text":"` + strings.Repeat("a", MaxBody+10) + `"}`
	resp, _ := e.req(t, "PUT", "/api/notes/"+id, big)
	if resp.StatusCode != 413 {
		t.Fatalf("got %d", resp.StatusCode)
	}
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["text"] != "keep" {
		t.Fatal("note changed")
	}
	// Just under the limit still works.
	ok := `{"base":"` + ver + `","text":"` + strings.Repeat("a", MaxBody-1024) + `"}`
	if resp, b := e.req(t, "PUT", "/api/notes/"+id, ok); resp.StatusCode != 200 {
		t.Fatalf("under the limit: %d %s", resp.StatusCode, b)
	}
}

func TestAPanicBecomesAPlainServerError(t *testing.T) {
	// No dependencies at all: any handler that touches them panics.
	ts := httptest.NewUnstartedServer(nil)
	addr := ts.Listener.Addr().String()
	ts.Config.Handler = New(Deps{Addr: addr, Logf: t.Logf})
	ts.Start()
	defer ts.Close()
	resp, err := http.Get(ts.URL + "/api/folders")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var buf strings.Builder
	b := make([]byte, 4096)
	n, _ := resp.Body.Read(b)
	buf.Write(b[:n])
	if resp.StatusCode != 500 || strings.Contains(buf.String(), "nil pointer") || strings.Contains(buf.String(), ".go") {
		t.Fatalf("%d %q", resp.StatusCode, buf.String())
	}
}

func TestErrorsDoNotLeakInternals(t *testing.T) {
	e := newEnv(t)
	for _, p := range []string{"/api/notes/zzz", "/api/notes/zzz/history", "/api/trash/zzz/restore"} {
		_, b := e.req(t, "GET", p, nil)
		if strings.Contains(string(b), e.root) || strings.Contains(string(b), "/tmp") {
			t.Errorf("%s leaked a path: %s", p, b)
		}
	}
}
