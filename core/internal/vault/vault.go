// Package vault stores notes as plain files in a folder.
//
// Every note has a permanent ID that names a group: the Markdown note plus the
// source files it came from (PDFs, images). A group lives in one folder and
// always moves as one. The group's paths and content hashes are kept in
// <root>/.ids/<id>, so IDs survive moves and can be rebuilt from the folder.
package vault

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

const (
	idsDir    = ".ids"
	tmpPrefix = ".styli-tmp-"
)

var (
	ErrNotFound = errors.New("vault: not found")
	ErrExists   = errors.New("vault: already exists")
	ErrBadPath  = errors.New("vault: bad path")
	ErrConflict = errors.New("vault: note changed since the given version")
	ErrNoNote   = errors.New("vault: group has no note yet")
	ErrHasNote  = errors.New("vault: group already has a note")
)

var idRe = regexp.MustCompile(`^[a-z2-7]{26}$`)

// File is one file in the vault. Path is relative to the vault root and uses
// forward slashes. Version is the hex sha256 of its content.
type File struct {
	Path    string `json:"path"`
	Version string `json:"version"`
}

// Group is one ID: an optional Markdown note plus its source files. A group
// created from a PDF has no Note until the Markdown is added.
type Group struct {
	ID    string `json:"-"`
	Note  *File  `json:"note,omitempty"`
	Files []File `json:"files"`
}

// Vault is a folder of notes. Safe for concurrent use inside one process.
type Vault struct {
	root string
	mu   sync.Mutex
}

// Open opens (and if needed creates) a vault, and repairs any group that a
// crash or an outside move has split from its record.
func Open(root string) (*Vault, error) {
	if err := os.MkdirAll(filepath.Join(root, idsDir), 0o755); err != nil {
		return nil, err
	}
	v := &Vault{root: root}
	if err := v.repair(); err != nil {
		return nil, err
	}
	return v, nil
}

// CreateNote makes a new group from a Markdown note. p must end in .md.
func (v *Vault) CreateNote(p string, content []byte) (Group, error) {
	if !isMD(p) {
		return Group{}, ErrBadPath
	}
	return v.create(p, bytes.NewReader(content), true)
}

// ImportFile makes a new group from a source file (for example a PDF). The
// Markdown joins the group later with SetNote.
func (v *Vault) ImportFile(p string, r io.Reader) (Group, error) {
	if isMD(p) {
		return Group{}, ErrBadPath
	}
	return v.create(p, r, false)
}

func (v *Vault) create(p string, r io.Reader, isNote bool) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	abs, err := v.abs(p)
	if err != nil {
		return Group{}, err
	}
	// File first, record second: a crash in between leaves an unrecorded file
	// (the later watcher can adopt it), never a record pointing at nothing.
	ver, err := writeAtomic(abs, r, false)
	if err != nil {
		return Group{}, err
	}
	g := Group{ID: newID(), Files: []File{}}
	if isNote {
		g.Note = &File{p, ver}
	} else {
		g.Files = append(g.Files, File{p, ver})
	}
	return g, v.save(g)
}

