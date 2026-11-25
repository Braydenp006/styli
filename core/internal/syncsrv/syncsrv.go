// Package syncsrv is the atrium's sync API: the change feed devices pull
// from, content downloads, uploads and commits. See "Sync protocol" in
// docs/DESIGN.md. It is served on its own port behind mutual TLS (see
// server.go), and only through Server, so it can never be reached without
// proving which paired device is calling.
package syncsrv

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"net/http"
	"strconv"
	"time"

	"styli/core/internal/store"
	"styli/core/internal/vault"
)

// Protocol is the sync protocol version, bumped on any incompatible change.
const Protocol = 1

var (
	MaxBody     int64 = 8 << 20 // a commit's JSON
	MaxBlob     int64 = 2 << 30 // one uploaded file
	MaxWait           = 30 * time.Second
	DefaultPage       = 500
	MaxPage           = 1000
)

// Deps is what the handlers need.
type Deps struct {
	Vault   *vault.Vault
	Store   *store.Store // optional; kept in step with commits
	Logf    func(string, ...any)
	Version string // the app's version
}

type server struct{ Deps }

// newHandler returns the sync API's handlers, with no checks of who is
// calling: Server puts them behind mutual TLS.
func newHandler(d Deps) http.Handler {
	s := &server{d}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /sync/v1/hello", s.hello)
	mux.HandleFunc("GET /sync/v1/changes", s.changes)
	mux.HandleFunc("GET /sync/v1/groups/{id}/note", s.getNote)
	mux.HandleFunc("GET /sync/v1/groups/{id}/files/{n}", s.getFile)
	mux.HandleFunc("PUT /sync/v1/blobs/{hash}", s.putBlob)
	mux.HandleFunc("PUT /sync/v1/groups/{id}", s.commit)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Every answer says which epoch its numbers belong to.
		w.Header().Set("Styli-Epoch", s.Vault.Epoch())
		w.Header().Set("Cache-Control", "no-store")
		mux.ServeHTTP(w, r)
	})
}

// Group is a group as it travels over the wire.
type Group struct {
	ID      string       `json:"id"`
	Rev     uint64       `json:"rev"`
	Note    *vault.File  `json:"note,omitempty"`
	Files   []vault.File `json:"files"`
	History *bool        `json:"history,omitempty"`
}

func wire(g vault.Group) Group {
	if g.Files == nil {
		g.Files = []vault.File{}
	}
	return Group{g.ID, g.Rev, g.Note, g.Files, g.History}
}

func (s *server) hello(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"protocol": Protocol, "version": s.Version, "epoch": s.Vault.Epoch(), "seq": s.Vault.Seq(),
	})
}

// changes lists groups changed after ?since=N, oldest change first. With
// ?wait=S it holds the request up to S seconds until something changes.
func (s *server) changes(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	since, err := uintParam(q.Get("since"), 0)
	limit, err2 := uintParam(q.Get("limit"), uint64(DefaultPage))
	wait, err3 := uintParam(q.Get("wait"), 0)
	if err := errors.Join(err, err2, err3); err != nil || limit == 0 {
		fail(w, http.StatusBadRequest, "bad_request", "since, limit and wait must be whole numbers")
		return
	}
	limit = min(limit, uint64(MaxPage))
	deadline := time.Now().Add(min(time.Duration(wait)*time.Second, MaxWait))
	for {
		changed := s.Vault.Changed() // before reading, so no change slips in between
		gs, more, err := s.Vault.Changes(since, int(limit))
		if err != nil {
			s.failErr(w, err)
			return
		}
		left := time.Until(deadline)
		if len(gs) > 0 || left <= 0 {
			out := make([]Group, len(gs))
			next := since
			for i, g := range gs {
				out[i] = wire(g)
				next = g.Rev
			}
			writeJSON(w, http.StatusOK, map[string]any{"groups": out, "next": next, "more": more})
			return
		}
		t := time.NewTimer(left)
		select {
		case <-changed:
		case <-t.C:
		case <-r.Context().Done():
			t.Stop()
			return
		}
		t.Stop()
	}
}

// getNote sends the note's text if it still has the hash in ?version.
func (s *server) getNote(w http.ResponseWriter, r *http.Request) {
	b, ver, err := s.Vault.ReadNote(r.PathValue("id"))
	if err != nil {
		s.failErr(w, err)
		return
	}
	if ver != r.URL.Query().Get("version") {
		fail(w, http.StatusConflict, "changed", "the note has changed; pull again")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(b))
}

