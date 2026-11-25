package web

import (
	"errors"
	"mime"
	"net/http"
	"path"
	"strconv"
	"strings"
	"time"

	"styli/core/internal/store"
	"styli/core/internal/vault"
)

type hitDTO struct {
	ID       string    `json:"id"`
	Title    string    `json:"title"`
	Path     string    `json:"path"`
	Snippet  string    `json:"snippet"`
	Modified time.Time `json:"modified"`
}

// listNotes searches or browses: ?q= &folder= &tag= (repeatable) &from= &to= &limit= &offset=
func (s *server) listNotes(w http.ResponseWriter, r *http.Request) {
	qv := r.URL.Query()
	q := store.Query{Text: qv.Get("q"), Folder: qv.Get("folder"), Tags: qv["tag"]}
	var err error
	if q.From, err = parseTime(qv.Get("from"), false); err != nil {
		s.failErr(w, badRequest("from: %v", err))
		return
	}
	if q.To, err = parseTime(qv.Get("to"), true); err != nil {
		s.failErr(w, badRequest("to: %v", err))
		return
	}
	for name, dst := range map[string]*int{"limit": &q.Limit, "offset": &q.Offset} {
		if v := qv.Get(name); v != "" {
			if *dst, err = strconv.Atoi(v); err != nil {
				s.failErr(w, badRequest("%s must be a number", name))
				return
			}
		}
	}
	hits, err := s.Store.Find(q)
	if err != nil {
		s.failErr(w, err)
		return
	}
	out := make([]hitDTO, 0, len(hits))
	for _, h := range hits {
		out = append(out, hitDTO{h.ID, h.Title, h.Path, h.Snippet, h.Modified})
	}
	writeJSON(w, http.StatusOK, map[string]any{"notes": out})
}

// parseTime accepts 2026-03-05 or RFC 3339. A bare date as an upper bound
// means the end of that day.
func parseTime(s string, endOfDay bool) (time.Time, error) {
	if s == "" {
		return time.Time{}, nil
	}
	if t, err := time.Parse(time.DateOnly, s); err == nil {
		if endOfDay {
			t = t.Add(24*time.Hour - time.Second)
		}
		return t, nil
	}
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		return time.Time{}, errors.New("use 2026-03-05 or 2026-03-05T10:00:00Z")
	}
	return t, nil
}

type fileDTO struct {
	Index int    `json:"index"`
	Name  string `json:"name"`
	Path  string `json:"path"`
}

type noteDTO struct {
	ID             string    `json:"id"`
	HasNote        bool      `json:"has_note"`
	Path           string    `json:"path"` // "" until the group has a note
	Version        string    `json:"version"`
	Text           string    `json:"text"`
	Files          []fileDTO `json:"files"`
	HistoryEnabled bool      `json:"history_enabled"`
}

func (s *server) getNote(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	g, err := s.Vault.Get(id)
	if err != nil {
		s.failErr(w, err)
		return
	}
	n := noteDTO{ID: id, Files: make([]fileDTO, 0, len(g.Files))}
	for i, f := range g.Files {
		n.Files = append(n.Files, fileDTO{i, path.Base(f.Path), f.Path})
	}
	if g.Note != nil {
		text, ver, err := s.Vault.ReadNote(id)
		if err != nil {
			s.failErr(w, err)
			return
		}
		n.HasNote, n.Path, n.Version, n.Text = true, g.Note.Path, ver, string(text)
	}
	if n.HistoryEnabled, err = s.Vault.HistoryEnabled(id); err != nil {
		s.failErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, n)
}

// reindex brings one note's index entry up to date after a change. If it
// fails the change stands; the next startup reindex repairs the index.
func (s *server) reindex(id string) {
	if err := s.Store.Refresh(s.Vault, id); err != nil {
		s.Logf("web: index %s: %v", id, err)
	}
}

func (s *server) createNote(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path string `json:"path"`
		Text string `json:"text"`
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	g, err := s.Vault.CreateNote(req.Path, []byte(req.Text))
	if err != nil {
		s.failErr(w, err)
		return
	}
	s.reindex(g.ID)
	writeJSON(w, http.StatusCreated, map[string]string{"id": g.ID, "path": g.Note.Path, "version": g.Note.Version})
}

func (s *server) saveNote(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Text string `json:"text"`
		Base string `json:"base"`
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	id := r.PathValue("id")
	ver, err := s.Vault.WriteNote(id, []byte(req.Text), req.Base)
	if err != nil {
		s.failErr(w, err)
		return
	}
	s.reindex(id)
	writeJSON(w, http.StatusOK, map[string]string{"version": ver})
}

