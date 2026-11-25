// Package web is the atrium's HTTP API (and, later, its web UI). It listens on
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
	"unicode"
	"unicode/utf8"

	"styli/core/internal/devices"
	"styli/core/internal/maid"
	"styli/core/internal/pairing"
	"styli/core/internal/settings"
	"styli/core/internal/store"
	"styli/core/internal/syncdev"
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
	Version  string // the build version, shown on the dashboard
	// Pairing, if set, lets the Settings screen pair new devices (nil when
	// sync is off).
	Pairing *pairing.Atrium
	// Devices is the paired-device list, shown on Settings (nil when sync is off).
	Devices *devices.List
	// Sync, if set, is this device's sync with its atrium (nil on an atrium).
	Sync *syncdev.Engine
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
	mux.HandleFunc("POST /api/notes/{id}/clear", s.clearNote)
	mux.HandleFunc("GET /api/notes/{id}/files/{n}", s.getFile)
	mux.HandleFunc("GET /api/notes/{id}/history", s.listVersions)
	mux.HandleFunc("GET /api/notes/{id}/history/{vid}", s.getVersion)
	mux.HandleFunc("POST /api/notes/{id}/history/{vid}/restore", s.restoreVersion)
	mux.HandleFunc("PUT /api/notes/{id}/history-enabled", s.setHistoryEnabled)
	mux.HandleFunc("DELETE /api/notes/{id}/conflict", s.clearConflict)
	mux.HandleFunc("GET /api/conflicts", s.listConflicts)

	mux.HandleFunc("POST /api/render", s.render)

	mux.HandleFunc("GET /api/folders", s.listFolders)
	mux.HandleFunc("GET /api/tags", s.listTags)

	mux.HandleFunc("GET /api/trash", s.listTrash)
	mux.HandleFunc("POST /api/trash/{id}/restore", s.restoreTrash)
	mux.HandleFunc("POST /api/trash/{id}/delete-forever", s.deleteForever)
	mux.HandleFunc("GET /api/recovery", s.listCandidates)
	mux.HandleFunc("POST /api/recovery/{key}/restore", s.restoreCandidate)
	mux.HandleFunc("POST /api/recovery/{key}/delete-forever", s.deleteCandidate)

	mux.HandleFunc("GET /api/missing", s.listMissing)
	mux.HandleFunc("GET /api/status", s.status)
	mux.HandleFunc("POST /api/sync/failed/{name}/dismiss", s.dismissFailedCommit)
	mux.HandleFunc("GET /api/pairing", s.getPairing)
	mux.HandleFunc("POST /api/pairing", s.openPairing)
	mux.HandleFunc("POST /api/pairing/approve", s.approvePairing)
	mux.HandleFunc("POST /api/pairing/cancel", s.cancelPairing)
	mux.HandleFunc("GET /api/sync", s.syncStatus)
	mux.HandleFunc("GET /api/devices", s.listDevices)
	mux.HandleFunc("POST /api/devices/{id}/revoke", s.revokeDevice)
	mux.HandleFunc("POST /api/devices/{id}/unrevoke", s.unrevokeDevice)
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
		// Fetch Metadata: a browser says where a request comes from. Another
		// site may link to the atrium's page (a top-level navigation), but may not
		// make the browser fetch anything else from it, reads included: even
		// unreadable answers can be timed, which would let a site test whether
		// your notes contain a word. Old browsers send no such header and fall
		// back to the checks below.
		if !sameSiteOK(r) {
			fail(w, http.StatusForbidden, "forbidden_origin", "request came from another site")
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

// sameSiteOK applies the Fetch Metadata rule described in guard.
func sameSiteOK(r *http.Request) bool {
	switch r.Header.Get("Sec-Fetch-Site") {
	case "", "same-origin", "none":
		return true
	}
	// From another site: only a plain visit to a page of the app, never the API or its files.
	navigate := r.Header.Get("Sec-Fetch-Mode") == "navigate"
	dest := r.Header.Get("Sec-Fetch-Dest")
	return navigate && (r.Method == http.MethodGet || r.Method == http.MethodHead) &&
		(dest == "" || dest == "document") && !strings.HasPrefix(r.URL.Path, "/api/")
}

func (s *server) recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if p := recover(); p != nil {
				s.Logf("web: panic in %s %s: %v", r.Method, r.URL.Path, p)
				fail(w, http.StatusInternalServerError, "internal", "Something went wrong on the atrium. Try again.")
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
		fail(w, http.StatusRequestEntityTooLarge, "too_large", "That is too large to save.")
	case errors.Is(err, vault.ErrTooLarge):
		fail(w, http.StatusRequestEntityTooLarge, "too_large", fmt.Sprintf("This note is too large to open here (over %d MB).", vault.MaxNoteSize>>20))
	case errors.Is(err, vault.ErrNotFound), errors.Is(err, vault.ErrNoNote):
		fail(w, http.StatusNotFound, "not_found", "That note wasn't found.")
	case errors.Is(err, fs.ErrNotExist):
		fail(w, http.StatusNotFound, "missing", "This file is missing from your vault folder.")
	case errors.Is(err, vault.ErrConflict):
		fail(w, http.StatusConflict, "conflict", "This note changed since you opened it.")
	case errors.Is(err, vault.ErrExists), errors.Is(err, vault.ErrHasNote):
		fail(w, http.StatusConflict, "exists", "Something with that name is already there.")
	case errors.Is(err, vault.ErrBadPath):
		fail(w, http.StatusBadRequest, "bad_path", "That name or folder isn't allowed. Avoid slashes, colons and a leading dot.")
	case errors.Is(err, vault.ErrBadPolicy), errors.Is(err, settings.ErrInvalid):
		fail(w, http.StatusBadRequest, "invalid", sentence(strings.TrimPrefix(err.Error(), "settings: invalid value: ")))
	case errors.Is(err, errBadRequest):
		fail(w, http.StatusBadRequest, "bad_request", sentence(strings.TrimPrefix(err.Error(), "bad request: ")))
	default:
		s.Logf("web: internal error: %v", err)
		fail(w, http.StatusInternalServerError, "internal", "Something went wrong on the atrium. Try again.")
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

// sentence capitalises the first letter and ends with a full stop, so a message
// built from an error reads like the rest of the UI.
func sentence(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return s
	}
	if r, n := utf8.DecodeRuneInString(s); unicode.IsLower(r) {
		s = string(unicode.ToUpper(r)) + s[n:]
	}
	if !strings.HasSuffix(s, ".") && !strings.HasSuffix(s, "?") && !strings.HasSuffix(s, "!") {
		s += "."
	}
	return s
}
