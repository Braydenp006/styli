package store

import (
	"sort"
	"strings"
	"time"
)

// Hit is one note (or one PDF-first group) in a search or browse result.
type Hit struct {
	ID, Title string
	Path      string // the note's path, or the first file's if it has no note yet
	Snippet   string // the matching text, with [brackets] round matches; empty when browsing
	Modified  time.Time
}

// Query asks for notes. Every part is optional and they combine with AND.
// With no Text the result is a plain list, newest first.
type Query struct {
	Text     string    // words that must all appear (title, text or folder); the last also matches as a prefix
	Folder   string    // this folder and everything below it; "" means anywhere
	Tags     []string  // every one of these tags
	From, To time.Time // modified time, inclusive; zero means unbounded
	Limit    int       // default 50, at most 500
	Offset   int
}

// Find runs a query. Search words are quoted, so characters like " - * ( never
// reach FTS5 as syntax.
func (s *Store) Find(q Query) ([]Hit, error) {
	sel := `g.id, g.title, COALESCE(g.note_path, (SELECT MIN(path) FROM files WHERE group_n = g.n), ''), g.modified_at`
	from, snippet, order := `groups g`, `''`, `g.modified_at DESC, g.id`
	var where []string
	var args []any

	if match := ftsQuery(q.Text); match != "" {
		from = `notes_fts JOIN groups g ON g.n = notes_fts.rowid`
		snippet = `snippet(notes_fts, 1, '[', ']', '…', 12)`
		order = `rank, g.id`
		where = append(where, `notes_fts MATCH ?`)
		args = append(args, match)
	}
	if f := strings.Trim(q.Folder, "/"); f != "" && f != "." {
		// No LIKE here: folder names may contain % and _.
		where = append(where, `(g.folder = ? OR substr(g.folder, 1, ?) = ?)`)
		args = append(args, f, len(f)+1, f+"/")
	}
	for _, t := range q.Tags {
		if t = strings.ToLower(strings.TrimLeft(strings.TrimSpace(t), "#")); t != "" {
			where = append(where, `EXISTS (SELECT 1 FROM tags t WHERE t.group_n = g.n AND t.tag = ?)`)
			args = append(args, t)
		}
	}
	if !q.From.IsZero() {
		where = append(where, `g.modified_at >= ?`)
		args = append(args, q.From.Unix())
	}
	if !q.To.IsZero() {
		where = append(where, `g.modified_at <= ?`)
		args = append(args, q.To.Unix())
	}

	sql := `SELECT ` + sel + `, ` + snippet + ` FROM ` + from
	if len(where) > 0 {
		sql += ` WHERE ` + strings.Join(where, ` AND `)
	}
	limit := q.Limit
	if limit <= 0 {
		limit = 50
	}
	limit = min(limit, 500)
	sql += ` ORDER BY ` + order + ` LIMIT ? OFFSET ?`
	args = append(args, limit, max(q.Offset, 0))

	rows, err := s.db.Query(sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var hits []Hit
	for rows.Next() {
		var h Hit
		var mod int64
		if err := rows.Scan(&h.ID, &h.Title, &h.Path, &mod, &h.Snippet); err != nil {
			return nil, err
		}
		h.Modified = time.Unix(mod, 0)
		hits = append(hits, h)
	}
	return hits, rows.Err()
}

// Search is Find with just words and a limit.
func (s *Store) Search(text string, limit int) ([]Hit, error) {
	return s.Find(Query{Text: text, Limit: limit})
}

// ftsQuery quotes each word as a phrase and makes the last one a prefix match.
func ftsQuery(text string) string {
	words := strings.Fields(text)
	if len(words) == 0 {
		return ""
	}
	for i, w := range words {
		words[i] = `"` + strings.ReplaceAll(w, `"`, `""`) + `"`
	}
	words[len(words)-1] += "*"
	return strings.Join(words, " ")
}

// Folders lists every folder that holds a note or file, plus their parents,
// sorted. The vault root is not listed.
func (s *Store) Folders() ([]string, error) {
	rows, err := s.db.Query(`SELECT DISTINCT folder FROM groups WHERE folder != ''`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	set := map[string]bool{}
	for rows.Next() {
		var f string
		if err := rows.Scan(&f); err != nil {
			return nil, err
		}
		for ; f != "" && f != "."; f = f[:max(strings.LastIndex(f, "/"), 0)] {
			set[f] = true
		}
	}
	out := make([]string, 0, len(set))
	for f := range set {
		out = append(out, f)
	}
	sort.Strings(out)
	return out, rows.Err()
}

// TagCount is a tag and how many notes carry it.
type TagCount struct {
	Tag   string
	Count int
}

// Tags lists every tag, most used first.
func (s *Store) Tags() ([]TagCount, error) {
	rows, err := s.db.Query(`SELECT tag, count(*) FROM tags GROUP BY tag ORDER BY 2 DESC, 1`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []TagCount
	for rows.Next() {
		var t TagCount
		if err := rows.Scan(&t.Tag, &t.Count); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}