func (s *server) moveNote(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Folder string `json:"folder"`
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	id := r.PathValue("id")
	if err := s.Vault.Move(id, req.Folder); err != nil {
		s.failErr(w, err)
		return
	}
	s.reindex(id)
	g, _ := s.Vault.Get(id)
	p := ""
	if g.Note != nil {
		p = g.Note.Path
	}
	writeJSON(w, http.StatusOK, map[string]string{"path": p})
}

func (s *server) trashNote(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if err := s.Vault.Trash(id); err != nil {
		s.failErr(w, err)
		return
	}
	if err := s.Store.Remove(id); err != nil {
		s.Logf("web: index remove %s: %v", id, err)
	}
	w.WriteHeader(http.StatusNoContent)
}

// inlineTypes may be shown in the browser. Everything else is a download:
// SVG and HTML in particular can run scripts, so they never open inline.
var inlineTypes = map[string]string{
	".pdf": "application/pdf", ".png": "image/png", ".jpg": "image/jpeg",
	".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp",
}

// getFile streams one of the group's attached files, with range support so a
// big PDF can open at a given page. The file is chosen by its position in the
// group's record, never by a path from the request.
func (s *server) getFile(w http.ResponseWriter, r *http.Request) {
	n, err := strconv.Atoi(r.PathValue("n"))
	if err != nil {
		s.failErr(w, vault.ErrNotFound)
		return
	}
	f, p, err := s.Vault.OpenFile(r.PathValue("id"), n)
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
	name := path.Base(p)
	h := w.Header()
	if ct, ok := inlineTypes[strings.ToLower(path.Ext(name))]; ok {
		h.Set("Content-Type", ct)
		h.Set("Content-Disposition", mime.FormatMediaType("inline", map[string]string{"filename": name}))
	} else {
		h.Set("Content-Type", "application/octet-stream")
		h.Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": name}))
	}
	// Whatever is inside the file, it may not run scripts or touch the page.
	h.Set("Content-Security-Policy", "sandbox; default-src 'none'")
	h.Set("Cache-Control", "private, no-cache")
	http.ServeContent(w, r, name, fi.ModTime(), f)
}

type versionDTO struct {
	ID   string    `json:"id"`
	Time time.Time `json:"time"`
}

func (s *server) listVersions(w http.ResponseWriter, r *http.Request) {
	vs, err := s.Vault.History(r.PathValue("id"))
	if err != nil {
		s.failErr(w, err)
		return
	}
	out := make([]versionDTO, 0, len(vs))
	for _, v := range vs {
		out = append(out, versionDTO{v.ID, v.Time})
	}
	writeJSON(w, http.StatusOK, map[string]any{"versions": out})
}

func (s *server) getVersion(w http.ResponseWriter, r *http.Request) {
	b, err := s.Vault.ReadVersion(r.PathValue("id"), r.PathValue("vid"))
	if err != nil {
		s.failErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"text": string(b)})
}

func (s *server) restoreVersion(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Base string `json:"base"`
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	id := r.PathValue("id")
	ver, err := s.Vault.RestoreVersion(id, r.PathValue("vid"), req.Base)
	if err != nil {
		s.failErr(w, err)
		return
	}
	s.reindex(id)
	writeJSON(w, http.StatusOK, map[string]string{"version": ver})
}

func (s *server) setHistoryEnabled(w http.ResponseWriter, r *http.Request) {
	var req struct {
		On *bool `json:"on"` // null or absent: follow the hub's default
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	id := r.PathValue("id")
	if err := s.Vault.SetHistory(id, req.On); err != nil {
		s.failErr(w, err)
		return
	}
	on, err := s.Vault.HistoryEnabled(id)
	if err != nil {
		s.failErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"history_enabled": on})
}

func (s *server) listFolders(w http.ResponseWriter, r *http.Request) {
	fs, err := s.Store.Folders()
	if err != nil {
		s.failErr(w, err)
		return
	}
	if fs == nil {
		fs = []string{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"folders": fs})
}

func (s *server) listTags(w http.ResponseWriter, r *http.Request) {
	type tagDTO struct {
		Tag   string `json:"tag"`
		Count int    `json:"count"`
	}
	ts, err := s.Store.Tags()
	if err != nil {
		s.failErr(w, err)
		return
	}
	out := make([]tagDTO, 0, len(ts))
	for _, t := range ts {
		out = append(out, tagDTO{t.Tag, t.Count})
	}
	writeJSON(w, http.StatusOK, map[string]any{"tags": out})
}
