package web

import (
	"embed"
	"io/fs"
	"mime"
	"net/http"

	"styli/core/internal/mdrender"
)

// The web UI is plain files in ui/, built into the binary. index.html is
// served at "/" and everything else under /static/.
//
//go:embed ui
var uiFiles embed.FS

func init() {
	// Go's built-in table may not know this one, and the CSP's nosniff means a
	// guess would not be corrected.
	mime.AddExtensionType(".woff2", "font/woff2")
}

// noDirs makes directories look missing, so /static/ shows no file listing.
type noDirs struct{ fs.FS }

func (n noDirs) Open(name string) (fs.File, error) {
	f, err := n.FS.Open(name)
	if err != nil {
		return nil, err
	}
	if fi, err := f.Stat(); err != nil || fi.IsDir() {
		f.Close()
		return nil, fs.ErrNotExist
	}
	return f, nil
}

func (s *server) staticRoutes(mux *http.ServeMux) {
	root, err := fs.Sub(uiFiles, "ui")
	if err != nil {
		panic(err) // the embed is part of the binary; this cannot fail at run time
	}
	root = noDirs{root}
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		http.ServeFileFS(w, r, root, "index.html")
	})
	mux.Handle("GET /static/", http.FileServerFS(root))
	mux.HandleFunc("GET /ca.crt", s.caCert)
}

// CA is the atrium's own certificate authority, for phones to install.
type CA struct {
	DER         []byte
	Fingerprint string // SHA-256, as phones show it: "AB CD …"
}

// caCert hands out the atrium's CA certificate. It needs no sign-in: a phone
// installs it before it can reach the atrium without a warning, and a CA
// certificate is public by nature (the person checks its fingerprint).
func (s *server) caCert(w http.ResponseWriter, r *http.Request) {
	if s.CA == nil {
		fail(w, http.StatusNotFound, "no_ca", "This atrium doesn't use its own certificate.")
		return
	}
	w.Header().Set("Content-Type", "application/x-x509-ca-cert")
	w.Header().Set("Content-Disposition", `attachment; filename="styli-atrium.crt"`)
	w.Write(s.CA.DER)
}

// render turns a note's Markdown into safe HTML (see mdrender).
func (s *server) render(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Text string `json:"text"`
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	out, err := mdrender.Render([]byte(req.Text))
	if err != nil {
		s.failErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"html": string(out)})
}
