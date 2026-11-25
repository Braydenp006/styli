package vault

import (
	"bytes"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Recovery candidates are versions of a note that a delete everywhere would
// otherwise throw away: a copy a device edited after the note was deleted,
// or the newer version a device deleted without having seen it. The delete
// stands; each candidate waits for someone to restore it or delete it for
// good. Nothing removes one on its own (the maid only empties the trash).
//
//	<root>/.ids/recovery/<key>/record.json   the candidate (Candidate)
//	<root>/.ids/recovery/<key>/files/<hash>  its files, by content
//
// A candidate is written into a temporary folder and renamed into place, so
// it exists whole or not at all; a leftover temporary folder (its sender was
// never told) is removed when the vault opens.
const recoveryDir = "recovery"

// Why a candidate exists.
const (
	EditedAfterDelete   = "edited_after_delete"   // a device changed it after it was deleted
	DeletedWhileChanged = "deleted_while_changed" // a device deleted a version older than this one
)

// Candidate is one recovery candidate.
type Candidate struct {
	Key       string    `json:"-"`
	Group     Group     `json:"group"`  // the note's ID, paths and content
	Device    string    `json:"device"` // the device it came from, or the deleting device
	Why       string    `json:"why"`
	DeletedAt time.Time `json:"deleted_at,omitzero"` // when the note was deleted, if known
	AddedAt   time.Time `json:"added_at"`
}

type candidateRecord struct {
	Candidate
	GroupID string `json:"id"` // Group's ID is not part of its own JSON
}

func (v *Vault) recoveryPath(key string) string {
	return filepath.Join(v.root, idsDir, recoveryDir, key)
}

func (c Candidate) file(root, version string) string {
	return filepath.Join(root, idsDir, recoveryDir, c.Key, "files", version)
}

// AddCandidate keeps want, sent by device, as a candidate for note id, which
// must not be live here (ErrConflict and the live group: sync it normally).
// Its content must be staged (StageBlob). The same candidate sent again
// returns the one already kept.
func (v *Vault) AddCandidate(id string, want Group, device, why string) (Candidate, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if !idRe.MatchString(id) {
		return Candidate{}, ErrBadGroup
	}
	if cur, err := v.load(id); err == nil {
		return Candidate{Group: cur}, ErrConflict
	} else if !errors.Is(err, ErrNotFound) {
		return Candidate{}, err
	}
	return v.addCandidate(id, want, device, why)
}

func (v *Vault) addCandidate(id string, want Group, device, why string) (Candidate, error) {
	if err := v.checkShape(Group{}, want); err != nil {
		return Candidate{}, err
	}
	cs, err := v.listCandidates()
	if err != nil {
		return Candidate{}, err
	}
	for _, c := range cs {
		if c.Group.ID == id && SameFiles(c.Group, want) {
			return c, nil // sent again: its reply was lost
		}
	}
	want.ID, want.Rev, want.Deleted = id, 0, false
	c := Candidate{Key: newID(), Group: want, Device: device, Why: why, AddedAt: v.now().UTC()}
	if t, err := v.readTrash(id); err == nil {
		c.DeletedAt = t.DeletedAt
	} else if why == DeletedWhileChanged {
		c.DeletedAt = c.AddedAt // it is being deleted now (TrashKeeping)
	}

	tmp := v.recoveryPath(tmpPrefix + c.Key)
	fail := func(err error) (Candidate, error) {
		os.RemoveAll(tmp) // only the half-made temporary folder
		return Candidate{}, err
	}
	if err := os.MkdirAll(filepath.Join(tmp, "files"), 0o700); err != nil {
		return fail(err)
	}
	for _, f := range want.all() {
		err := linkOrCopy(v.blobPath(f.Version), filepath.Join(tmp, "files", f.Version))
		if errors.Is(err, fs.ErrNotExist) {
			return fail(ErrMissingBlob)
		}
		if err != nil {
			return fail(err)
		}
	}
	b, err := json.Marshal(candidateRecord{c, id})
	if err != nil {
		return fail(err)
	}
	if _, err := writeAtomic(filepath.Join(tmp, "record.json"), bytes.NewReader(b), true); err != nil {
		return fail(err)
	}
	if err := os.Rename(tmp, v.recoveryPath(c.Key)); err != nil {
		return fail(err)
	}
	if err := fsyncDir(filepath.Join(v.root, idsDir, recoveryDir)); err != nil {
		return Candidate{}, err
	}
	return c, nil
}

// TrashKeeping is a device's delete everywhere of group id, which it last saw
// at rev base. If the group changed here since, the delete still stands, but
// the version here is first kept as a candidate (DeletedWhileChanged) for
// review. A version here that differs from its record (an edit not scanned
// yet) is refused with ErrConflict; the device tries again later.
func (v *Vault) TrashKeeping(id string, base uint64, device string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return err
	}
	if g.Rev != base {
		// Copied, not linked: files in the vault can be edited in place.
		for _, f := range g.all() {
			src, err := v.abs(f.Path)
			if err != nil {
				return err
			}
			r, err := os.Open(src)
			if err != nil {
				return err
			}
			err = v.StageBlob(f.Version, r, 1<<62) // no cap: it is already in the vault (and +1 must not overflow)
			r.Close()
			if errors.Is(err, ErrHashMismatch) {
				return ErrConflict
			}
			if err != nil {
				return err
			}
		}
		if _, err := v.addCandidate(id, g, device, DeletedWhileChanged); err != nil {
			return err
		}
	}
	return v.trash(g, false)
}

// ListCandidates returns the candidates, oldest first.
func (v *Vault) ListCandidates() ([]Candidate, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.listCandidates()
}

