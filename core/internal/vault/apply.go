package vault

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strings"
	"sync"
	"time"

	"styli/core/internal/atomicfile"
)

// The atrium side of sync (see "Sync protocol" in DESIGN.md).
//
// A device uploads new content as blobs named by their hash (StageBlob), then
// commits the group's new state with Apply, which only succeeds if the group's
// Rev still equals the device's base. Apply never overwrites what it has not
// seen: a file whose content no longer matches the record is refused until the
// next scan catches up.
//
//	<root>/.ids/sync/blobs/<sha256>   uploaded content waiting for its commit
//	<root>/.ids/sync/pending/<id>     a commit in progress (see runApply)
//	<root>/.ids/sync/failed/<id>-<ms>.json   a commit that could not be finished
const (
	blobsDir   = "blobs"
	pendingDir = "pending"
	failedDir  = "failed"
)

var (
	ErrTrashed      = errors.New("vault: group is in the trash")
	ErrMissingBlob  = errors.New("vault: content has not been uploaded")
	ErrHashMismatch = errors.New("vault: content does not match its hash")
	ErrBadGroup     = errors.New("vault: bad group")
	ErrMissingFile  = errors.New("vault: a file of this group is missing from the vault folder")
)

// StaleBlobAge is how old an uploaded blob that was never committed must be
// before it is removed (when the vault opens).
var StaleBlobAge = 24 * time.Hour

var hashRe = regexp.MustCompile(`^[0-9a-f]{64}$`)

// signal wakes everyone waiting on Changed. Call with the lock held.
func (v *Vault) signal() {
	close(v.changed)
	v.changed = make(chan struct{})
}

// Changed returns a channel that is closed at the next change to any record.
// Take it before reading, then wait on it, so no change slips in between.
func (v *Vault) Changed() <-chan struct{} {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.changed
}

// Changes returns up to limit groups whose Rev is above since, lowest Rev
// first, and whether more are waiting. A group appears once, at its newest Rev.
// A group in the trash is listed as a deletion (Deleted set, Rev the delete's
// number) until it is emptied from the trash.
func (v *Vault) Changes(since uint64, limit int) ([]Group, bool, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	type entry struct {
		id      string
		rev     uint64
		deleted bool
	}
	var es []entry
	for id, rev := range v.revs {
		if rev > since {
			es = append(es, entry{id, rev, false})
		}
	}
	for id, rev := range v.dels {
		if _, live := v.revs[id]; rev > since && !live { // live: a Trash cut short, finished later
			es = append(es, entry{id, rev, true})
		}
	}
	sort.Slice(es, func(i, j int) bool { return es[i].rev < es[j].rev })
	more := len(es) > limit
	if more {
		es = es[:limit]
	}
	gs := make([]Group, 0, len(es))
	for _, e := range es {
		if e.deleted {
			r, err := v.readTrash(e.id)
			if err != nil {
				return nil, false, err
			}
			g := r.Group
			g.Rev, g.Deleted = r.DeletedRev, true
			gs = append(gs, g)
			continue
		}
		g, err := v.load(e.id)
		if err != nil {
			return nil, false, err
		}
		gs = append(gs, g)
	}
	return gs, more, nil
}

func (v *Vault) blobPath(hash string) string {
	return filepath.Join(v.root, idsDir, syncDir, blobsDir, hash)
}

// StageBlob stores uploaded content until a commit uses it. The content must
// have the given sha256 and be at most max bytes. It does not take the vault
// lock, so a large upload never holds up the rest of the app.
func (v *Vault) StageBlob(hash string, r io.Reader, max int64) error {
	if !hashRe.MatchString(hash) {
		return ErrBadGroup
	}
	dst := v.blobPath(hash)
	lr := &io.LimitedReader{R: r, N: max + 1}
	got, err := writeAtomic(dst, lr, true)
	if err != nil {
		return err
	}
	switch {
	case lr.N == 0:
		os.Remove(dst)
		return ErrTooLarge
	case got != hash:
		os.Remove(dst)
		return ErrHashMismatch
	}
	return nil
}

// ErrOffset means a staged part is not the length the caller expected.
var ErrOffset = errors.New("vault: the staged part is not the length expected")

func (v *Vault) partPath(hash string) string { return v.blobPath(hash) + ".part" }

