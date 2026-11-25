package web

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"styli/core/internal/devices"
	"styli/core/internal/maid"
	"styli/core/internal/pairing"
	"styli/core/internal/settings"
	"styli/core/internal/store"
	"styli/core/internal/syncdev"
	"styli/core/internal/vault"
)

type tenv struct {
	ts   *httptest.Server
	v    *vault.Vault
	st   *store.Store
	set  *settings.Store
	root string
	host string
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err) // a test panic fails that test with the error shown
	}
	return v
}

func newEnv(t *testing.T) *tenv {
	t.Helper()
	e := &tenv{root: t.TempDir()}
	e.v = must(vault.Open(e.root))
	e.set = must(settings.Open(e.root, t.TempDir()))
	must(0, e.set.ApplyTo(e.v))
	e.st = must(store.Open(t.TempDir()))
	t.Cleanup(func() { e.st.Close() })
	m := maid.New(e.v, e.set, t.Logf)
	e.ts = httptest.NewUnstartedServer(nil)
	e.host = e.ts.Listener.Addr().String()
	e.ts.Config.Handler = New(Deps{Vault: e.v, Store: e.st, Settings: e.set, Maid: m, Logf: t.Logf, Addr: e.host})
	e.ts.Start()
	t.Cleanup(e.ts.Close)
	return e
}

// req sends a request. A string body is sent as is, anything else as JSON.
// hdr is name/value pairs; "Host" sets the Host header, and an empty value
// removes a header.
func (e *tenv) req(t *testing.T, method, path string, body any, hdr ...string) (*http.Response, []byte) {
	t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	default:
		rd = bytes.NewReader(must(json.Marshal(b)))
	}
	r, err := http.NewRequest(method, e.ts.URL+path, rd)
	if err != nil {
		t.Fatal(err)
	}
	if method != http.MethodGet && method != http.MethodHead {
		r.Header.Set("Content-Type", "application/json")
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		switch {
		case hdr[i] == "Host":
			r.Host = hdr[i+1]
		case hdr[i+1] == "":
			r.Header.Del(hdr[i])
		default:
			r.Header.Set(hdr[i], hdr[i+1])
		}
	}
	resp, err := http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	return resp, must(io.ReadAll(resp.Body))
}

func (e *tenv) call(t *testing.T, method, path string, body any, wantStatus int) map[string]any {
	t.Helper()
	resp, b := e.req(t, method, path, body)
	if resp.StatusCode != wantStatus {
		t.Fatalf("%s %s: status %d, want %d: %s", method, path, resp.StatusCode, wantStatus, b)
	}
	var m map[string]any
	if len(b) > 0 && resp.Header.Get("Content-Type") != "" && strings.Contains(resp.Header.Get("Content-Type"), "json") {
		if err := json.Unmarshal(b, &m); err != nil {
			t.Fatalf("bad JSON %q: %v", b, err)
		}
	}
	return m
}

func (e *tenv) create(t *testing.T, path, text string) string {
	t.Helper()
	return e.call(t, "POST", "/api/notes", map[string]string{"path": path, "text": text}, 201)["id"].(string)
}

func (e *tenv) search(t *testing.T, query string) []any {
	t.Helper()
	return e.call(t, "GET", "/api/notes?"+query, nil, 200)["notes"].([]any)
}

func TestCreateReadSaveAndTheIndexFollows(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "Net/tcp.md", "# TCP\nhandshake")
	if got := e.search(t, "q=handshake"); len(got) != 1 || got[0].(map[string]any)["id"] != id {
		t.Fatalf("search: %v", got)
	}
	n := e.call(t, "GET", "/api/notes/"+id, nil, 200)
	if n["title"] != "TCP" || n["modified"] == "" || len(n["tags"].([]any)) != 0 {
		t.Fatalf("index info missing: %v", n)
	}
	if n["text"] != "# TCP\nhandshake" || n["path"] != "Net/tcp.md" || n["has_note"] != true || n["history_enabled"] != true {
		t.Fatalf("%v", n)
	}
	r := e.call(t, "PUT", "/api/notes/"+id, map[string]string{"text": "# TCP\nsyn ack", "base": n["version"].(string)}, 200)
	if r["version"] == n["version"] {
		t.Fatal("version should change")
	}
	if len(e.search(t, "q=handshake")) != 0 || len(e.search(t, "q=syn")) != 1 {
		t.Fatal("index did not follow the save")
	}
}

