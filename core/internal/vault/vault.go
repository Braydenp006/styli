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
	"time"

	"styli/core/internal/atomicfile"
)

const (
	idsDir    = ".ids"
	tmpPrefix = atomicfile.TmpPrefix
)

var (
	ErrNotFound = errors.New("vault: not found")
	ErrExists   = atomicfile.ErrExists
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

	// History overrides the vault's default for this note: nil follows the
	// default, otherwise true or false. See history.go.
	History *bool `json:"history,omitempty"`

	// Conflict marks a note whose last merge left conflict markers (or kept
	// both versions of an attached file) for someone to sort out. It syncs
	// like any other field and stays until SetConflict clears it.
	Conflict bool `json:"conflict,omitempty"`

	// Rev is the number this vault gave the group's latest change (see rev.go).
	Rev uint64 `json:"rev,omitempty"`

	// Deleted is set only on a deletion listed by Changes: the group as it was
	// when trashed, with the delete's number as Rev. Never stored.
	Deleted bool `json:"-"`
}

// Vault is a folder of notes. Safe for concurrent use inside one process.
type Vault struct {
	root    string
	dirMode fs.FileMode // the vault folder's permissions, given to folders the vault creates
	mu      sync.Mutex

	// Used only by Rescan (see scan.go).
	seen    map[string]cached // size, modified time and hash from the last scan
	missing map[string]bool   // group IDs already reported missing

	// Version history settings (see history.go). The atrium's settings feed these.
	historyOn     bool
	historyWindow time.Duration
	historyPolicy HistoryPolicy
	loc           *time.Location   // time zone that day/week/month boundaries follow
	now           func() time.Time // replaced in tests

	// Change numbering (see rev.go).
	seq    uint64
	epoch  string
	mirror string // copy of seq outside the vault; "" until SetSeqMirror

	// For the change feed (see apply.go): each live group's Rev, and a channel
	// closed (then replaced) whenever a record is saved.
	revs    map[string]uint64
	changed chan struct{}

	dels      map[string]uint64 // trashed group ID -> the delete's number
	forgotten uint64            // see Forgotten
}

// Open opens (and if needed creates) a vault. Call Rescan afterwards to pick up
// anything that changed while the app was not running.
func Open(root string) (*Vault, error) {
	// .ids holds the records, history and trash: private to this user, even
	// if it already existed with looser permissions.
	ids := filepath.Join(root, idsDir)
	if err := os.MkdirAll(ids, 0o700); err != nil {
		return nil, err
	}
	if err := os.Chmod(ids, 0o700); err != nil {
		return nil, err
	}
	fi, err := os.Stat(root)
	if err != nil {
		return nil, err
	}
	v := &Vault{
		root: root, dirMode: fi.Mode().Perm(), seen: map[string]cached{}, missing: map[string]bool{},
		historyOn: true, historyWindow: 30 * time.Minute, loc: time.Local, now: time.Now,
		revs: map[string]uint64{}, changed: make(chan struct{}), dels: map[string]uint64{},
	}
	if err := v.openRevs(); err != nil {
		return nil, err
	}
	if err := v.openApply(); err != nil {
		return nil, err
	}
	if err := v.openRestores(); err != nil {
		return nil, err
	}
	if err := v.openRecovery(); err != nil {
		return nil, err
	}
	return v, nil
}

// mkdirs makes the folders for a path in the vault with the vault folder's own
// permissions, so a private vault stays private and a shared one stays shared.
func (v *Vault) mkdirs(dir string) error {
	return os.MkdirAll(dir, v.dirMode)
}

// CreateNote makes a new group from a Markdown note. p must end in .md.
func (v *Vault) CreateNote(p string, content []byte) (Group, error) {
	if !isMD(p) {
		return Group{}, ErrBadPath
	}
	return v.create(p, bytes.NewReader(content), content, true)
}

// ImportFile makes a new group from a source file (for example a PDF). The
// Markdown joins the group later with SetNote.
func (v *Vault) ImportFile(p string, r io.Reader) (Group, error) {
	if isMD(p) {
		return Group{}, ErrBadPath
	}
	return v.create(p, r, nil, false)
}

func (v *Vault) create(p string, r io.Reader, content []byte, isNote bool) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	abs, err := v.abs(p)
	if err != nil {
		return Group{}, err
	}
	g := Group{ID: newID(), Files: []File{}}
	if isNote {
		if _, err := os.Lstat(abs); err == nil {
			return Group{}, ErrExists
		}
		if err := v.snapshot(g, content, nil, false); err != nil {
			return Group{}, err
		}
	}
	if err := v.mkdirs(filepath.Dir(abs)); err != nil {
		if isNote {
			v.dropHistory(g.ID)
		}
		return Group{}, err
	}
	// File first, record second: a crash in between leaves an unrecorded file
	// (the later watcher can adopt it), never a record pointing at nothing.
	ver, err := writeAtomic(abs, r, false)
	if err != nil {
		if isNote {
			v.dropHistory(g.ID)
		}
		return Group{}, err
	}
	if isNote {
		g.Note = &File{p, ver}
	} else {
		g.Files = append(g.Files, File{p, ver})
	}
	err = v.save(&g)
	return g, err
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
	err = v.save(&g)
	return g, err
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
	if err := v.snapshot(g, content, nil, false); err != nil {
		return Group{}, err
	}
	ver, err := writeAtomic(abs, bytes.NewReader(content), false)
	if err != nil {
		return Group{}, err
	}
	g.Note = &File{p, ver}
	err = v.save(&g)
	return g, err
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
	b, err := readCapped(abs)
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
	return v.writeNote(id, content, base, false, false, false)
}

