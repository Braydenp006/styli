package web

import (
	"io/fs"
	"net/url"
	"path"
	"regexp"
	"strings"
	"testing"
)

func TestRenderEndpoint(t *testing.T) {
	e := newEnv(t)
	r := e.call(t, "POST", "/api/render", map[string]string{"text": "# Title\n\nSolve $x_1^2$.\n\n| a |\n|---|\n| 1 |\n\n<script>alert(1)</script>"}, 200)
	html := r["html"].(string)
	for _, want := range []string{"<h1>Title</h1>", `<span class="math math-inline">x_1^2</span>`, "<table>"} {
		if !strings.Contains(html, want) {
			t.Errorf("missing %q in %s", want, html)
		}
	}
	if strings.Contains(html, "<script") {
		t.Errorf("script survived: %s", html)
	}
	if got := e.call(t, "POST", "/api/render", map[string]string{"text": ""}, 200)["html"]; got != "" {
		t.Errorf("empty text rendered %q", got)
	}
	e.call(t, "POST", "/api/render", map[string]any{"text": 5}, 400)
	e.call(t, "POST", "/api/render", map[string]any{"text": "x", "extra": 1}, 400)
	if resp, _ := e.req(t, "POST", "/api/render", `{"text":"x"}`, "Content-Type", "text/plain"); resp.StatusCode != 415 {
		t.Errorf("text/plain: %d", resp.StatusCode)
	}
	if resp, _ := e.req(t, "POST", "/api/render", `{"text":"x"}`, "Origin", "http://evil.example.com"); resp.StatusCode != 403 {
		t.Errorf("foreign origin: %d", resp.StatusCode)
	}
	big := `{"text":"` + strings.Repeat("a", MaxBody+10) + `"}`
	if resp, _ := e.req(t, "POST", "/api/render", big); resp.StatusCode != 413 {
		t.Errorf("oversized: %d", resp.StatusCode)
	}
}

func TestIndexPageIsServed(t *testing.T) {
	e := newEnv(t)
	resp, b := e.req(t, "GET", "/", nil)
	page := string(b)
	if resp.StatusCode != 200 || !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/html") {
		t.Fatalf("%d %v", resp.StatusCode, resp.Header)
	}
	for _, want := range []string{"<title>Styli</title>", `type="module" src="/static/js/app.js"`, `id="app"`, `lang="en"`, "viewport"} {
		if !strings.Contains(page, want) {
			t.Errorf("index.html lacks %q", want)
		}
	}
	if strings.Contains(page, "<script>") || regexp.MustCompile(`\son[a-z]+=`).MatchString(page) || strings.Contains(page, "style=") {
		t.Error("index.html has an inline script, event handler or style, which the CSP would block")
	}
}