func TestStaleSaveIsAConflictAndChangesNothing(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "v1")
	base := e.call(t, "GET", "/api/notes/"+id, nil, 200)["version"]
	e.call(t, "PUT", "/api/notes/"+id, map[string]any{"text": "v2", "base": base}, 200)
	m := e.call(t, "PUT", "/api/notes/"+id, map[string]any{"text": "mine", "base": base}, 409)
	if m["code"] != "conflict" {
		t.Fatalf("%v", m)
	}
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["text"] != "v2" {
		t.Fatal("stale save changed the note")
	}
}

func TestSaveWithKeepOldKeepsTheReplacedVersion(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "first")
	get := func() map[string]any { return e.call(t, "GET", "/api/notes/"+id, nil, 200) }
	e.call(t, "PUT", "/api/notes/"+id, map[string]any{"text": "theirs", "base": get()["version"]}, 200) // folds into the first version
	if n := len(e.call(t, "GET", "/api/notes/"+id+"/history", nil, 200)["versions"].([]any)); n != 1 {
		t.Fatalf("expected the save to fold, have %d versions", n)
	}
	e.call(t, "PUT", "/api/notes/"+id, map[string]any{"text": "mine", "base": get()["version"], "keep_old": true}, 200)
	vs := e.call(t, "GET", "/api/notes/"+id+"/history", nil, 200)["versions"].([]any)
	if len(vs) != 2 {
		t.Fatalf("versions: %v", vs)
	}
	old := e.call(t, "GET", "/api/notes/"+id+"/history/"+vs[1].(map[string]any)["id"].(string), nil, 200)["text"]
	if old != "theirs" || get()["text"] != "mine" {
		t.Fatalf("old %v, now %v", old, get()["text"])
	}
	e.call(t, "PUT", "/api/notes/"+id, map[string]any{"text": "x", "base": "stale", "keep_old": true}, 409)
	e.call(t, "PUT", "/api/notes/"+id, map[string]any{"text": "x", "base": "b", "keep_old": "yes"}, 400)
}

func TestCreateErrors(t *testing.T) {
	e := newEnv(t)
	e.create(t, "a.md", "x")
	for name, c := range map[string]struct {
		path string
		want int
	}{"duplicate": {"a.md", 409}, "not markdown": {"a.txt", 400}, "traversal": {"../x.md", 400}, "empty": {"", 400}, "ids folder": {".ids/x.md", 400}} {
		e.call(t, "POST", "/api/notes", map[string]string{"path": c.path, "text": "x"}, c.want)
		_ = name
	}
}

func TestMoveTrashRestoreDeleteForever(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "a/n.md", "# Note\nquince")
	other := e.create(t, "b/o.md", "x")

	if p := e.call(t, "POST", "/api/notes/"+id+"/move", map[string]string{"folder": "c"}, 200)["path"]; p != "c/n.md" {
		t.Fatalf("path %v", p)
	}
	if len(e.search(t, "folder=c")) != 1 || len(e.search(t, "folder=a")) != 0 {
		t.Fatal("index did not follow the move")
	}

	e.call(t, "POST", "/api/notes/"+id+"/trash", nil, 204)
	e.call(t, "GET", "/api/notes/"+id, nil, 404)
	if len(e.search(t, "q=quince")) != 0 {
		t.Fatal("trashed note still searchable")
	}
	tr := e.call(t, "GET", "/api/trash", nil, 200)["trash"].([]any)
	if len(tr) != 1 || tr[0].(map[string]any)["id"] != id || tr[0].(map[string]any)["note_path"] != "c/n.md" {
		t.Fatalf("%v", tr)
	}

	// A name is taken: the restore is refused and nothing moves.
	e.create(t, "c/n.md", "someone else")
	e.call(t, "POST", "/api/trash/"+id+"/restore", map[string]string{}, 409)
	e.call(t, "POST", "/api/trash/"+id+"/restore", map[string]string{"folder": "d"}, 204)
	if len(e.search(t, "q=quince")) != 1 || e.call(t, "GET", "/api/notes/"+id, nil, 200)["path"] != "d/n.md" {
		t.Fatal("restore")
	}

	e.call(t, "POST", "/api/notes/"+id+"/trash", nil, 204)
	e.call(t, "POST", "/api/trash/"+id+"/delete-forever", nil, 204)
	e.call(t, "POST", "/api/trash/"+id+"/delete-forever", nil, 404)
	e.call(t, "POST", "/api/trash/"+id+"/restore", map[string]string{}, 404)
	if len(e.call(t, "GET", "/api/trash", nil, 200)["trash"].([]any)) != 0 {
		t.Fatal("trash not empty")
	}
	e.call(t, "GET", "/api/notes/"+other, nil, 200) // untouched
}