func (v *Vault) listCandidates() ([]Candidate, error) {
	ents, err := os.ReadDir(filepath.Join(v.root, idsDir, recoveryDir))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []Candidate
	var errs []error
	for _, e := range ents {
		if !e.IsDir() || !idRe.MatchString(e.Name()) {
			continue
		}
		c, err := v.readCandidate(e.Name())
		if err != nil {
			errs = append(errs, err)
			continue
		}
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].AddedAt.Before(out[j].AddedAt) })
	return out, errors.Join(errs...)
}

func (v *Vault) readCandidate(key string) (Candidate, error) {
	if !idRe.MatchString(key) {
		return Candidate{}, ErrNotFound
	}
	b, err := os.ReadFile(filepath.Join(v.recoveryPath(key), "record.json"))
	if errors.Is(err, fs.ErrNotExist) {
		return Candidate{}, ErrNotFound
	}
	if err != nil {
		return Candidate{}, err
	}
	var r candidateRecord
	if err := json.Unmarshal(b, &r); err != nil {
		return Candidate{}, err
	}
	r.Candidate.Key, r.Candidate.Group.ID = key, r.GroupID
	return r.Candidate, nil
}

// RestoreCandidate makes the candidate the note's current version, under its
// ID: restored from the trash first if it is there, created again if it was
// emptied from the trash, or applied on top as an edit if the note is live
// (the text it replaces goes to history). folder is where it goes; "" means
// where the candidate's files were. A name already taken there is refused
// (ErrExists) and nothing changes. The candidate is removed only once the
// commit is done, so an interruption leaves it to restore again.
func (v *Vault) RestoreCandidate(key, folder string) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	c, err := v.readCandidate(key)
	if err != nil {
		return Group{}, err
	}
	id := c.Group.ID
	want := c.Group
	want.Files = append([]File(nil), c.Group.Files...)
	if want.Note != nil {
		n := *want.Note
		want.Note = &n
	}
	if folder != "" {
		for _, f := range want.all() {
			f.Path = path.Join(folder, path.Base(f.Path))
		}
	}
	// Free names are checked before anything moves.
	for _, f := range want.all() {
		dst, err := v.abs(f.Path)
		if err != nil {
			return Group{}, err
		}
		if _, err := os.Lstat(dst); err == nil && !v.ownsPath(id, f.Path) {
			return Group{}, ErrExists
		}
	}
	// Staged by link, so the candidate stays whole until the commit is done.
	for _, f := range want.all() {
		if err := linkOrCopy(c.file(v.root, f.Version), v.blobPath(f.Version)); err != nil {
			return Group{}, err
		}
	}
	var base uint64
	if cur, err := v.load(id); err == nil {
		base = cur.Rev
		want = keepRest(cur, want)
	} else if _, err := v.readTrash(id); err == nil {
		if err := v.restore(id, path.Dir(want.all()[0].Path)); err != nil {
			return Group{}, err
		}
		cur, err := v.load(id)
		if err != nil {
			return Group{}, err
		}
		base = cur.Rev
		want = keepRest(cur, want)
	}
	g, err := v.apply(id, base, want)
	if err != nil {
		return Group{}, err
	}
	return g, v.removeCandidate(key)
}

// ownsPath reports whether the live group id already has the path (so
// restoring over it is the group's own file, not someone else's).
func (v *Vault) ownsPath(id, p string) bool {
	g, err := v.load(id)
	if err != nil {
		return false
	}
	for _, f := range g.all() {
		if f.Path == p {
			return true
		}
	}
	return false
}

// keepRest adds to want what cur has and want lacks (a push never removes
// anything), moved into want's folder: nothing is dropped by a restore.
func keepRest(cur, want Group) Group {
	dir := path.Dir(want.all()[0].Path)
	if want.Note == nil && cur.Note != nil {
		n := *cur.Note
		n.Path = path.Join(dir, path.Base(n.Path))
		want.Note = &n
	}
	for _, f := range cur.Files[min(len(want.Files), len(cur.Files)):] {
		f.Path = path.Join(dir, path.Base(f.Path))
		want.Files = append(want.Files, f)
	}
	return want
}

// DeleteCandidate deletes a candidate for good: its files first, the record
// last, so an interruption leaves one the next delete finishes.
func (v *Vault) DeleteCandidate(key string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if _, err := v.readCandidate(key); err != nil {
		return err
	}
	return v.removeCandidate(key)
}

func (v *Vault) removeCandidate(key string) error {
	dir := v.recoveryPath(key)
	if err := os.RemoveAll(filepath.Join(dir, "files")); err != nil {
		return err
	}
	if err := os.Remove(filepath.Join(dir, "record.json")); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return os.Remove(dir)
}

// openRecovery removes temporary folders a crash left (candidates whose
// sender was never told they arrived). Called from Open.
func (v *Vault) openRecovery() error {
	dir := filepath.Join(v.root, idsDir, recoveryDir)
	ents, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	for _, e := range ents {
		if e.IsDir() && strings.HasPrefix(e.Name(), tmpPrefix) {
			if err := os.RemoveAll(filepath.Join(dir, e.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}

// linkOrCopy makes dst a second name for src (a copy where links are not
// supported). An existing dst is kept: these are files named by their hash,
// always written whole.
func linkOrCopy(src, dst string) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o700); err != nil { // only ever inside .ids
		return err
	}
	err := os.Link(src, dst)
	if err == nil || errors.Is(err, fs.ErrExist) {
		return nil
	}
	f, oerr := os.Open(src)
	if oerr != nil {
		return oerr
	}
	defer f.Close()
	_, err = writeAtomic(dst, f, true)
	return err
}
