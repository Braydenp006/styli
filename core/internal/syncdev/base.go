package syncdev

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"

	"styli/core/internal/atomicfile"
	"styli/core/internal/vault"
)

// What a device keeps about sync, inside its own vault so it travels with the
// vault (see "What a device keeps" in DESIGN.md). None of it can be rebuilt,
// so a damaged file stops sync instead of being read as empty.
//
//	<vault>/.ids/sync/state/<id>   the group's base (gzipped JSON)
//	<vault>/.ids/sync/cursor       which atrium changes this device has handled

// Base is the last state of one group that this device and the atrium agreed
// on: the atrium's rev for it, its paths and hashes, and the note's text then
// (a merge needs it).
type Base struct {
	Rev     uint64       `json:"rev"`
	Note    *vault.File  `json:"note,omitempty"`
	Files   []vault.File `json:"files"`
	History *bool        `json:"history,omitempty"`
	Text    []byte       `json:"text,omitempty"`
}

// Group returns the base as a vault group, for comparing states.
func (b *Base) Group(id string) vault.Group {
	return vault.Group{ID: id, Note: b.Note, Files: b.Files, History: b.History, Rev: b.Rev}
}

// Cursor is the newest atrium change this device has handled, and which epoch
// (numbering) that rev belongs to.
type Cursor struct {
	Epoch string `json:"epoch"`
	Since uint64 `json:"since"`
}

type records struct{ dir string } // <vault>/.ids/sync

func newRecords(vaultRoot string) records {
	return records{filepath.Join(vaultRoot, ".ids", "sync")}
}

func (r records) basePath(id string) string { return filepath.Join(r.dir, "state", id) }

// base returns the group's base, or nil if it has never synced.
func (r records) base(id string) (*Base, error) {
	f, err := os.Open(r.basePath(id))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return nil, fmt.Errorf("sync: base of %s is damaged: %w", id, err)
	}
	var b Base
	if err := json.NewDecoder(zr).Decode(&b); err != nil {
		return nil, fmt.Errorf("sync: base of %s is damaged: %w", id, err)
	}
	return &b, nil
}

func (r records) saveBase(id string, b Base) error {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	if err := json.NewEncoder(zw).Encode(b); err != nil {
		return err
	}
	if err := zw.Close(); err != nil {
		return err
	}
	_, err := atomicfile.Write(r.basePath(id), &buf, true)
	return err
}

// baseIDs lists every group that has a base.
func (r records) baseIDs() ([]string, error) {
	ents, err := os.ReadDir(filepath.Join(r.dir, "state"))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var ids []string
	for _, e := range ents {
		if e.Type().IsRegular() && len(e.Name()) == 26 { // skips leftover temp files
			ids = append(ids, e.Name())
		}
	}
	return ids, nil
}

// cursor returns the saved cursor; a device that never synced has the zero one.
func (r records) cursor() (Cursor, error) {
	var c Cursor
	b, err := os.ReadFile(filepath.Join(r.dir, "cursor"))
	if errors.Is(err, fs.ErrNotExist) {
		return c, nil
	}
	if err != nil {
		return c, err
	}
	if err := json.Unmarshal(b, &c); err != nil {
		return c, fmt.Errorf("sync: the cursor is damaged: %w", err)
	}
	return c, nil
}

func (r records) saveCursor(c Cursor) error {
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	_, err = atomicfile.Write(filepath.Join(r.dir, "cursor"), bytes.NewReader(b), true)
	return err
}