// BlobPartSize is how many bytes of blob hash an earlier, interrupted
// transfer staged (0 if none): where StageBlobAt can carry on.
func (v *Vault) BlobPartSize(hash string) (int64, error) {
	if !hashRe.MatchString(hash) {
		return 0, ErrBadGroup
	}
	fi, err := os.Stat(v.partPath(hash))
	if errors.Is(err, fs.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	return fi.Size(), nil
}

// BlobStaged reports whether blob hash is staged whole (checked against its
// hash when it was), and its size.
func (v *Vault) BlobStaged(hash string) (int64, bool, error) {
	if !hashRe.MatchString(hash) {
		return 0, false, ErrBadGroup
	}
	fi, err := os.Stat(v.blobPath(hash))
	if errors.Is(err, fs.ErrNotExist) {
		return 0, false, nil
	}
	if err != nil {
		return 0, false, err
	}
	return fi.Size(), true, nil
}

// DropBlobPart throws away the staged part of blob hash.
func (v *Vault) DropBlobPart(hash string) error {
	if !hashRe.MatchString(hash) {
		return ErrBadGroup
	}
	unlock := v.lockPart(hash)
	defer unlock()
	if err := os.Remove(v.partPath(hash)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// lockPart serialises the writers of one part; the entry goes when the last
// one unlocks, so the map does not grow.
func (v *Vault) lockPart(hash string) (unlock func()) {
	v.partMu.Lock()
	l := v.parts[hash]
	if l == nil {
		l = &partLock{}
		v.parts[hash] = l
	}
	l.users++
	v.partMu.Unlock()
	l.Lock()
	return func() {
		l.Unlock()
		v.partMu.Lock()
		if l.users--; l.users == 0 {
			delete(v.parts, hash)
		}
		v.partMu.Unlock()
	}
}

type partLock struct {
	sync.Mutex
	users int
}

// StageBlobAt is StageBlob that can be continued. r holds the content from
// byte offset on, and the part staged so far must be exactly offset bytes
// (ErrOffset otherwise; offset 0 starts again). A copy that fails part way
// keeps what arrived, so the next call carries on from BlobPartSize. When r
// ends the whole content is checked: a match is staged like StageBlob's, a
// mismatch throws the part away (ErrHashMismatch), and so does going over max
// (ErrTooLarge), since neither can be carried on.
func (v *Vault) StageBlobAt(hash string, offset int64, r io.Reader, max int64) error {
	if !hashRe.MatchString(hash) {
		return ErrBadGroup
	}
	if offset < 0 || offset > max {
		return ErrTooLarge
	}
	unlock := v.lockPart(hash)
	defer unlock()
	part := v.partPath(hash)
	size, err := atomicfile.Append(part, offset, &io.LimitedReader{R: r, N: max - offset + 1})
	switch {
	case errors.Is(err, atomicfile.ErrOffset):
		return ErrOffset
	case size > max:
		os.Remove(part)
		return ErrTooLarge
	case err != nil:
		return err
	}
	got, err := hashFile(part)
	if err != nil {
		return err
	}
	if got != hash {
		os.Remove(part)
		return ErrHashMismatch
	}
	if err := os.Rename(part, v.blobPath(hash)); err != nil {
		return err
	}
	return fsyncDir(filepath.Dir(part))
}

// applyStep is one file of a commit: moved from Src (empty for a new file) to
// Dst, and if Content is set, given the content with hash Ver.
type applyStep struct {
	Src     string `json:"src,omitempty"`
	Dst     string `json:"dst"`
	Ver     string `json:"ver"`
	Content bool   `json:"content,omitempty"`
}

// pendingApply is the journal of a commit in progress. It is written before
// any file is touched and removed after the record is saved, so a crash in
// between is finished (never half-undone) when the vault next opens.
type pendingApply struct {
	ID    string      `json:"id"`
	Group Group       `json:"group"`
	Steps []applyStep `json:"steps"`

	// From is set when the commit gives an existing group the ID ID (see
	// Adopt): its history moves along and its record is removed.
	From string `json:"from,omitempty"`

	// Attempts counts the starts that could not finish it. After two it is
	// set aside (see openApply).
	Attempts int `json:"attempts,omitempty"`
}

// Apply commits a device's push: group id becomes want, if its Rev still equals
// base (0 means the device is creating it). Files are matched by position:
// want.Note is the note, want.Files[i] is the group's i-th attached file, and
// files past the end of the current list are new. It returns the saved group.
//
// If the group already holds exactly want, it returns it with no error (the
// device's earlier attempt went through but the reply was lost). On
// ErrConflict it returns the current group so the device can merge.
func (v *Vault) Apply(id string, base uint64, want Group) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.apply(id, base, want)
}

func (v *Vault) apply(id string, base uint64, want Group) (Group, error) {
	if !idRe.MatchString(id) {
		return Group{}, ErrBadGroup
	}
	if err := v.finishPending(id); err != nil {
		return Group{}, err
	}
	want.ID, want.Rev = id, 0

	cur, err := v.load(id)
	exists := err == nil
	if err != nil && !errors.Is(err, ErrNotFound) {
		return Group{}, err
	}
	if !exists {
		if rec, err := v.readTrash(id); err == nil {
			if base == 0 || base != rec.DeletedRev {
				return Group{}, ErrTrashed
			}
			// The device saw this delete (its base is the delete's number) and
			// restored the note there: restore it here, then apply its push.
			if err := v.restore(id, ""); err != nil {
				return Group{}, err
			}
			if cur, err = v.load(id); err != nil {
				return Group{}, err
			}
			exists, base = true, cur.Rev
			if SameState(cur, want) {
				return cur, nil
			}
		} else if base != 0 {
			return Group{}, ErrNotFound
		}
	} else {
		if SameState(cur, want) {
			return cur, nil
		}
		if base == 0 || cur.Rev != base {
			return cur, ErrConflict
		}
	}
	if err := v.checkShape(cur, want); err != nil {
		return Group{}, err
	}

	// Plan the steps, note first.
	var steps []applyStep
	var was []string // the recorded version each step replaces
	add := func(from *File, to File) {
		s := applyStep{Dst: to.Path, Ver: to.Version, Content: true}
		w := ""
		if from != nil {
			s.Src, s.Content, w = from.Path, from.Version != to.Version, from.Version
		}
		steps = append(steps, s)
		was = append(was, w)
	}
	if want.Note != nil {
		add(cur.Note, *want.Note)
	}
	for i, f := range want.Files {
		var from *File
		if i < len(cur.Files) {
			from = &cur.Files[i]
		}
		add(from, f)
	}

	var noteText []byte
	var seed *historySeed
	for i, s := range steps {
		dst, err := v.abs(s.Dst)
		if err != nil {
			return Group{}, err
		}
		if s.Src != s.Dst {
			// Never overwrite: a new name must be free (this also refuses
			// swapping two names inside the group).
			if _, err := os.Lstat(dst); err == nil {
				return Group{}, ErrExists
			} else if !errors.Is(err, fs.ErrNotExist) {
				return Group{}, err
			}
		}
		var src string
		if s.Src != "" {
			if src, err = v.abs(s.Src); err != nil {
				return Group{}, err
			}
			fi, err := os.Lstat(src)
			if errors.Is(err, fs.ErrNotExist) {
				return Group{}, ErrMissingFile
			} else if err != nil {
				return Group{}, err
			} else if !fi.Mode().IsRegular() {
				return Group{}, ErrBadPath
			}
		}
		if !s.Content {
			continue
		}
		fi, err := os.Stat(v.blobPath(s.Ver))
		if errors.Is(err, fs.ErrNotExist) {
			return Group{}, ErrMissingBlob
		} else if err != nil {
			return Group{}, err
		}
		isNote := i == 0 && want.Note != nil
		if isNote {
			if fi.Size() > MaxNoteSize {
				return Group{}, ErrTooLarge
			}
			if noteText, err = os.ReadFile(v.blobPath(s.Ver)); err != nil {
				return Group{}, err
			}
			if hashBytes(noteText) != s.Ver {
				return Group{}, ErrHashMismatch
			}
		}
		if src == "" {
			continue
		}
		// Only replace content the record says is there: an edit made outside
		// the app and not scanned yet must not be overwritten.
		if isNote {
			old, err := readCapped(src)
			if err != nil {
				return Group{}, err
			}
			if hashBytes(old) != was[i] {
				return cur, ErrConflict
			}
			seed = &historySeed{old, fi.ModTime()}
		} else if h, err := hashFile(src); err != nil {
			return Group{}, err
		} else if h != was[i] {
			return cur, ErrConflict
		}
	}

	// The text being replaced is kept in history before anything changes.
	if noteText != nil {
		if err := v.snapshot(want, noteText, seed, false); err != nil {
			return Group{}, err
		}
	}
	p := pendingApply{ID: id, Group: want, Steps: steps}
	b, err := json.Marshal(p)
	if err != nil {
		return Group{}, err
	}
	if _, err := writeAtomic(v.pendingPath(id), bytes.NewReader(b), true); err != nil {
		return Group{}, err
	}
	if err := v.runApply(p); err != nil {
		return Group{}, err // the journal stays; the next Apply or Open finishes it
	}
	return v.load(id)
}

// Adopt gives the group from the ID id, because the atrium already holds the
// same files under id (two copies of one vault joining). from must never
// have synced, so its ID was never shared. want is the atrium's group: its
// files must equal from's, and its other fields (history setting, conflict
// flag) win. The record and its history move to id; it returns the saved
// group. A crash part way is finished when the vault next opens.
func (v *Vault) Adopt(from, id string, want Group) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if !idRe.MatchString(from) || !idRe.MatchString(id) || from == id {
		return Group{}, ErrBadGroup
	}
	for _, x := range []string{from, id} {
		if err := v.finishPending(x); err != nil {
			return Group{}, err
		}
	}
	if _, err := v.load(id); err == nil {
		return Group{}, ErrExists
	} else if !errors.Is(err, ErrNotFound) {
		return Group{}, err
	}
	if _, err := v.readTrash(id); err == nil {
		return Group{}, ErrTrashed
	}
	cur, err := v.load(from)
	if err != nil {
		return Group{}, err
	}
	if !SameFiles(cur, want) {
		return cur, ErrConflict
	}
	want.ID, want.Rev = id, 0
	p := pendingApply{ID: id, Group: want, Steps: []applyStep{}, From: from}
	b, err := json.Marshal(p)
	if err != nil {
		return Group{}, err
	}
	if _, err := writeAtomic(v.pendingPath(id), bytes.NewReader(b), true); err != nil {
		return Group{}, err
	}
	if err := v.runApply(p); err != nil {
		return Group{}, err // the journal stays; the next Apply, Adopt or Open finishes it
	}
	return v.load(id)
}

func fsyncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// checkShape refuses a group that is malformed or drops something the
// current one has.
func (v *Vault) checkShape(cur, want Group) error {
	all := want.all()
	if len(all) == 0 || (cur.Note != nil && want.Note == nil) || len(want.Files) < len(cur.Files) {
		return ErrBadGroup
	}
	if want.Note != nil && !isMD(want.Note.Path) {
		return ErrBadPath
	}
	dir := path.Dir(all[0].Path)
	names := map[string]bool{}
	for _, f := range all {
		if !hashRe.MatchString(f.Version) {
			return ErrBadGroup
		}
		if _, err := v.abs(f.Path); err != nil || f.Path == "." {
			return ErrBadPath
		}
		if path.Dir(f.Path) != dir || names[path.Base(f.Path)] {
			return ErrBadGroup // a group lives in one folder, one name each
		}
		names[path.Base(f.Path)] = true
	}
	for _, f := range want.Files {
		if isMD(f.Path) {
			return ErrBadPath
		}
	}
	return nil
}

// runApply carries out a journalled commit. Every step checks what is already
// on disk first, so running it again after a crash or an error just finishes
// the job. Call with the lock held.
func (v *Vault) runApply(p pendingApply) error {
	for _, s := range p.Steps {
		dst, err := v.abs(s.Dst)
		if err != nil {
			return err
		}
		if err := v.mkdirs(filepath.Dir(dst)); err != nil {
			return err
		}
		if s.Src != "" && s.Src != s.Dst {
			src, err := v.abs(s.Src)
			if err != nil {
				return err
			}
			_, serr := os.Lstat(src)
			_, derr := os.Lstat(dst)
			if serr == nil && errors.Is(derr, fs.ErrNotExist) {
				if err := os.Rename(src, dst); err != nil {
					return err
				}
			}
		}
		if !s.Content {
			continue
		}
		if h, err := hashFile(dst); err == nil && h == s.Ver {
			continue // written before an interruption
		}
		f, err := os.Open(v.blobPath(s.Ver))
		if err != nil {
			return err
		}
		h, err := writeAtomic(dst, f, true)
		f.Close()
		if err != nil {
			return err
		}
		if h != s.Ver {
			return ErrHashMismatch // the stored blob was damaged
		}
	}
	g := p.Group
	g.ID = p.ID
	if p.From != "" {
		// History comes along first; it is only renamed once, so a rerun skips it.
		old, nw := v.historyPath(p.From), v.historyPath(p.ID)
		if _, err := os.Lstat(old); err == nil {
			if _, err := os.Lstat(nw); errors.Is(err, fs.ErrNotExist) {
				if err := os.Rename(old, nw); err != nil {
					return err
				}
			}
		}
	}
	if err := v.save(&g); err != nil {
		return err
	}
	if p.From != "" {
		// The old record goes only after the new one is saved, so the files
		// always have a record and no scan takes them for a new note.
		if err := os.Remove(filepath.Join(v.root, idsDir, p.From)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		delete(v.revs, p.From)
		if err := fsyncDir(filepath.Join(v.root, idsDir)); err != nil {
			return err // the journal stays, so the removal is checked again
		}
	}
	os.Remove(v.pendingPath(p.ID))
	for _, s := range p.Steps {
		if s.Content {
			os.Remove(v.blobPath(s.Ver))
		}
	}
	return nil
}

func (v *Vault) pendingPath(id string) string {
	return filepath.Join(v.root, idsDir, syncDir, pendingDir, id)
}

// finishPending completes an interrupted commit of group id, if there is one.
func (v *Vault) finishPending(id string) error {
	b, err := os.ReadFile(v.pendingPath(id))
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var p pendingApply
	if err := json.Unmarshal(b, &p); err != nil || p.ID != id {
		return errors.Join(ErrBadGroup, err)
	}
	return v.runApply(p)
}

// pendingIDs lists the groups with a commit in progress.
func (v *Vault) pendingIDs() ([]string, error) {
	ents, err := os.ReadDir(filepath.Join(v.root, idsDir, syncDir, pendingDir))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var ids []string
	for _, e := range ents {
		if idRe.MatchString(e.Name()) {
			ids = append(ids, e.Name())
		}
	}
	return ids, nil
}

// pendingPaths returns every path a commit or restore in progress will write, so Rescan
// does not adopt a half-committed file as a new note.
func (v *Vault) pendingPaths() map[string]bool {
	ids, _ := v.pendingIDs()
	out := map[string]bool{}
	for _, id := range ids {
		b, err := os.ReadFile(v.pendingPath(id))
		if err != nil {
			continue
		}
		var p pendingApply
		if json.Unmarshal(b, &p) == nil {
			for _, s := range p.Steps {
				out[s.Dst] = true
			}
		}
	}
	v.restoringPaths(out)
	return out
}

// openApply finishes any commit a crash interrupted, then removes uploads that
// were never committed.
//
// A commit that cannot be finished never stops the vault from opening. Nothing
// is at risk: the replaced text is already in history, and the device still
// has its version because it was never told the commit went through, so it
// sends it again. A commit that fails is left for the next start (a full disk
// may have been freed by then); if it fails again, or its journal cannot be
// read, it is set aside in .ids/sync/failed/ for the dashboard to show, and
// the next scan tidies any half-moved files.
func (v *Vault) openApply() error {
	ids, err := v.pendingIDs()
	if err != nil {
		return err
	}
	for _, id := range ids {
		b, err := os.ReadFile(v.pendingPath(id))
		if err != nil {
			return err
		}
		var p pendingApply
		if err := json.Unmarshal(b, &p); err != nil || p.ID != id {
			if err := v.setAside(id, b, "its record of what to do could not be read"); err != nil {
				return err
			}
			continue
		}
		err = v.runApply(p)
		switch {
		case err == nil:
		case p.Attempts >= 1:
			if err := v.setAside(id, b, plainReason(err)); err != nil {
				return err
			}
		default:
			p.Attempts++
			nb, _ := json.Marshal(p)
			if _, err := writeAtomic(v.pendingPath(id), bytes.NewReader(nb), true); err != nil {
				return err
			}
		}
	}
	_, err = v.cleanBlobs(time.Now())
	return err
}

// CleanBlobs removes uploads that no commit used within StaleBlobAge (the
// connection dropped between upload and commit, or the commit was refused and
// the device sent merged content instead). They are only spare copies of what
// a device still has; a device whose upload was removed is asked to send it
// again. The app calls this every hour, and the vault on open. It returns how
// many it removed.
func (v *Vault) CleanBlobs() (int, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.cleanBlobs(time.Now())
}

func (v *Vault) cleanBlobs(now time.Time) (int, error) {
	dir := filepath.Join(v.root, idsDir, syncDir, blobsDir)
	ents, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	// Uploads an unfinished commit still needs are never removed.
	needed := map[string]bool{}
	ids, _ := v.pendingIDs()
	for _, id := range ids {
		var p pendingApply
		if b, err := os.ReadFile(v.pendingPath(id)); err == nil && json.Unmarshal(b, &p) == nil {
			for _, s := range p.Steps {
				needed[s.Ver] = true
			}
		}
	}
	n := 0
	for _, e := range ents {
		// Finished uploads (named by their hash) and parts of unfinished ones
		// (hash.part); an upload being written in one go has a temporary name.
		if !hashRe.MatchString(strings.TrimSuffix(e.Name(), ".part")) || needed[e.Name()] {
			continue
		}
		if fi, err := e.Info(); err == nil && now.Sub(fi.ModTime()) > StaleBlobAge {
			if os.Remove(filepath.Join(dir, e.Name())) == nil {
				n++
			}
		}
	}
	return n, nil
}

// SameState reports whether two groups hold the same files, content and
// settings (Rev is ignored).
func SameState(a, b Group) bool {
	if (a.History == nil) != (b.History == nil) || (a.History != nil && *a.History != *b.History) {
		return false
	}
	return a.Conflict == b.Conflict && SameFiles(a, b)
}

// SameFiles reports whether two groups hold the same files at the same paths
// with the same content, whatever their settings.
func SameFiles(a, b Group) bool {
	if (a.Note == nil) != (b.Note == nil) || (a.Note != nil && *a.Note != *b.Note) {
		return false
	}
	return slices.Equal(a.Files, b.Files)
}

// FailedCommit is a change from a device that could not be finished and was
// set aside. The device sends the change again, so this is only a notice.
type FailedCommit struct {
	Name     string    `json:"name"` // the file in .ids/sync/failed/, used to dismiss it
	ID       string    `json:"id"`
	NotePath string    `json:"note_path,omitempty"` // where the note was going, if readable
	At       time.Time `json:"at"`
	Reason   string    `json:"reason"`
}

type failedRecord struct {
	FailedCommit
	Journal json.RawMessage `json:"journal,omitempty"` // kept for inspection
}

var failedRe = regexp.MustCompile(`^[a-z2-7]{26}-\d{13}\.json$`)

// setAside moves a journal that cannot be finished to .ids/sync/failed/.
func (v *Vault) setAside(id string, journal []byte, reason string) error {
	at := time.Now().UTC()
	rec := failedRecord{FailedCommit: FailedCommit{ID: id, At: at, Reason: reason}}
	var p pendingApply
	if json.Unmarshal(journal, &p) == nil {
		rec.Journal = journal
		if p.Group.Note != nil {
			rec.NotePath = p.Group.Note.Path
		}
	}
	b, err := json.Marshal(rec)
	if err != nil {
		return err
	}
	name := fmt.Sprintf("%s-%013d.json", id, at.UnixMilli())
	if _, err := writeAtomic(filepath.Join(v.root, idsDir, syncDir, failedDir, name), bytes.NewReader(b), false); err != nil {
		return err
	}
	return os.Remove(v.pendingPath(id))
}

// FailedCommits lists the commits that were set aside, oldest first.
func (v *Vault) FailedCommits() ([]FailedCommit, error) {
	dir := filepath.Join(v.root, idsDir, syncDir, failedDir)
	ents, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return []FailedCommit{}, nil
	}
	if err != nil {
		return nil, err
	}
	out := []FailedCommit{}
	for _, e := range ents {
		if !failedRe.MatchString(e.Name()) {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			return nil, err
		}
		var r failedRecord
		if err := json.Unmarshal(b, &r); err != nil {
			r.ID, r.Reason = e.Name()[:26], "its record could not be read"
		}
		r.Name = e.Name()
		out = append(out, r.FailedCommit)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name[27:] < out[j].Name[27:] })
	return out, nil
}

// DismissFailedCommit removes a set-aside commit once the person has seen it.
func (v *Vault) DismissFailedCommit(name string) error {
	if !failedRe.MatchString(name) {
		return ErrNotFound
	}
	err := os.Remove(filepath.Join(v.root, idsDir, syncDir, failedDir, name))
	if errors.Is(err, fs.ErrNotExist) {
		return ErrNotFound
	}
	return err
}

func plainReason(err error) string {
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return "the content the device uploaded is gone"
	case errors.Is(err, ErrHashMismatch):
		return "the content the device uploaded was damaged"
	default:
		return err.Error()
	}
}
