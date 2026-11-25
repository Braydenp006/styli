// Package store is the search index: a SQLite database that mirrors the vault.
// The vault folder is the truth; everything here can be rebuilt with Reindex.
package store

import (
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"net/url"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite" // pure-Go driver, registers "sqlite"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Store is the index database.
type Store struct{ db *sql.DB }

// DataDir is the default folder for a vault's index: the user cache folder,
// in a subfolder named from the vault's path.
func DataDir(vaultRoot string) (string, error) {
	abs, err := filepath.Abs(vaultRoot)
	if err != nil {
		return "", err
	}
	cache, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(abs))
	return filepath.Join(cache, "styli", hex.EncodeToString(sum[:4])), nil
}

// Open opens (creating and migrating if needed) the index in dir.
func Open(dir string) (*Store, error) {
	db, path, err := openDB(dir)
	if err != nil {
		return nil, err
	}
	if err := migrate(db, path, migrationFiles); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db}, nil
}

// Close closes the database.
func (s *Store) Close() error { return s.db.Close() }

func openDB(dir string) (*sql.DB, string, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, "", err
	}
	path := filepath.Join(dir, "index.db")
	// WAL for concurrent readers, foreign keys for the cascades, and a wait
	// instead of an immediate "database is locked".
	dsn := (&url.URL{Scheme: "file", Path: path}).String() +
		"?_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, "", err
	}
	return db, path, nil
}
