package store

import "strings"

// Hit is one search result.
type Hit struct {
	ID, Title, Path, Snippet string
}

// Search finds notes whose title, text or folder contain every word; the last
// word also matches as a prefix, so results appear while typing. Words are
// quoted, so characters like " - * ( never reach FTS5 as syntax.
func (s *Store) Search(query string, limit int) ([]Hit, error) {
	words := strings.Fields(query)
	if len(words) == 0 {
		return nil, nil
	}
	for i, w := range words {
		words[i] = `"` + strings.ReplaceAll(w, `"`, `""`) + `"`
	}
	words[len(words)-1] += "*"
	rows, err := s.db.Query(`
		SELECT g.id, g.title, COALESCE(g.note_path, ''), snippet(notes_fts, 1, '[', ']', '…', 12)
		FROM notes_fts JOIN groups g ON g.n = notes_fts.rowid
		WHERE notes_fts MATCH ? ORDER BY rank LIMIT ?`, strings.Join(words, " "), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var hits []Hit
	for rows.Next() {
		var h Hit
		if err := rows.Scan(&h.ID, &h.Title, &h.Path, &h.Snippet); err != nil {
			return nil, err
		}
		hits = append(hits, h)
	}
	return hits, rows.Err()
}