func TestBrowseAndSearchParameters(t *testing.T) {
	e := newEnv(t)
	e.create(t, "a/one.md", "# One\n<!-- styli:tags tcp, net -->")
	e.create(t, "a/b/two.md", "# Two\n<!-- styli:tags tcp -->")
	e.create(t, "three.md", "# Three")
	if len(e.search(t, "")) != 3 || len(e.search(t, "folder=a")) != 2 || len(e.search(t, "tag=tcp&tag=net")) != 1 {
		t.Fatal("filters")
	}
	if len(e.search(t, "limit=1")) != 1 || len(e.search(t, "limit=1&offset=2")) != 1 || len(e.search(t, "offset=3")) != 0 {
		t.Fatal("paging")
	}
	today := "2099-01-01"
	if len(e.search(t, "from="+today)) != 0 || len(e.search(t, "to="+today)) != 3 {
		t.Fatal("dates")
	}
	for _, q := range []string{"limit=x", "offset=y", "from=yesterday", "to=2026-13-45"} {
		e.call(t, "GET", "/api/notes?"+q, nil, 400)
	}
	for _, q := range []string{`q=%22`, `q=-`, `q=AND`, `q=(`, `q=*`} {
		e.call(t, "GET", "/api/notes?"+q, nil, 200)
	}
	if f := e.call(t, "GET", "/api/folders", nil, 200)["folders"].([]any); len(f) != 2 || f[0] != "a" || f[1] != "a/b" {
		t.Fatalf("folders %v", f)
	}
	if tg := e.call(t, "GET", "/api/tags", nil, 200)["tags"].([]any); len(tg) != 2 || tg[0].(map[string]any)["tag"] != "tcp" || tg[0].(map[string]any)["count"] != 2.0 {
		t.Fatalf("tags %v", tg)
	}
}

func TestHistoryEndpoints(t *testing.T) {
	e := newEnv(t)
	e.call(t, "PUT", "/api/settings/hub", map[string]any{"history": map[string]any{"window_minutes": 0}}, 200)
	id := e.create(t, "n.md", "first")
	save := func(text string) {
		v := e.call(t, "GET", "/api/notes/"+id, nil, 200)["version"]
		e.call(t, "PUT", "/api/notes/"+id, map[string]any{"text": text, "base": v}, 200)
	}
	save("second")
	save("third")
	vs := e.call(t, "GET", "/api/notes/"+id+"/history", nil, 200)["versions"].([]any)
	if len(vs) != 3 {
		t.Fatalf("%v", vs)
	}
	oldest := vs[2].(map[string]any)["id"].(string)
	if e.call(t, "GET", "/api/notes/"+id+"/history/"+oldest, nil, 200)["text"] != "first" {
		t.Fatal("version text")
	}
	base := e.call(t, "GET", "/api/notes/"+id, nil, 200)["version"]
	e.call(t, "POST", "/api/notes/"+id+"/history/"+oldest+"/restore", map[string]any{"base": "stale"}, 409)
	e.call(t, "POST", "/api/notes/"+id+"/history/"+oldest+"/restore", map[string]any{"base": base}, 200)
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["text"] != "first" || len(e.search(t, "q=first")) != 1 {
		t.Fatal("restore")
	}
	if n := len(e.call(t, "GET", "/api/notes/"+id+"/history", nil, 200)["versions"].([]any)); n != 4 {
		t.Fatalf("a restore should add a version, have %d", n)
	}
	e.call(t, "GET", "/api/notes/"+id+"/history/9999999999999", nil, 404)
	e.call(t, "GET", "/api/notes/"+id+"/history/nonsense", nil, 404)

	if e.call(t, "PUT", "/api/notes/"+id+"/history-enabled", map[string]any{"on": false}, 200)["history_enabled"] != false {
		t.Fatal("toggle off")
	}
	if e.call(t, "PUT", "/api/notes/"+id+"/history-enabled", map[string]any{"on": nil}, 200)["history_enabled"] != true {
		t.Fatal("back to the default")
	}
}

