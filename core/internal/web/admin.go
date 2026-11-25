package web

import (
	"context"
	"net/http"
	"runtime"
	"time"

	"styli/core/internal/maid"
)

type trashDTO struct {
	ID        string    `json:"id"`
	NotePath  string    `json:"note_path"`
	Folder    string    `json:"folder"`
	Files     []string  `json:"files"`
	DeletedAt time.Time `json:"deleted_at"`
}

func (s *server) listTrash(w http.ResponseWriter, r *http.Request) {
	tr, err := s.Vault.ListTrash()
	if err != nil && len(tr) == 0 {
		s.failErr(w, err)
		return
	}
	if err != nil { // some entries were unreadable: show the rest, log the problem
		s.Logf("web: trash: %v", err)
	}
	out := make([]trashDTO, 0, len(tr))
	for _, t := range tr {
		d := trashDTO{ID: t.Group.ID, Folder: t.Folder, DeletedAt: t.DeletedAt, Files: []string{}}
		if t.Group.Note != nil {
			d.NotePath = t.Group.Note.Path
		}
		for _, f := range t.Group.Files {
			d.Files = append(d.Files, f.Path)
		}
		out = append(out, d)
	}
	writeJSON(w, http.StatusOK, map[string]any{"trash": out})
}

func (s *server) restoreTrash(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Folder string `json:"folder"` // "" restores each file to where it was
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	id := r.PathValue("id")
	if err := s.Vault.Restore(id, req.Folder); err != nil {
		s.failErr(w, err)
		return
	}
	s.reindex(id)
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) deleteForever(w http.ResponseWriter, r *http.Request) {
	if err := s.Vault.DeleteForever(r.PathValue("id")); err != nil {
		s.failErr(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) listMissing(w http.ResponseWriter, r *http.Request) {
	type missingDTO struct {
		ID    string   `json:"id"`
		Paths []string `json:"paths"`
	}
	ms, err := s.Vault.Missing()
	if err != nil {
		s.failErr(w, err)
		return
	}
	out := make([]missingDTO, 0, len(ms))
	for _, m := range ms {
		out = append(out, missingDTO{m.ID, m.Paths})
	}
	writeJSON(w, http.StatusOK, map[string]any{"missing": out})
}

type jobDTO struct {
	Deleted     int    `json:"deleted"`
	WouldDelete int    `json:"would_delete"`
	Total       int    `json:"total"`
	HeldBack    bool   `json:"held_back"`
	Skipped     string `json:"skipped,omitempty"`
	Error       string `json:"error,omitempty"`
}

type runDTO struct {
	Time               time.Time `json:"time"`
	Preview            bool      `json:"preview"`
	Forced             bool      `json:"forced"`
	TimeCheck          string    `json:"time_check"`
	ClockOffsetSeconds float64   `json:"clock_offset_seconds"`
	TimeCheckError     string    `json:"time_check_error,omitempty"`
	History            jobDTO    `json:"history"`
	Trash              jobDTO    `json:"trash"`
	NeedsAttention     bool      `json:"needs_attention"`
}

func toRunDTO(r maid.Result) runDTO {
	job := func(j maid.JobResult) jobDTO {
		return jobDTO{j.Deleted, j.WouldDelete, j.Total, j.HeldBack, j.Skipped, j.Err}
	}
	return runDTO{r.Time, r.Preview, r.Forced, r.TimeCheck, r.ClockOffset.Seconds(), r.TimeCheckErr,
		job(r.History), job(r.Trash), r.NeedsAttention()}
}

// hubOS names the operating system the hub runs on, the way a person would.
func hubOS() string {
	switch runtime.GOOS {
	case "linux":
		return "Linux"
	case "freebsd":
		return "FreeBSD"
	case "windows":
		return "Windows"
	case "darwin":
		return "macOS"
	case "android":
		return "Android"
	}
	return runtime.GOOS
}

type hubDTO struct {
	Name    string `json:"name"`
	OS      string `json:"os"`
	Version string `json:"version"`
	Vault   string `json:"vault"` // the vault folder, so the dashboard can remind about backups
	Notes   int    `json:"notes"`
	Files   int    `json:"files"`
}

func (s *server) status(w http.ResponseWriter, r *http.Request) {
	st := s.Maid.Status()
	hub := hubDTO{Name: s.Settings.Hub().Name, OS: hubOS(), Version: s.Version, Vault: s.Vault.Root()}
	if n, f, err := s.Store.Counts(); err == nil {
		hub.Notes, hub.Files = n, f
	} else {
		s.Logf("web: counts: %v", err)
	}
	out := map[string]any{"maid_running": st.Running, "last_maid_run": nil, "hub": hub}
	if st.Last != nil {
		out["last_maid_run"] = toRunDTO(*st.Last)
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *server) maidPreview(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, toRunDTO(s.Maid.Preview(r.Context())))
}

// maidRun runs the maid now. force approves a held-back run; it never
// overrides a failed clock check (the maid enforces that).
func (s *server) maidRun(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Force bool `json:"force"`
	}
	if err := readJSON(w, r, &req); err != nil {
		s.failErr(w, err)
		return
	}
	// Not tied to the request: if the browser tab closes mid-run the run still
	// finishes, so it never stops halfway through a deletion.
	writeJSON(w, http.StatusOK, toRunDTO(s.Maid.RunNow(context.WithoutCancel(r.Context()), req.Force)))
}

func (s *server) settingsJSON() map[string]any {
	return map[string]any{
		"hub":                  s.Settings.Hub(),
		"device":               s.Settings.Device(),
		"trash_retention_days": int(s.Settings.TrashRetention() / (24 * time.Hour)),
	}
}

func (s *server) getSettings(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.settingsJSON())
}

// putHub and putDevice start from the current settings, so a request only has
// to send the fields it changes.
func (s *server) putHub(w http.ResponseWriter, r *http.Request) {
	h := s.Settings.Hub()
	if err := readJSON(w, r, &h); err != nil {
		s.failErr(w, err)
		return
	}
	if err := s.Settings.SetHub(h); err != nil {
		s.failErr(w, err)
		return
	}
	if err := s.Settings.ApplyTo(s.Vault); err != nil {
		s.failErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, s.settingsJSON())
}

func (s *server) putDevice(w http.ResponseWriter, r *http.Request) {
	d := s.Settings.Device()
	if err := readJSON(w, r, &d); err != nil {
		s.failErr(w, err)
		return
	}
	if err := s.Settings.SetDevice(d); err != nil {
		s.failErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, s.settingsJSON())
}