// WriteNoteKeepingOld is WriteNote, except the text it replaces is always kept
// as its own history version, even if it was saved moments ago (normally a
// save within the history window folds into the newest version). It is for
// the moment someone chooses to overwrite another person's or device's text.
func (v *Vault) WriteNoteKeepingOld(id string, content []byte, base string) (string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.writeNote(id, content, base, true, false, false)
}

// WriteNoteMerged writes the result of a sync merge. The text it replaces is
// always kept as a history version, even when history is off for this note,
// and conflict (if true) flags the note in the same save, so a crash cannot
// leave markers without the flag.
func (v *Vault) WriteNoteMerged(id string, content []byte, base string, conflict bool) (string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.writeNote(id, content, base, true, true, conflict)
}

// SetConflict sets or clears a note's conflict flag.
func (v *Vault) SetConflict(id string, on bool) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return err
	}
	if g.Conflict == on {
		return nil
	}
	g.Conflict = on
	return v.save(&g)
}

// writeNote is WriteNote with the lock held. newVersion forces a new history
// version instead of folding into the newest one (used by restores).
func (v *Vault) writeNote(id string, content []byte, base string, newVersion, alwaysKeep, conflict bool) (string, error) {
	old, cur, err := v.readNote(id)
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
	// The version is saved before the note, so if it cannot be saved the
	// write is refused and nothing changes.
	var seed *historySeed
	if fi, err := os.Stat(abs); err == nil {
		seed = &historySeed{old, fi.ModTime()}
	}
	hg := g
	if alwaysKeep {
		on := true
		hg.History = &on // history is saved even if the note has it off
	}
	if err := v.snapshot(hg, content, seed, newVersion); err != nil {
		return "", err
	}
	ver, err := writeAtomic(abs, bytes.NewReader(content), true)
	if err != nil {
		return "", err
	}
	g.Note.Version = ver
	g.Conflict = g.Conflict || conflict
	return ver, v.save(&g)
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
	if err := v.mkdirs(dstDir); err != nil {
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
	return v.save(&g)
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

// save writes the group's record with the next change number in g.Rev.
func (v *Vault) save(g *Group) error {
	rev, err := v.nextRev()
	if err != nil {
		return err
	}
	g.Rev = rev
	b, err := json.Marshal(g)
	if err != nil {
		return err
	}
	if _, err = writeAtomic(filepath.Join(v.root, idsDir, g.ID), bytes.NewReader(b), true); err != nil {
		return err
	}
	v.revs[g.ID] = g.Rev
	v.signal()
	return nil
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

// writeAtomic writes r to dst crash-safely (see atomicfile) and returns the
// sha256 of what it wrote.
func writeAtomic(dst string, r io.Reader, overwrite bool) (string, error) {
	return atomicfile.Write(dst, r, overwrite)
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

// OpenFile opens the group's index-th attached file (not the note) for
// reading and returns it with its vault path. The caller closes it. The path
// comes only from the group's record and goes through the vault's path rules,
// so a caller can never reach a file outside the group.
func (v *Vault) OpenFile(id string, index int) (*os.File, string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return nil, "", err
	}
	if index < 0 || index >= len(g.Files) {
		return nil, "", ErrNotFound
	}
	abs, err := v.abs(g.Files[index].Path)
	if err != nil {
		return nil, "", err
	}
	f, err := os.Open(abs)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, "", ErrNotFound
	}
	if err != nil {
		return nil, "", err
	}
	if fi, err := f.Stat(); err != nil || !fi.Mode().IsRegular() {
		f.Close()
		return nil, "", ErrNotFound
	}
	return f, g.Files[index].Path, nil
}

// Root returns the vault's folder on disk.
func (v *Vault) Root() string { return v.root }

// MaxNoteSize is the largest note read into memory. A bigger .md file (only
// possible by copying it into the vault folder) is listed and kept, but not
// opened, so it cannot exhaust the atrium's memory.
var MaxNoteSize int64 = 64 << 20

// ErrTooLarge means a note is bigger than MaxNoteSize.
var ErrTooLarge = errors.New("vault: note is too large to open")

func readCapped(path string) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	if fi, err := f.Stat(); err != nil {
		return nil, err
	} else if fi.Size() > MaxNoteSize {
		return nil, ErrTooLarge
	}
	b, err := io.ReadAll(io.LimitReader(f, MaxNoteSize+1)) // it may grow while we read
	if int64(len(b)) > MaxNoteSize {
		return nil, ErrTooLarge
	}
	return b, err
}
