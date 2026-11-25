package store

import (
	"database/sql"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"sort"
	"strconv"
	"strings"
)

// ErrNewer means the database was written by a newer build. We refuse to
// touch it rather than guess, so an old binary can't damage new data.
var ErrNewer = errors.New("store: database is newer than this build")

type migration struct {
	version int
	sql     string
}

// loadMigrations reads migrations/NNNN_name.sql files, ordered by number.
func loadMigrations(fsys fs.FS) ([]migration, error) {
	names, err := fs.Glob(fsys, "migrations/*.sql")
	if err != nil {
		return nil, err
	}
	var ms []migration
	for _, name := range names {
		base := name[strings.LastIndex(name, "/")+1:]
		num, _, _ := strings.Cut(base, "_")
		v, err := strconv.Atoi(num)
		if err != nil {
			return nil, fmt.Errorf("store: bad migration name %q", name)
		}
		b, err := fs.ReadFile(fsys, name)
		if err != nil {
			return nil, err
		}
		ms = append(ms, migration{v, string(b)})
	}
	sort.Slice(ms, func(i, j int) bool { return ms[i].version < ms[j].version })
	return ms, nil
}

// migrate brings the database up to the newest migration. Each migration runs
// in its own transaction, and PRAGMA user_version records how far we got.
// Before changing an existing database it copies the file to <path>.bak-v<N>.
func migrate(db *sql.DB, path string, fsys fs.FS) error {
	ms, err := loadMigrations(fsys)
	if err != nil {
		return err
	}
	var cur int
	if err := db.QueryRow(`PRAGMA user_version`).Scan(&cur); err != nil {
		return err
	}
	latest := 0
	if len(ms) > 0 {
		latest = ms[len(ms)-1].version
	}
	if cur > latest {
		return fmt.Errorf("%w (database v%d, build v%d)", ErrNewer, cur, latest)
	}
	if cur == latest {
		return nil
	}
	if cur > 0 {
		if err := backup(db, path, cur); err != nil {
			return err
		}
	}
	for _, m := range ms {
		if m.version <= cur {
			continue
		}
		tx, err := db.Begin()
		if err != nil {
			return err
		}
		_, err = tx.Exec(m.sql)
		if err == nil {
			_, err = tx.Exec(fmt.Sprintf(`PRAGMA user_version = %d`, m.version))
		}
		if err != nil {
			tx.Rollback()
			return fmt.Errorf("store: migration %d: %w", m.version, err)
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

func backup(db *sql.DB, path string, version int) error {
	// Fold the WAL into the main file so the copy is complete.
	if _, err := db.Exec(`PRAGMA wal_checkpoint(TRUNCATE)`); err != nil {
		return err
	}
	src, err := os.Open(path)
	if err != nil {
		return err
	}
	defer src.Close()
	dst, err := os.Create(fmt.Sprintf("%s.bak-v%d", path, version))
	if err != nil {
		return err
	}
	if _, err := io.Copy(dst, src); err != nil {
		dst.Close()
		return err
	}
	if err := dst.Sync(); err != nil {
		dst.Close()
		return err
	}
	return dst.Close()
}
