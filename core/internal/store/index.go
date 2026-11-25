package store

import (
	"database/sql"
	"errors"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"styli/core/internal/vault"
)

// Upsert records one group in the index. content is the note's text, or nil if
// the group has no note yet (or the file is missing). modified is the note
// file's modified time. Moving a note keeps its row; only the paths change.
func (s *Store) Upsert(g vault.Group, content []byte, modified time.Time) error {
	if modified.IsZero() {
		modified = time.Now()
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var notePath, noteVer any // nil stores NULL
	if g.Note != nil {
		notePath, noteVer = g.Note.Path, g.Note.Version
	}
	title := titleOf(g, content)

	var n int64
	err = tx.QueryRow(`SELECT n FROM groups WHERE id = ?`, g.ID).Scan(&n)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		res, err := tx.Exec(`INSERT INTO groups (id, note_path, note_version, title, created_at, modified_at)
			VALUES (?, ?, ?, ?, ?, ?)`, g.ID, notePath, noteVer, title, time.Now().Unix(), modified.Unix())
		if err != nil {
			return err
		}
		if n, err = res.LastInsertId(); err != nil {
			return err
		}
	case err != nil:
		return err
	default:
		if _, err := tx.Exec(`UPDATE groups SET note_path = ?, note_version = ?, title = ?, modified_at = ?
			WHERE n = ?`, notePath, noteVer, title, modified.Unix(), n); err != nil {
			return err
		}
	}

	if _, err := tx.Exec(`DELETE FROM files WHERE group_n = ?`, n); err != nil {
		return err
	}
	for _, f := range g.Files {
		if _, err := tx.Exec(`INSERT INTO files (group_n, path, version) VALUES (?, ?, ?)`, n, f.Path, f.Version); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`DELETE FROM tags WHERE group_n = ?`, n); err != nil {
		return err
	}
	for _, t := range parseTags(content) {
		if _, err := tx.Exec(`INSERT INTO tags (group_n, tag) VALUES (?, ?)`, n, t); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`DELETE FROM notes_fts WHERE rowid = ?`, n); err != nil {
		return err
	}
	if content != nil {
		folder := ""
		if g.Note != nil && path.Dir(g.Note.Path) != "." {
			folder = path.Dir(g.Note.Path)
		}
		if _, err := tx.Exec(`INSERT INTO notes_fts (rowid, title, body, folder) VALUES (?, ?, ?, ?)`,
			n, title, string(content), folder); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// Remove drops a group from the index (the vault is not touched).
func (s *Store) Remove(id string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var n int64
	err = tx.QueryRow(`SELECT n FROM groups WHERE id = ?`, id).Scan(&n)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM notes_fts WHERE rowid = ?`, n); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM groups WHERE n = ?`, n); err != nil { // files and tags cascade
		return err
	}
	return tx.Commit()
}

// Reindex makes the index match the vault: it reads every note from disk (so
// outside edits are picked up), updates each group and drops groups the vault
// no longer has.
// shortcut: rereads every note on each call; fine at startup, the file watcher
// takes over for live changes.
func (s *Store) Reindex(v *vault.Vault) error {
	groups, err := v.List()
	if err != nil {
		return err
	}
	keep := map[string]bool{}
	for _, g := range groups {
		keep[g.ID] = true
		var content []byte
		var mod time.Time
		if g.Note != nil {
			b, ver, err := v.ReadNote(g.ID)
			switch {
			case err == nil:
				content = b
				g.Note.Version = ver // the real file, not the record
				if fi, err := os.Stat(filepath.Join(v.Root(), filepath.FromSlash(g.Note.Path))); err == nil {
					mod = fi.ModTime()
				}
			case errors.Is(err, fs.ErrNotExist): // note file missing: index it without text
			default:
				return err
			}
		}
		if err := s.Upsert(g, content, mod); err != nil {
			return err
		}
	}
	rows, err := s.db.Query(`SELECT id FROM groups`)
	if err != nil {
		return err
	}
	var stale []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		if !keep[id] {
			stale = append(stale, id)
		}
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	for _, id := range stale {
		if err := s.Remove(id); err != nil {
			return err
		}
	}
	return nil
}

// titleOf is the note's first "# " heading, else its file name without .md,
// else (no note yet) the first source file's name without its extension.
func titleOf(g vault.Group, content []byte) string {
	for _, line := range strings.Split(string(content), "\n") {
		if t, ok := strings.CutPrefix(line, "# "); ok && strings.TrimSpace(t) != "" {
			return strings.TrimSpace(t)
		}
	}
	p := ""
	switch {
	case g.Note != nil:
		p = g.Note.Path
	case len(g.Files) > 0:
		p = g.Files[0].Path
	}
	base := path.Base(p)
	return strings.TrimSuffix(base, path.Ext(base))
}

var tagsRe = regexp.MustCompile(`(?s)<!--\s*styli:tags\b(.*?)-->`)

// parseTags reads every <!-- styli:tags a, b --> comment: lowercased,
// trimmed, de-duplicated and sorted.
func parseTags(content []byte) []string {
	seen := map[string]bool{}
	var out []string
	for _, m := range tagsRe.FindAllSubmatch(content, -1) {
		for _, t := range strings.Split(string(m[1]), ",") {
			t = strings.ToLower(strings.TrimLeft(strings.TrimSpace(t), "#"))
			if t != "" && !seen[t] {
				seen[t] = true
				out = append(out, t)
			}
		}
	}
	sort.Strings(out)
	return out
}
