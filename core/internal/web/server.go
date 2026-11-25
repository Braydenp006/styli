// Package web is the hub's HTTP API (and, later, its web UI). It listens on
// loopback only, because login does not exist yet, and every request passes
// the guard below. Handlers never touch the filesystem themselves: every path
// goes through the vault's own checks.
package web

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"styli/core/internal/maid"
	"styli/core/internal/settings"
	"styli/core/internal/store"
	"styli/core/internal/vault"
)

// MaxBody is the largest JSON request body accepted (note text, settings).
// Files are never uploaded through the API.
const MaxBody = 8 << 20

// Deps is everything the API needs.
type Deps struct {
	Vault    *vault.Vault
	Store    *store.Store
	Settings *settings.Store
	Maid     *maid.Maid
	Logf     func(string, ...any)
	// Addr is the address the server listens on, for example "127.0.0.1:7878".
	// Only requests addressed to that port on a loopback name are accepted.
	Addr string
}

type server struct {
	Deps
	hosts map[string]bool
}

// New returns the API handler.
func New(d Deps) http.Handler {
	if d.Logf == nil {
		d.Logf = log.Printf
	}
	s := &server{Deps: d, hosts: map[string]bool{}}
	_, port, _ := net.SplitHostPort(d.Addr)
	for _, h := range []string{"127.0.0.1", "localhost", "[::1]"} {
		s.hosts[h+":"+port] = true
	}

	mux := http.NewServeMux()
	s.routes(mux)
	return s.recoverer(s.logger(s.guard(mux)))
}

func (s *server) routes(mux *http.ServeMux) {
	s.staticRoutes(mux)

	mux.HandleFunc("GET /api/notes", s.listNotes)
	mux.HandleFunc("POST /api/notes", s.createNote)
	mux.HandleFunc("GET /api/notes/{id}", s.getNote)
	mux.HandleFunc("PUT /api/notes/{id}", s.saveNote)
	mux.HandleFunc("POST /api/notes/{id}/move", s.moveNote)
	mux.HandleFunc("POST /api/notes/{id}/trash", s.trashNote)
	mux.HandleFunc("GET /api/notes/{id}/files/{n}", s.getFile)
	mux.HandleFunc("GET /api/notes/{id}/history", s.listVersions)
	mux.HandleFunc("GET /api/notes/{id}/history/{vid}", s.getVersion)
	mux.HandleFunc("POST /api/notes/{id}/history/{vid}/restore", s.restoreVersion)
	mux.HandleFunc("PUT /api/notes/{id}/history-enabled", s.setHistoryEnabled)

	mux.HandleFunc("POST /api/render", s.render)

	mux.HandleFunc("GET /api/folders", s.listFolders)
	mux.HandleFunc("GET /api/tags", s.listTags)

	mux.HandleFunc("GET /api/trash", s.listTrash)
	mux.HandleFunc("POST /api/trash/{id}/restore", s.restoreTrash)
	mux.HandleFunc("POST /api/trash/{id}/delete-forever", s.deleteForever)

	mux.HandleFunc("GET /api/missing", s.listMissing)
	mux.HandleFunc("GET /api/status", s.status)
	mux.HandleFunc("GET /api/maid/preview", s.maidPreview)
	mux.HandleFunc("POST /api/maid/run", s.maidRun)

	mux.HandleFunc("GET /api/settings", s.getSettings)
	mux.HandleFunc("PUT /api/settings/hub", s.putHub)
	mux.HandleFunc("PUT /api/settings/device", s.putDevice)
}

// guard applies the rules every request must pass, then sets the security
// headers on the response.
func (s *server) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		// Scripts only from our own origin; no framing; nothing sent anywhere else.
		h.Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "+
			"img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Cache-Control", "no-store")

		// Host: stops DNS rebinding, where a hostile site's name points at us.
		if !s.hosts[strings.ToLower(r.Host)] {
			fail(w, http.StatusForbidden, "forbidden_host", "unexpected Host header")
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			// JSON only: other sites cannot send this type without a CORS
			// preflight, and we never answer one.
			if mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || mt != "application/json" {
				fail(w, http.StatusUnsupportedMediaType, "unsupported_media_type", "send application/json")
				return
			}
			// A browser always sends Origin on a cross-site write; curl does not.
			if o := r.Header.Get("Origin"); o != "" {
				if u, err := url.Parse(o); err != nil || u.Scheme != "http" || strings.ToLower(u.Host) != strings.ToLower(r.Host) {
					fail(w, http.StatusForbidden, "forbidden_origin", "request came from another origin")
					return
				}
			}
			if sf := r.Header.Get("Sec-Fetch-Site"); sf != "" && sf != "same-origin" && sf != "none" {
				fail(w, http.StatusForbidden, "forbidden_origin", "request came from another site")
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func (s *server) recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if p := recover(); p != nil {
				s.Logf("web: panic in %s %s: %v", r.Method, r.URL.Path, p)
				fail(w, http.StatusInternalServerError, "internal", "something went wrong")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (w *statusWriter) WriteHeader(c int) { w.code = c; w.ResponseWriter.WriteHeader(c) }

func (s *server) logger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, code: http.StatusOK}
		next.ServeHTTP(sw, r)
		// Method, path and status only: never bodies or note text.
		s.Logf("web: %s %s %d %s", r.Method, r.URL.Path, sw.code, time.Since(start).Round(time.Millisecond))
	})
}

type apiError struct {
	Error string `json:"error"`
	Code  string `json:"code"`
}

func fail(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, apiError{msg, code})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// failErr turns a backend error into a status code. Anything unexpected is
// logged and answered with a generic 500, so internals never leak.
func (s *server) failErr(w http.ResponseWriter, err error) {
	var tooBig *http.MaxBytesError
	switch {
	case errors.As(err, &tooBig):
		fail(w, http.StatusRequestEntityTooLarge, "too_large", "request is too large")
	case errors.Is(err, vault.ErrNotFound), errors.Is(err, vault.ErrNoNote):
		fail(w, http.StatusNotFound, "not_found", "not found")
	case errors.Is(err, fs.ErrNotExist):
		fail(w, http.StatusNotFound, "missing", "the file is missing from the vault")
	case errors.Is(err, vault.ErrConflict):
		fail(w, http.StatusConflict, "conflict", "the note changed since you opened it")
	case errors.Is(err, vault.ErrExists), errors.Is(err, vault.ErrHasNote):
		fail(w, http.StatusConflict, "exists", "something with that name is already there")
	case errors.Is(err, vault.ErrBadPath):
		fail(w, http.StatusBadRequest, "bad_path", "that path is not allowed")
	case errors.Is(err, vault.ErrBadPolicy), errors.Is(err, settings.ErrInvalid):
		fail(w, http.StatusBadRequest, "invalid", err.Error())
	case errors.Is(err, errBadRequest):
		fail(w, http.StatusBadRequest, "bad_request", err.Error())
	default:
		s.Logf("web: internal error: %v", err)
		fail(w, http.StatusInternalServerError, "internal", "something went wrong")
	}
}

var errBadRequest = errors.New("bad request")

func badRequest(format string, a ...any) error {
	return fmt.Errorf("%w: %s", errBadRequest, fmt.Sprintf(format, a...))
}

// readJSON decodes the request body into dst (which may be pre-filled).
// An empty body is allowed and leaves dst alone. Unknown fields and extra data
// after the object are errors.
func readJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, MaxBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		if errors.Is(err, io.EOF) {
			return nil
		}
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return err
		}
		return badRequest("invalid JSON: %v", err)
	}
	if dec.More() {
		return badRequest("unexpected data after the JSON object")
	}
	return nil
}
