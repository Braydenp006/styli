package syncsrv

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"styli/core/internal/store"
	"styli/core/internal/vault"
)

type env struct {
	v   *vault.Vault
	st  *store.Store
	srv *httptest.Server
}

func setup(t *testing.T) *env {
	t.Helper()
	v, err := vault.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	srv := httptest.NewServer(newHandler(Deps{Vault: v, Store: st, Logf: t.Logf, Version: "test"}))
	t.Cleanup(srv.Close)
	return &env{v, st, srv}
}

func hash(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// do sends a request and decodes a JSON answer into out (if given).
func (e *env) do(t *testing.T, method, path, body string, out any) *http.Response {
	t.Helper()
	req, _ := http.NewRequest(method, e.srv.URL+path, strings.NewReader(body))
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if out != nil {
		if err := json.NewDecoder(res.Body).Decode(out); err != nil {
			t.Fatalf("%s %s: %v", method, path, err)
		}
	}
	return res
}

func (e *env) upload(t *testing.T, content string) string {
	t.Helper()
	h := hash(content)
	if res := e.do(t, "PUT", "/sync/v1/blobs/"+h, content, nil); res.StatusCode != http.StatusNoContent {
		t.Fatalf("upload: %d", res.StatusCode)
	}
	return h
}

type feed struct {
	Groups []Group `json:"groups"`
	Next   uint64  `json:"next"`
	More   bool    `json:"more"`
}

const devID = "bbbbbbbbbbbbbbbbbbbbbbbbbb"

func TestPushThenPull(t *testing.T) {
	e := setup(t)
	var hello map[string]any
	res := e.do(t, "GET", "/sync/v1/hello", "", &hello)
	if hello["protocol"] != float64(Protocol) || hello["epoch"] != e.v.Epoch() || res.Header.Get("Styli-Epoch") != e.v.Epoch() {
		t.Fatalf("hello: %v", hello)
	}

	h := e.upload(t, "# Pushed\nfrom a device")
	var g Group
	body := fmt.Sprintf(`{"base":0,"note":{"path":"a.md","version":%q},"files":[]}`, h)
	if res := e.do(t, "PUT", "/sync/v1/groups/"+devID, body, &g); res.StatusCode != 200 || g.Rev == 0 {
		t.Fatalf("commit: %d %+v", res.StatusCode, g)
	}
	// The search index knows about it.
	if hits, _ := e.st.Find(store.Query{ID: devID}); len(hits) != 1 || hits[0].Title != "Pushed" {
		t.Fatalf("index: %+v", hits)
	}

	var f feed
	e.do(t, "GET", "/sync/v1/changes?since=0", "", &f)
	if len(f.Groups) != 1 || f.Groups[0].ID != devID || f.Next != g.Rev || f.More {
		t.Fatalf("feed: %+v", f)
	}
	res = e.do(t, "GET", "/sync/v1/groups/"+devID+"/note?version="+h, "", nil)
	if res.StatusCode != 200 {
		t.Fatalf("note: %d", res.StatusCode)
	}
	if res := e.do(t, "GET", "/sync/v1/groups/"+devID+"/note?version="+hash("old"), "", nil); res.StatusCode != http.StatusConflict {
		t.Fatalf("stale version: %d", res.StatusCode)
	}
}

func TestCommitConflictReturnsCurrent(t *testing.T) {
	e := setup(t)
	g, _ := e.v.CreateNote("a.md", []byte("one"))
	_, ver, _ := e.v.ReadNote(g.ID)
	e.v.WriteNote(g.ID, []byte("web edit"), ver)
	h := e.upload(t, "device")
	var out struct {
		Code    string `json:"code"`
		Current Group  `json:"current"`
	}
	body := fmt.Sprintf(`{"base":%d,"note":{"path":"a.md","version":%q},"files":[]}`, g.Rev, h)
	res := e.do(t, "PUT", "/sync/v1/groups/"+g.ID, body, &out)
	if res.StatusCode != http.StatusConflict || out.Code != "conflict" || out.Current.Note.Version != hash("web edit") {
		t.Fatalf("got %d %+v", res.StatusCode, out)
	}
}

func TestCommitErrors(t *testing.T) {
	e := setup(t)
	g, _ := e.v.CreateNote("a.md", []byte("one"))
	e.v.CreateNote("b.md", []byte("two"))
	h := e.upload(t, "x")
	cases := []struct {
		id, body string
		status   int
		code     string
	}{
		{g.ID, fmt.Sprintf(`{"base":%d,"note":{"path":"b.md","version":%q},"files":[]}`, g.Rev, g.Note.Version), 409, "path_taken"},
		{g.ID, fmt.Sprintf(`{"base":%d,"note":{"path":"a.md","version":%q},"files":[]}`, g.Rev, hash("not uploaded")), 422, "missing_blob"},
		{g.ID, fmt.Sprintf(`{"base":%d,"note":{"path":"../a.md","version":%q},"files":[]}`, g.Rev, h), 400, "bad_request"},
		{g.ID, `{"base":1,"surprise":true}`, 400, "bad_request"},
		{g.ID, `not json`, 400, "bad_request"},
		{devID, fmt.Sprintf(`{"base":7,"note":{"path":"c.md","version":%q},"files":[]}`, h), 404, "not_found"},
	}
	for _, c := range cases {
		var out map[string]any
		res := e.do(t, "PUT", "/sync/v1/groups/"+c.id, c.body, &out)
		if res.StatusCode != c.status || out["code"] != c.code {
			t.Errorf("%s: got %d %v, want %d %s", c.body, res.StatusCode, out, c.status, c.code)
		}
	}
	e.v.Trash(g.ID)
	var out map[string]any
	res := e.do(t, "PUT", "/sync/v1/groups/"+g.ID, fmt.Sprintf(`{"base":%d,"note":{"path":"a.md","version":%q},"files":[]}`, g.Rev, h), &out)
	if res.StatusCode != http.StatusGone || out["code"] != "deleted" {
		t.Errorf("trashed: %d %v", res.StatusCode, out)
	}
	if res := e.do(t, "PUT", "/sync/v1/blobs/"+hash("a"), "b", nil); res.StatusCode != 422 {
		t.Errorf("wrong blob: %d", res.StatusCode)
	}
}

func TestFileDownloadWithRange(t *testing.T) {
	e := setup(t)
	g, _ := e.v.ImportFile("doc.pdf", strings.NewReader("0123456789"))
	req, _ := http.NewRequest("GET", e.srv.URL+"/sync/v1/groups/"+g.ID+"/files/0?version="+g.Files[0].Version, nil)
	req.Header.Set("Range", "bytes=4-")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusPartialContent || string(b) != "456789" {
		t.Fatalf("range: %d %q", res.StatusCode, b)
	}
	for _, p := range []string{"/files/1?version=" + g.Files[0].Version, "/files/x", "/files/0?version=" + hash("other")} {
		if res := e.do(t, "GET", "/sync/v1/groups/"+g.ID+p, "", nil); res.StatusCode == 200 {
			t.Errorf("%s: served", p)
		}
	}
}

func TestChangesPagingAndWait(t *testing.T) {
	e := setup(t)
	for i := range 3 {
		e.v.CreateNote(fmt.Sprintf("%d.md", i), nil)
	}
	var f feed
	e.do(t, "GET", "/sync/v1/changes?since=0&limit=2", "", &f)
	if len(f.Groups) != 2 || !f.More {
		t.Fatalf("page 1: %+v", f)
	}
	e.do(t, "GET", fmt.Sprintf("/sync/v1/changes?since=%d&limit=2", f.Next), "", &f)
	if len(f.Groups) != 1 || f.More {
		t.Fatalf("page 2: %+v", f)
	}
	last := f.Next

	// Nothing new: a wait ends empty with the same cursor...
	start := time.Now()
	MaxWait = 200 * time.Millisecond
	t.Cleanup(func() { MaxWait = 30 * time.Second })
	e.do(t, "GET", fmt.Sprintf("/sync/v1/changes?since=%d&wait=30", last), "", &f)
	if len(f.Groups) != 0 || f.Next != last || time.Since(start) < 150*time.Millisecond {
		t.Fatalf("empty wait: %+v", f)
	}
	// ...and wakes as soon as something changes.
	MaxWait = 10 * time.Second
	go func() {
		time.Sleep(50 * time.Millisecond)
		e.v.CreateNote("late.md", nil)
	}()
	start = time.Now()
	e.do(t, "GET", fmt.Sprintf("/sync/v1/changes?since=%d&wait=10", last), "", &f)
	if len(f.Groups) != 1 || time.Since(start) > 5*time.Second {
		t.Fatalf("woken wait: %+v after %v", f, time.Since(start))
	}
	if res := e.do(t, "GET", "/sync/v1/changes?since=-1", "", nil); res.StatusCode != 400 {
		t.Fatalf("bad since: %d", res.StatusCode)
	}
}

// The device's commit went through but the reply was lost: it retries.
func TestRetriedCommitIsHarmless(t *testing.T) {
	e := setup(t)
	h := e.upload(t, "once")
	body := fmt.Sprintf(`{"base":0,"note":{"path":"a.md","version":%q},"files":[]}`, h)
	var a, b Group
	e.do(t, "PUT", "/sync/v1/groups/"+devID, body, &a)
	if res := e.do(t, "PUT", "/sync/v1/groups/"+devID, body, &b); res.StatusCode != 200 || b.Rev != a.Rev {
		t.Fatalf("retry: %d %+v", res.StatusCode, b)
	}
	if gs, _ := e.v.List(); len(gs) != 1 {
		t.Fatalf("%d groups", len(gs))
	}
}

func TestDeleteFromDevice(t *testing.T) {
	e := setup(t)
	g, _ := e.v.CreateNote("a.md", []byte("a"))
	path := fmt.Sprintf("/sync/v1/groups/%s?base=%d", g.ID, g.Rev)

	var got Group
	if res := e.do(t, "DELETE", path, "", &got); res.StatusCode != 200 || !got.Deleted || got.Rev != e.v.Seq() || got.Note.Path != "a.md" {
		t.Fatalf("%d %+v", res.StatusCode, got)
	}
	// The same delete again (its reply was lost) gets the same answer.
	var again Group
	if res := e.do(t, "DELETE", path, "", &again); res.StatusCode != 200 || again.Rev != got.Rev || !again.Deleted {
		t.Fatalf("retry: %d %+v", res.StatusCode, again)
	}
	// The feed lists it as a deletion.
	var f feed
	e.do(t, "GET", "/sync/v1/changes?since=0", "", &f)
	if len(f.Groups) != 1 || !f.Groups[0].Deleted {
		t.Fatalf("%+v", f)
	}
	// Emptied from the trash: gone, and hello says the feed forgot it.
	if err := e.v.DeleteForever(g.ID); err != nil {
		t.Fatal(err)
	}
	if res := e.do(t, "DELETE", path, "", nil); res.StatusCode != 404 {
		t.Fatalf("after delete forever: %d", res.StatusCode)
	}
	var h struct{ Forgotten uint64 }
	e.do(t, "GET", "/sync/v1/hello", "", &h)
	if h.Forgotten != got.Rev {
		t.Fatalf("forgotten %d, want %d", h.Forgotten, got.Rev)
	}
}

// A delete of a version that has since changed on the atrium still stands,
// and the newer version is kept as a recovery candidate.
func TestStaleDeleteKeepsCandidate(t *testing.T) {
	e := setup(t)
	g, _ := e.v.CreateNote("a.md", []byte("a"))
	_, ver, _ := e.v.ReadNote(g.ID)
	e.v.WriteNote(g.ID, []byte("a2"), ver)
	var got Group
	if res := e.do(t, "DELETE", fmt.Sprintf("/sync/v1/groups/%s?base=%d", g.ID, g.Rev), "", &got); res.StatusCode != 200 || !got.Deleted {
		t.Fatalf("%d %+v", res.StatusCode, got)
	}
	cs, _ := e.v.ListCandidates()
	if len(cs) != 1 || cs[0].Why != vault.DeletedWhileChanged || cs[0].Group.Note.Version != hash("a2") {
		t.Fatalf("%+v", cs)
	}
	if res := e.do(t, "DELETE", "/sync/v1/groups/"+g.ID, "", nil); res.StatusCode != 400 {
		t.Fatalf("no base: %d", res.StatusCode)
	}
}

func TestRecoveryCandidateFromDevice(t *testing.T) {
	e := setup(t)
	g, _ := e.v.CreateNote("a.md", []byte("a"))
	body := fmt.Sprintf(`{"note":{"path":"a.md","version":%q},"files":[]}`, e.upload(t, "edited offline"))
	path := "/sync/v1/groups/" + g.ID + "/recovery"

	var ae struct {
		Code    string
		Current Group
	}
	if res := e.do(t, "POST", path, body, &ae); res.StatusCode != 409 || ae.Code != "conflict" || ae.Current.ID != g.ID {
		t.Fatalf("live: %d %+v", res.StatusCode, ae)
	}
	e.v.Trash(g.ID)
	tg, _ := e.v.Trashed(g.ID)
	var out struct {
		Key        string
		DeletedRev uint64 `json:"deleted_rev"`
	}
	if res := e.do(t, "POST", path, body, &out); res.StatusCode != 200 || out.DeletedRev != tg.DeletedRev || out.Key == "" {
		t.Fatalf("%d %+v", res.StatusCode, out)
	}
	var again struct{ Key string }
	if e.do(t, "POST", path, body, &again); again.Key != out.Key {
		t.Fatal("sent again made a second candidate")
	}
	missing := fmt.Sprintf(`{"note":{"path":"a.md","version":%q},"files":[]}`, hash("never uploaded"))
	if res := e.do(t, "POST", path, missing, nil); res.StatusCode != 422 {
		t.Fatalf("missing upload: %d", res.StatusCode)
	}
}

// GET /sync/v1/groups/<id> confirms what the atrium holds, checked against
// the disk: live, an unscanned edit ("changed"), deleted, or gone.
func TestGetGroupConfirmsTheDisk(t *testing.T) {
	e := setup(t)
	g, _ := e.v.CreateNote("a.md", []byte("a"))
	path := "/sync/v1/groups/" + g.ID
	var got Group
	if res := e.do(t, "GET", path, "", &got); res.StatusCode != 200 || got.Deleted || got.Rev != g.Rev || got.Note.Version != hash("a") {
		t.Fatalf("%d %+v", res.StatusCode, got)
	}
	if err := os.WriteFile(filepath.Join(e.v.Root(), "a.md"), []byte("edited outside"), 0o644); err != nil {
		t.Fatal(err)
	}
	var ae struct{ Code string }
	if res := e.do(t, "GET", path, "", &ae); res.StatusCode != 409 || ae.Code != "changed" {
		t.Fatalf("unscanned edit: %d %+v", res.StatusCode, ae)
	}
	os.WriteFile(filepath.Join(e.v.Root(), "a.md"), []byte("a"), 0o644)
	e.v.Trash(g.ID)
	got = Group{}
	if res := e.do(t, "GET", path, "", &got); res.StatusCode != 200 || !got.Deleted || got.Note.Path != "a.md" {
		t.Fatalf("trashed: %d %+v", res.StatusCode, got)
	}
	e.v.DeleteForever(g.ID)
	if res := e.do(t, "GET", path, "", &ae); res.StatusCode != 404 || ae.Code != "not_found" {
		t.Fatalf("emptied: %d %+v", res.StatusCode, ae)
	}
}