func TestConflictFlagCanBeCleared(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "a")
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["conflict"] != false {
		t.Fatal("a new note is flagged")
	}
	if err := e.v.SetConflict(id, true); err != nil {
		t.Fatal(err)
	}
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["conflict"] != true {
		t.Fatal("the flag is not shown")
	}
	hits := e.call(t, "GET", "/api/notes", nil, 200)["notes"].([]any)
	if len(hits) != 1 || hits[0].(map[string]any)["conflict"] != true {
		t.Fatalf("the list does not show the flag: %v", hits)
	}
	e.call(t, "DELETE", "/api/notes/"+id+"/conflict", nil, 200)
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["conflict"] != false {
		t.Fatal("the flag did not clear")
	}
}

func TestConflictsListHoldsOnlyFlaggedNotes(t *testing.T) {
	e := newEnv(t)
	e.create(t, "fine.md", "ok")
	id := e.create(t, "n.md", "<<<<<<< this device\nmine\n=======\ntheirs\n>>>>>>> atrium\n")
	if got := e.call(t, "GET", "/api/conflicts", nil, 200)["conflicts"].([]any); len(got) != 0 {
		t.Fatalf("nothing is flagged yet: %v", got)
	}
	if err := e.v.SetConflict(id, true); err != nil {
		t.Fatal(err)
	}
	got := e.call(t, "GET", "/api/conflicts", nil, 200)["conflicts"].([]any)
	if len(got) != 1 || got[0].(map[string]any)["id"] != id || !strings.Contains(got[0].(map[string]any)["text"].(string), "theirs") {
		t.Fatalf("%v", got)
	}
	e.call(t, "DELETE", "/api/notes/"+id+"/conflict", nil, 200)
	if got := e.call(t, "GET", "/api/conflicts", nil, 200)["conflicts"].([]any); len(got) != 0 {
		t.Fatalf("settled notes leave the list: %v", got)
	}
}

func TestFileEndpointServesSafely(t *testing.T) {
	e := newEnv(t)
	g := must(e.v.CreateNote("a/n.md", []byte("note")))
	must(e.v.AddFile(g.ID, "report.PDF", strings.NewReader("%PDF-1.4 sample")))
	must(e.v.AddFile(g.ID, "evil.html", strings.NewReader("<script>alert(1)</script>")))
	must(e.v.AddFile(g.ID, "evil.svg", strings.NewReader(`<svg onload="alert(1)"/>`)))
	must(e.v.AddFile(g.ID, "pic.png", strings.NewReader("png")))
	must(e.v.AddFile(g.ID, "weird name & \"quotes\".bin", strings.NewReader("bin")))
	e.reindexAll(t)

	files := e.call(t, "GET", "/api/notes/"+g.ID, nil, 200)["files"].([]any)
	if len(files) != 5 || files[0].(map[string]any)["name"] != "report.PDF" {
		t.Fatalf("%v", files)
	}

	resp, b := e.req(t, "GET", "/api/notes/"+g.ID+"/files/0", nil)
	if resp.StatusCode != 200 || string(b) != "%PDF-1.4 sample" || resp.Header.Get("Content-Type") != "application/pdf" ||
		!strings.HasPrefix(resp.Header.Get("Content-Disposition"), "inline") {
		t.Fatalf("pdf: %d %q %v", resp.StatusCode, b, resp.Header)
	}
	if resp.Header.Get("X-Content-Type-Options") != "nosniff" || !strings.Contains(resp.Header.Get("Content-Security-Policy"), "sandbox") {
		t.Fatalf("file headers: %v", resp.Header)
	}
	// A range request: the first bytes only.
	resp, b = e.req(t, "GET", "/api/notes/"+g.ID+"/files/0", nil, "Range", "bytes=0-3")
	if resp.StatusCode != 206 || string(b) != "%PDF" {
		t.Fatalf("range: %d %q", resp.StatusCode, b)
	}
	for _, i := range []int{1, 2, 4} { // html, svg and unknown types download, never open inline
		resp, _ = e.req(t, "GET", "/api/notes/"+g.ID+"/files/"+string(rune('0'+i)), nil)
		if resp.Header.Get("Content-Type") != "application/octet-stream" || !strings.HasPrefix(resp.Header.Get("Content-Disposition"), "attachment") {
			t.Errorf("file %d: %v", i, resp.Header)
		}
	}
	if resp, _ = e.req(t, "GET", "/api/notes/"+g.ID+"/files/3", nil); resp.Header.Get("Content-Type") != "image/png" {
		t.Errorf("png: %v", resp.Header)
	}
	for _, p := range []string{"5", "-1", "x", "99999999999999999999", "0%2f..%2f..%2fetc"} {
		if resp, _ = e.req(t, "GET", "/api/notes/"+g.ID+"/files/"+p, nil); resp.StatusCode < 400 || resp.StatusCode >= 500 {
			t.Errorf("files/%s: status %d", p, resp.StatusCode)
		}
	}
	e.call(t, "GET", "/api/notes/"+strings.Repeat("a", 26)+"/files/0", nil, 404)
}