// getFile sends the group's n-th attached file if its record still has the
// hash in ?version. Ranges are supported so a large download can resume. The
// device checks the hash of what it received.
func (s *server) getFile(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	n, err := strconv.Atoi(r.PathValue("n"))
	if err != nil {
		fail(w, http.StatusNotFound, "not_found", "no such file")
		return
	}
	g, err := s.Vault.Get(id)
	if err != nil {
		s.failErr(w, err)
		return
	}
	if n < 0 || n >= len(g.Files) {
		fail(w, http.StatusNotFound, "not_found", "no such file")
		return
	}
	if g.Files[n].Version != r.URL.Query().Get("version") {
		fail(w, http.StatusConflict, "changed", "the file has changed; pull again")
		return
	}
	f, _, err := s.Vault.OpenFile(id, n)
	if err != nil {
		s.failErr(w, err)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		s.failErr(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeContent(w, r, "", fi.ModTime(), f)
}

func (s *server) putBlob(w http.ResponseWriter, r *http.Request) {
	if r.ContentLength > MaxBlob {
		fail(w, http.StatusRequestEntityTooLarge, "too_large", "that file is too large")
		return
	}
	if err := s.Vault.StageBlob(r.PathValue("hash"), r.Body, MaxBlob); err != nil {
		s.failErr(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) commit(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Base    uint64       `json:"base"`
		Note    *vault.File  `json:"note"`
		Files   []vault.File `json:"files"`
		History *bool        `json:"history"`
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, MaxBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil || dec.More() {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			fail(w, http.StatusRequestEntityTooLarge, "too_large", "the commit is too large")
			return
		}
		fail(w, http.StatusBadRequest, "bad_request", "the commit is not valid JSON")
		return
	}
	if req.Files == nil {
		req.Files = []vault.File{}
	}
	id := r.PathValue("id")
	g, err := s.Vault.Apply(id, req.Base, vault.Group{Note: req.Note, Files: req.Files, History: req.History})
	if errors.Is(err, vault.ErrConflict) {
		// The device pulls this, merges and pushes again.
		writeJSON(w, http.StatusConflict, map[string]any{"code": "conflict", "error": "the group changed on the atrium", "current": wire(g)})
		return
	}
	if err != nil {
		s.failErr(w, err)
		return
	}
	if s.Store != nil {
		if err := s.Store.Refresh(s.Vault, id); err != nil {
			s.Logf("sync: index %s: %v", id, err) // the commit stands; a restart reindexes
		}
	}
	writeJSON(w, http.StatusOK, wire(g))
}

func uintParam(s string, def uint64) (uint64, error) {
	if s == "" {
		return def, nil
	}
	return strconv.ParseUint(s, 10, 64)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// fail answers with an error code the device acts on, and a message for logs.
func fail(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"code": code, "error": msg})
}

func (s *server) failErr(w http.ResponseWriter, err error) {
	var tooBig *http.MaxBytesError
	switch {
	case errors.As(err, &tooBig), errors.Is(err, vault.ErrTooLarge):
		fail(w, http.StatusRequestEntityTooLarge, "too_large", "too large")
	case errors.Is(err, vault.ErrNotFound), errors.Is(err, vault.ErrNoNote):
		fail(w, http.StatusNotFound, "not_found", "no such group")
	case errors.Is(err, vault.ErrTrashed):
		fail(w, http.StatusGone, "deleted", "the group is in the atrium's trash")
	case errors.Is(err, vault.ErrExists):
		fail(w, http.StatusConflict, "path_taken", "another file already has that name")
	case errors.Is(err, vault.ErrMissingFile), errors.Is(err, fs.ErrNotExist):
		fail(w, http.StatusConflict, "missing", "a file of this group is missing from the atrium's vault folder")
	case errors.Is(err, vault.ErrMissingBlob):
		fail(w, http.StatusUnprocessableEntity, "missing_blob", "upload the content first")
	case errors.Is(err, vault.ErrHashMismatch):
		fail(w, http.StatusUnprocessableEntity, "hash_mismatch", "the content does not match its hash")
	case errors.Is(err, vault.ErrBadPath), errors.Is(err, vault.ErrBadGroup):
		fail(w, http.StatusBadRequest, "bad_request", err.Error())
	default:
		if errors.Is(err, io.ErrUnexpectedEOF) {
			fail(w, http.StatusBadRequest, "bad_request", "the upload was cut short")
			return
		}
		s.Logf("sync: internal error: %v", err)
		fail(w, http.StatusInternalServerError, "internal", "something went wrong on the atrium")
	}
}