// AddFile stores a source file in the group's folder.
func (v *Vault) AddFile(id, name string, r io.Reader) (Group, error) {
	if isMD(name) {
		return Group{}, ErrBadPath
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	g, p, err := v.inGroupFolder(id, name)
	if err != nil {
		return Group{}, err
	}
	abs, err := v.abs(p)
	if err != nil {
		return Group{}, err
	}
	ver, err := writeAtomic(abs, r, false)
	if err != nil {
		return Group{}, err
	}
	g.Files = append(g.Files, File{p, ver})
	return g, v.save(g)
}

// SetNote adds the Markdown note to a group that has none (for example after
// the AI converts an imported PDF).
func (v *Vault) SetNote(id, name string, content []byte) (Group, error) {
	if !isMD(name) {
		return Group{}, ErrBadPath
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	g, p, err := v.inGroupFolder(id, name)
	if err != nil {
		return Group{}, err
	}
	if g.Note != nil {
		return Group{}, ErrHasNote
	}
	abs, err := v.abs(p)
	if err != nil {
		return Group{}, err
	}
	ver, err := writeAtomic(abs, bytes.NewReader(content), false)
	if err != nil {
		return Group{}, err
	}
	g.Note = &File{p, ver}
	return g, v.save(g)
}

// inGroupFolder loads a group and builds the path for a new file name in its folder.
func (v *Vault) inGroupFolder(id, name string) (Group, string, error) {
	if name == "." || strings.Contains(name, "/") || !fs.ValidPath(name) {
		return Group{}, "", ErrBadPath
	}
	g, err := v.load(id)
	if err != nil {
		return Group{}, "", err
	}
	return g, path.Join(path.Dir(g.all()[0].Path), name), nil
}

// Get returns one group.
func (v *Vault) Get(id string) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.load(id)
}

// List returns every group, ordered by ID.
func (v *Vault) List() ([]Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.list()
}

// ReadNote returns the note's content and its version.
func (v *Vault) ReadNote(id string) ([]byte, string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.readNote(id)
}

func (v *Vault) readNote(id string) ([]byte, string, error) {
	g, err := v.load(id)
	if err != nil {
		return nil, "", err
	}
	if g.Note == nil {
		return nil, "", ErrNoNote
	}
	abs, err := v.abs(g.Note.Path)
	if err != nil {
		return nil, "", err
	}
	b, err := os.ReadFile(abs)
	if err != nil {
		return nil, "", err
	}
	return b, hashBytes(b), nil
}

// WriteNote replaces the note's content. base must be the version the caller
// last read; if the file has changed since, it fails with ErrConflict and
// writes nothing. It returns the new version.
func (v *Vault) WriteNote(id string, content []byte, base string) (string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	_, cur, err := v.readNote(id)
	if err != nil {
		return "", err
	}
	if cur != base {
		return "", ErrConflict
	}
	g, _ := v.load(id)
	abs, err := v.abs(g.Note.Path)
	if err != nil {
		return "", err
	}
	ver, err := writeAtomic(abs, bytes.NewReader(content), true)
	if err != nil {
		return "", err
	}
	g.Note.Version = ver
	return ver, v.save(g)
}

// Move moves the whole group into folder ("" or "." is the vault root). If any
// destination name is taken, nothing moves.
func (v *Vault) Move(id, folder string) error {
	if folder == "" {
		folder = "."
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return err
	}
	dstDir, err := v.abs(folder)
	if err != nil {
		return err
	}
	type mv struct {
		f        *File
		to       string
		from, ta string
	}
	var mvs []mv
	seen := map[string]bool{}
	for _, f := range g.all() {
		to := path.Join(folder, path.Base(f.Path))
		if to == f.Path {
			continue
		}
		from, err := v.abs(f.Path)
		if err != nil {
			return err
		}
		ta, err := v.abs(to)
		if err != nil {
			return err
		}
		if _, err := os.Lstat(ta); err == nil || seen[to] {
			return ErrExists
		}
		seen[to] = true
		mvs = append(mvs, mv{f, to, from, ta})
	}
	if err := os.MkdirAll(dstDir, 0o755); err != nil {
		return err
	}
	for i, m := range mvs {
		if err := os.Rename(m.from, m.ta); err != nil {
			for _, d := range mvs[:i] { // undo, so the group is not left split
				os.Rename(d.ta, d.from)
			}
			return err
		}
	}
	for _, m := range mvs {
		m.f.Path = m.to
	}
	return v.save(g)
}

// all returns pointers to every file in the group, note first.
func (g *Group) all() []*File {
	var out []*File
	if g.Note != nil {
		out = append(out, g.Note)
	}
	for i := range g.Files {
		out = append(out, &g.Files[i])
	}
	return out
}

func (v *Vault) load(id string) (Group, error) {
	if !idRe.MatchString(id) {
		return Group{}, ErrNotFound
	}
	b, err := os.ReadFile(filepath.Join(v.root, idsDir, id))
	if errors.Is(err, fs.ErrNotExist) {
		return Group{}, ErrNotFound
	}
	if err != nil {
		return Group{}, err
	}
	g := Group{ID: id}
	if err := json.Unmarshal(b, &g); err != nil {
		return Group{}, err
	}
	return g, nil
}

func (v *Vault) save(g Group) error {
	b, err := json.Marshal(g)
	if err != nil {
		return err
	}
	_, err = writeAtomic(filepath.Join(v.root, idsDir, g.ID), bytes.NewReader(b), true)
	return err
}

func (v *Vault) list() ([]Group, error) {
	ents, err := os.ReadDir(filepath.Join(v.root, idsDir))
	if err != nil {
		return nil, err
	}
	var gs []Group
	for _, e := range ents {
		if !idRe.MatchString(e.Name()) { // skips leftover temp files
			continue
		}
		g, err := v.load(e.Name())
		if err != nil {
			return nil, err
		}
		gs = append(gs, g)
	}
	return gs, nil
}

// repair re-finds files that moved without their record being updated (a
// crash mid-move, or an edit outside the app) by matching content hashes.
// shortcut: hashes the whole vault, only when a file is missing; the file
// watcher replaces this for live changes.
func (v *Vault) repair() error {
	gs, err := v.list()
	if err != nil {
		return err
	}
	claimed := map[string]bool{}
	missing := false
	for i := range gs {
		for _, f := range gs[i].all() {
			claimed[f.Path] = true
			abs, err := v.abs(f.Path)
			if err != nil {
				return err
			}
			if _, err := os.Lstat(abs); errors.Is(err, fs.ErrNotExist) {
				missing = true
			}
		}
	}
	if !missing {
		return nil
	}
	byHash := map[string]string{}
	err = filepath.WalkDir(v.root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() && d.Name() == idsDir && p != v.root {
			return fs.SkipDir
		}
		if !d.Type().IsRegular() || strings.HasPrefix(d.Name(), tmpPrefix) {
			return nil
		}
		rel, _ := filepath.Rel(v.root, p)
		rel = filepath.ToSlash(rel)
		if claimed[rel] {
			return nil
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		byHash[hashBytes(b)] = rel
		return nil
	})
	if err != nil {
		return err
	}
	for _, g := range gs {
		dirty := false
		for _, f := range g.all() {
			abs, _ := v.abs(f.Path)
			if _, err := os.Lstat(abs); !errors.Is(err, fs.ErrNotExist) {
				continue
			}
			if p, ok := byHash[f.Version]; ok {
				f.Path = p
				delete(byHash, f.Version)
				dirty = true
			}
		}
		if dirty {
			if err := v.save(g); err != nil {
				return err
			}
		}
	}
	return nil
}

// abs turns a vault-relative path ("." is the root) into a disk path, and
// rejects anything that could leave the vault or touch internals.
func (v *Vault) abs(rel string) (string, error) {
	if rel != "." && (!fs.ValidPath(rel) || strings.ContainsAny(rel, `\:`)) {
		return "", ErrBadPath
	}
	cur := v.root
	if rel == "." {
		return cur, nil
	}
	for i, part := range strings.Split(rel, "/") {
		if (i == 0 && part == idsDir) || strings.HasPrefix(part, tmpPrefix) {
			return "", ErrBadPath
		}
		cur = filepath.Join(cur, part)
		fi, err := os.Lstat(cur)
		if errors.Is(err, fs.ErrNotExist) {
			continue
		}
		if err != nil {
			return "", err
		}
		if fi.Mode()&fs.ModeSymlink != 0 {
			return "", ErrBadPath
		}
	}
	return cur, nil
}

// writeAtomic writes r to dst via a temp file in the same folder, then renames
// it into place, so a crash leaves the old file or the new one, never half.
// It returns the sha256 of what it wrote. Without overwrite it fails with
// ErrExists if dst exists.
// shortcut: the exists check and the rename are not one atomic step; the vault
// mutex covers this process only, so don't run two processes on one vault.
func writeAtomic(dst string, r io.Reader, overwrite bool) (string, error) {
	dir := filepath.Dir(dst)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	if !overwrite {
		if _, err := os.Lstat(dst); err == nil {
			return "", ErrExists
		}
	}
	f, err := os.CreateTemp(dir, tmpPrefix+"*")
	if err != nil {
		return "", err
	}
	h := sha256.New()
	_, err = io.Copy(io.MultiWriter(f, h), r)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(f.Name(), dst)
	}
	if err != nil {
		os.Remove(f.Name())
		return "", err
	}
	if d, err := os.Open(dir); err == nil { // make the rename durable; fails harmlessly on Windows
		d.Sync()
		d.Close()
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func hashBytes(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

func isMD(p string) bool { return strings.EqualFold(path.Ext(p), ".md") }

func newID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err) // the OS random source failing is not recoverable
	}
	return strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}