func (e *tenv) reindexAll(t *testing.T) {
	t.Helper()
	if err := e.st.Reindex(e.v); err != nil {
		t.Fatal(err)
	}
}

func TestMissingNotesAndMaid(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "x")
	if len(e.call(t, "GET", "/api/missing", nil, 200)["missing"].([]any)) != 0 {
		t.Fatal("nothing is missing yet")
	}
	os.Remove(filepath.Join(e.root, "n.md"))
	if _, _, err := e.v.Rescan(); err != nil {
		t.Fatal(err)
	}
	m := e.call(t, "GET", "/api/missing", nil, 200)["missing"].([]any)
	if len(m) != 1 || m[0].(map[string]any)["id"] != id || m[0].(map[string]any)["paths"].([]any)[0] != "n.md" {
		t.Fatalf("%v", m)
	}
	e.call(t, "POST", "/api/notes/"+id+"/trash", nil, 204) // the dashboard's "remove"
	if len(e.call(t, "GET", "/api/missing", nil, 200)["missing"].([]any)) != 0 {
		t.Fatal("still missing after removing")
	}

	if e.call(t, "GET", "/api/status", nil, 200)["last_maid_run"] != nil {
		t.Fatal("no run yet")
	}
	p := e.call(t, "GET", "/api/maid/preview", nil, 200)
	if p["preview"] != true || p["time_check"] != "off" {
		t.Fatalf("%v", p)
	}
	r := e.call(t, "POST", "/api/maid/run", map[string]any{"force": false}, 200)
	if r["preview"] != false || r["needs_attention"] != false {
		t.Fatalf("%v", r)
	}
	if e.call(t, "GET", "/api/status", nil, 200)["last_maid_run"] == nil {
		t.Fatal("status should show the run")
	}
	e.call(t, "POST", "/api/maid/run", nil, 200)                            // empty body is fine
	e.call(t, "POST", "/api/maid/run", map[string]any{"force": "yes"}, 400) // wrong type
}

func TestStatusDescribesTheHub(t *testing.T) {
	e := newEnv(t)
	e.call(t, "PUT", "/api/settings/hub", map[string]any{"name": "Home hub"}, 200)
	id := e.create(t, "a.md", "x")
	e.create(t, "b.md", "y")
	must(e.v.AddFile(id, "slides.pdf", strings.NewReader("%PDF")))
	e.reindexAll(t)
	hub := e.call(t, "GET", "/api/status", nil, 200)["hub"].(map[string]any)
	// Notes are the Markdown notes; files are the attached or imported ones.
	if hub["name"] != "Home hub" || hub["notes"] != 2.0 || hub["files"] != 1.0 {
		t.Fatalf("%v", hub)
	}
	if hub["vault"] != e.root || hub["os"] == nil || hub["os"] == "" {
		t.Fatalf("%v", hub)
	}
	if _, ok := hub["version"].(string); !ok {
		t.Fatalf("version missing: %v", hub)
	}
}