// Everything the page references must exist, must be served with a sensible
// type, and must live on the atrium itself.
func TestEveryReferencedFileExistsAndNothingLeavesTheHub(t *testing.T) {
	e := newEnv(t)
	seen := map[string]bool{}
	var visit func(p string)
	visit = func(p string) {
		if seen[p] {
			return
		}
		seen[p] = true
		resp, body := e.req(t, "GET", p, nil)
		if resp.StatusCode != 200 {
			t.Errorf("%s: %d", p, resp.StatusCode)
			return
		}
		ct := resp.Header.Get("Content-Type")
		want := map[string]string{".css": "text/css", ".js": "text/javascript", ".woff2": "font/woff2", ".svg": "image/svg+xml", ".html": "text/html"}[path.Ext(p)]
		if p == "/" {
			want = "text/html"
		}
		if want != "" && !strings.HasPrefix(ct, want) {
			t.Errorf("%s: content type %q, want %q", p, ct, want)
		}
		text := string(body)
		var refs []string
		switch {
		case p == "/":
			for _, m := range regexp.MustCompile(`(?:href|src|srcset)="([^"]+)"`).FindAllStringSubmatch(text, -1) {
				if !strings.HasPrefix(m[1], "#") {
					refs = append(refs, m[1])
				}
			}
		case strings.HasSuffix(p, ".css"):
			for _, m := range regexp.MustCompile(`url\(([^)]+)\)`).FindAllStringSubmatch(text, -1) {
				ref := strings.Trim(m[1], `"' `)
				// KaTeX lists .woff and .ttf after .woff2 as fallbacks that no current browser fetches.
				if strings.HasPrefix(p, "/static/vendor/katex/") && !strings.HasSuffix(ref, ".woff2") {
					continue
				}
				refs = append(refs, ref)
			}
		case strings.HasPrefix(p, "/static/js/"):
			// Only real import lines: the words "from" and a quote also appear in text shown on screen.
			for _, m := range regexp.MustCompile(`(?m)^import\b[^\n]*?\bfrom\s+"([^"]+)"`).FindAllStringSubmatch(text, -1) {
				refs = append(refs, m[1])
			}
		}
		// Our own files (not the vendored libraries) must not mention other sites.
		if !strings.HasPrefix(p, "/static/vendor/") && p != "/static/img/styli-mark-light-bg.svg" && p != "/static/img/styli-mark-dark-bg.svg" {
			for _, m := range regexp.MustCompile(`https?://[^\s"')]+`).FindAllString(text, -1) {
				if m == "http://www.w3.org/2000/svg" {
					continue // the XML namespace name the browser needs to make an SVG icon; nothing is fetched from it
				}
				t.Errorf("%s mentions %s", p, m)
			}
		}
		for _, ref := range refs {
			u, err := url.Parse(ref)
			if err != nil || u.Scheme != "" || u.Host != "" {
				t.Errorf("%s references %q, which is not a file on the atrium", p, ref)
				continue
			}
			if strings.HasPrefix(ref, "./") || !strings.HasPrefix(ref, "/") {
				ref = path.Join(path.Dir(p), ref)
			}
			visit(path.Clean(ref))
		}
	}
	visit("/")
	for _, must := range []string{"/static/css/app.css", "/static/js/app.js", "/static/js/api.js", "/static/vendor/katex/katex.min.js", "/static/fonts/inter-latin-400-normal.woff2", "/static/fonts/marcellus-latin-400-normal.woff2"} {
		if !seen[must] {
			t.Errorf("%s is not reachable from the page", must)
		}
	}
}

func TestStaticServerShowsNoDirectoriesAndNoTraversal(t *testing.T) {
	e := newEnv(t)
	for _, p := range []string{"/static/", "/static", "/static/css/", "/static/fonts/", "/static/vendor/", "/static/nope.css",
		"/static/../index.html", "/static/%2e%2e/index.html", "/static/css/../../ui.go", "/static/..%2fui.go", "/static//etc/passwd", "/index.html", "/ui/index.html"} {
		resp, b := e.req(t, "GET", p, nil)
		if resp.StatusCode == 200 || strings.Contains(string(b), "package web") {
			t.Errorf("GET %s: %d", p, resp.StatusCode)
		}
	}
	if resp, _ := e.req(t, "POST", "/static/css/app.css", `{}`); resp.StatusCode == 200 {
		t.Errorf("POST to a static file: %d", resp.StatusCode)
	}
}

func TestStaticFilesCarryTheSecurityHeaders(t *testing.T) {
	e := newEnv(t)
	for _, p := range []string{"/", "/static/js/app.js", "/static/css/app.css", "/static/fonts/inter-latin-400-normal.woff2"} {
		resp, _ := e.req(t, "GET", p, nil)
		if resp.Header.Get("Content-Security-Policy") == "" || resp.Header.Get("X-Content-Type-Options") != "nosniff" {
			t.Errorf("%s: %v", p, resp.Header)
		}
	}
	if _, err := fs.Stat(uiFiles, "ui/static/vendor/VENDOR.md"); err != nil {
		t.Error("VENDOR.md should ship with the vendored files")
	}
}