func TestSettings(t *testing.T) {
	e := newEnv(t)
	s := e.call(t, "GET", "/api/settings", nil, 200)
	if s["trash_retention_days"] != 30.0 || s["hub"].(map[string]any)["history"].(map[string]any)["on"] != true {
		t.Fatalf("%v", s)
	}
	// Only the changed fields are sent; the rest keep their values.
	s = e.call(t, "PUT", "/api/settings/hub", map[string]any{"name": "Home", "history": map[string]any{"max_versions": 5}}, 200)
	h := s["hub"].(map[string]any)
	if h["name"] != "Home" || h["trash_retention_days"] != 30.0 || h["history"].(map[string]any)["window_minutes"] != 30.0 || h["history"].(map[string]any)["max_versions"] != 5.0 {
		t.Fatalf("%v", h)
	}
	if e.set.Hub().History.MaxVersions != 5 {
		t.Fatal("not saved")
	}
	// Invalid values are refused and change nothing.
	e.call(t, "PUT", "/api/settings/hub", map[string]any{"trash_retention_days": -1}, 400)
	e.call(t, "PUT", "/api/settings/hub", map[string]any{"history": map[string]any{"per_period": 2}}, 400)
	e.call(t, "PUT", "/api/settings/hub", map[string]any{"time_check": map[string]any{"on": true, "server": ""}}, 400)
	if e.set.Hub().Name != "Home" || e.set.Hub().TrashRetentionDays != 30 {
		t.Fatal("invalid request changed settings")
	}
	// Device: its own retention beats the atrium's, and null clears it again.
	s = e.call(t, "PUT", "/api/settings/device", map[string]any{"name": "Laptop", "trash_retention_days": 7}, 200)
	if s["trash_retention_days"] != 7.0 {
		t.Fatalf("%v", s)
	}
	s = e.call(t, "PUT", "/api/settings/device", map[string]any{"trash_retention_days": nil}, 200)
	if s["trash_retention_days"] != 30.0 || s["device"].(map[string]any)["name"] != "Laptop" {
		t.Fatalf("%v", s)
	}
	e.call(t, "PUT", "/api/settings/device", map[string]any{"trash_retention_days": -3}, 400)
}

func TestUnknownRoutesAndMethods(t *testing.T) {
	e := newEnv(t)
	if resp, _ := e.req(t, "GET", "/nope", nil); resp.StatusCode != 404 {
		t.Fatalf("%d", resp.StatusCode)
	}
	if resp, _ := e.req(t, "DELETE", "/api/notes/x", nil); resp.StatusCode != 405 {
		t.Fatalf("%d", resp.StatusCode)
	}
	if resp, _ := e.req(t, "GET", "/api/render", nil); resp.StatusCode != 405 {
		t.Fatalf("%d", resp.StatusCode)
	}
}

func TestOddIDsNeverReachTheFilesystem(t *testing.T) {
	e := newEnv(t)
	for _, id := range []string{"..", "%2e%2e", "a%2fb", "..%2f..%2fetc%2fpasswd", strings.Repeat("x", 5000), "trash", ".ids", "%00"} {
		for _, p := range []string{"/api/notes/", "/api/notes/%s/history", "/api/notes/%s/files/0"} {
			path := p + id
			if strings.Contains(p, "%s") {
				path = strings.Replace(p, "%s", id, 1)
			}
			if u, err := url.Parse(e.ts.URL + path); err != nil || u.Path == "" {
				continue
			}
			resp, _ := e.req(t, "GET", path, nil)
			if resp.StatusCode == 200 || resp.StatusCode >= 500 {
				t.Errorf("GET %s: status %d", path, resp.StatusCode)
			}
		}
		resp, _ := e.req(t, "POST", "/api/trash/"+id+"/delete-forever", nil)
		if resp.StatusCode == 204 || resp.StatusCode >= 500 {
			t.Errorf("delete-forever %q: status %d", id, resp.StatusCode)
		}
	}
}

func TestErrorMessagesReadLikeTheUI(t *testing.T) {
	e := newEnv(t)
	e.create(t, "a.md", "x")
	for name, c := range map[string]struct {
		method, path string
		body         any
		want         int
		msg          string
	}{
		"duplicate": {"POST", "/api/notes", map[string]string{"path": "a.md", "text": "x"}, 409, "Something with that name is already there."},
		"bad path":  {"POST", "/api/notes", map[string]string{"path": "../x.md", "text": "x"}, 400, "That name or folder isn't allowed. Avoid slashes, colons and a leading dot."},
		"not found": {"GET", "/api/notes/zzz", nil, 404, "That note wasn't found."},
		"bad value": {"PUT", "/api/settings/hub", map[string]any{"trash_retention_days": -1}, 400, "Trash retention must be between 0 and 36500 days."},
		"bad json":  {"POST", "/api/notes", `{"path":5}`, 400, ""},
	} {
		m := e.call(t, c.method, c.path, c.body, c.want)
		msg, _ := m["error"].(string)
		if c.msg != "" && msg != c.msg {
			t.Errorf("%s: %q, want %q", name, msg, c.msg)
		}
		if msg == "" || msg[0] < 'A' || msg[0] > 'Z' || !strings.HasSuffix(msg, ".") {
			t.Errorf("%s: %q should start with a capital and end with a full stop", name, msg)
		}
	}
}

func TestFailedSyncCommitsOnDashboard(t *testing.T) {
	e := newEnv(t)
	if f := e.call(t, "GET", "/api/status", nil, 200)["failed_commits"].([]any); len(f) != 0 {
		t.Fatalf("none yet: %v", f)
	}
	name := "aaaaaaaaaaaaaaaaaaaaaaaaaa-1700000000000.json"
	dir := filepath.Join(e.root, ".ids", "sync", "failed")
	os.MkdirAll(dir, 0o700)
	os.WriteFile(filepath.Join(dir, name), []byte(`{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaa","note_path":"x/a.md","at":"2026-11-25T10:00:00Z","reason":"the content the device uploaded is gone"}`), 0o600)
	f := e.call(t, "GET", "/api/status", nil, 200)["failed_commits"].([]any)
	if len(f) != 1 || f[0].(map[string]any)["name"] != name || f[0].(map[string]any)["note_path"] != "x/a.md" {
		t.Fatalf("%v", f)
	}
	e.call(t, "POST", "/api/sync/failed/"+name+"/dismiss", nil, 204)
	e.call(t, "POST", "/api/sync/failed/"+name+"/dismiss", nil, 404)
	e.call(t, "POST", "/api/sync/failed/..%2Fseq/dismiss", nil, 404)
}

func TestPairingFromSettings(t *testing.T) {
	e := newEnv(t)
	// Sync off: pairing says so.
	if m := e.call(t, "GET", "/api/pairing", nil, 404); m["code"] != "sync_off" {
		t.Fatalf("%v", m)
	}

	devs := must(devices.Open(e.root))
	p := pairing.NewAtrium(strings.Repeat("a", 64), devs)
	e.ts.Config.Handler = New(Deps{Vault: e.v, Store: e.st, Settings: e.set, Maid: maid.New(e.v, e.set, t.Logf), Logf: t.Logf, Addr: e.host, Pairing: p})

	if m := e.call(t, "GET", "/api/pairing", nil, 200); m["phase"] != "idle" {
		t.Fatalf("%v", m)
	}
	m := e.call(t, "POST", "/api/pairing", nil, 200)
	if m["phase"] != "waiting" || len(strings.Split(m["words"].(string), "-")) != 4 || m["expires"] == nil {
		t.Fatalf("open: %v", m)
	}
	// Approving with no device yet does nothing and says where things stand.
	if m := e.call(t, "POST", "/api/pairing/approve", nil, 200); m["phase"] != "waiting" {
		t.Fatalf("early approve: %v", m)
	}
	if m := e.call(t, "POST", "/api/pairing/cancel", nil, 200); m["phase"] != "cancelled" || m["words"] != nil {
		t.Fatalf("cancel: %v", m)
	}
	// Changes come only as JSON from the app itself, like every other change.
	if resp, _ := e.req(t, "POST", "/api/pairing", nil, "Sec-Fetch-Site", "cross-site"); resp.StatusCode != 403 {
		t.Fatalf("cross-site open: %d", resp.StatusCode)
	}
}

func TestDeviceListRevokeAndRestore(t *testing.T) {
	e := newEnv(t)
	e.call(t, "GET", "/api/devices", nil, 404) // sync off

	devs := must(devices.Open(e.root))
	id := strings.Repeat("b", 64)
	devs.Add(id, "Tablet")
	e.ts.Config.Handler = New(Deps{Vault: e.v, Store: e.st, Settings: e.set, Maid: maid.New(e.v, e.set, t.Logf), Logf: t.Logf, Addr: e.host, Devices: devs})

	list := func(m map[string]any) map[string]any { return m["devices"].([]any)[0].(map[string]any) }
	if d := list(e.call(t, "GET", "/api/devices", nil, 200)); d["name"] != "Tablet" || d["revoked_at"] != nil {
		t.Fatalf("%v", d)
	}
	for i := 0; i < 2; i++ { // revoking twice is harmless
		if d := list(e.call(t, "POST", "/api/devices/"+id+"/revoke", nil, 200)); d["revoked_at"] == nil {
			t.Fatalf("not revoked: %v", d)
		}
	}
	if devs.Trusted(id) {
		t.Fatal("still trusted")
	}
	if d := list(e.call(t, "POST", "/api/devices/"+id+"/unrevoke", nil, 200)); d["revoked_at"] != nil || !devs.Trusted(id) {
		t.Fatalf("not restored: %v", d)
	}
	e.call(t, "POST", "/api/devices/"+strings.Repeat("c", 64)+"/revoke", nil, 404)
	e.call(t, "POST", "/api/devices/nope/unrevoke", nil, 404)
	if resp, _ := e.req(t, "POST", "/api/devices/"+id+"/revoke", nil, "Sec-Fetch-Site", "cross-site"); resp.StatusCode != 403 || !devs.Trusted(id) {
		t.Fatalf("cross-site revoke: %d", resp.StatusCode)
	}
}

func TestSyncStatusOnlyOnADevice(t *testing.T) {
	e := newEnv(t)
	if m := e.call(t, "GET", "/api/sync", nil, 404); m["code"] != "not_device" {
		t.Fatalf("%v", m)
	}
	eng := syncdev.New(e.v, nil, &tls.Config{}, "atrium.example:7879", t.Logf)
	e.ts.Config.Handler = New(Deps{Vault: e.v, Store: e.st, Settings: e.set, Maid: maid.New(e.v, e.set, t.Logf), Logf: t.Logf, Addr: e.host, Sync: eng})
	if m := e.call(t, "GET", "/api/sync", nil, 200); m["atrium"] != "atrium.example:7879" || m["waiting"] == nil {
		t.Fatalf("%v", m)
	}
}

func TestRecoveryCandidates(t *testing.T) {
	e := newEnv(t)
	id := e.create(t, "n.md", "deleted version")
	if err := e.v.Trash(id); err != nil {
		t.Fatal(err)
	}
	text := "edited offline"
	h := sha256.Sum256([]byte(text))
	hash := hex.EncodeToString(h[:])
	if err := e.v.StageBlob(hash, strings.NewReader(text), 1<<20); err != nil {
		t.Fatal(err)
	}
	must(e.v.AddCandidate(id, vault.Group{Note: &vault.File{Path: "n.md", Version: hash}, Files: []vault.File{}}, "unknown-device", vault.EditedAfterDelete))

	cs := e.call(t, "GET", "/api/recovery", nil, 200)["candidates"].([]any)
	c := cs[0].(map[string]any)
	if len(cs) != 1 || c["id"] != id || c["note_path"] != "n.md" || c["from"] != "a device" || c["why"] != vault.EditedAfterDelete {
		t.Fatalf("%v", cs)
	}
	if st := e.call(t, "GET", "/api/status", nil, 200)["recovery"].([]any); len(st) != 1 {
		t.Fatalf("status: %v", st)
	}
	key := c["key"].(string)
	if got := e.call(t, "POST", "/api/recovery/"+key+"/restore", map[string]any{}, 200); got["id"] != id {
		t.Fatalf("%v", got)
	}
	if e.call(t, "GET", "/api/notes/"+id, nil, 200)["text"] != text {
		t.Fatal("not restored")
	}
	e.call(t, "POST", "/api/recovery/"+key+"/delete-forever", map[string]any{}, 404)
}
