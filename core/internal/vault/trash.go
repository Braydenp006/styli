package vault

import (
	"bytes"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"time"
)

// Trash keeps deleted groups recoverable inside the vault:
//
//	<root>/.ids/trash/<id>/record.json   the group's record, plus when and from where
//	<root>/.ids/trash/<id>/files/<name>  the group's files, moved (not copied)
//
// It is hidden from Rescan, and the vault's path rules refuse anything under
// .ids, so normal calls can never reach it. Purge is the only code that
// permanently deletes anything.
const trashDir = "trash"

// TrashedGroup is one entry in the trash.
type TrashedGroup struct {
	Group      Group  // as it was when deleted; Group.Rev is its last rev while live
	Folder     string // the folder the group lived in
	DeletedAt  time.Time
	DeletedRev uint64 // the delete's own number (see rev.go)
	LocalOnly  bool   // removed from this device only (sync's safe clearing); still on the atrium
}

type trashRecord struct {
	Group
	Folder     string    `json:"folder"`
	DeletedAt  time.Time `json:"deleted_at"`
	DeletedRev uint64    `json:"deleted_rev,omitempty"`
	LocalOnly  bool      `json:"local_only,omitempty"`
	// Restoring is set, to the restore's folder ("" for where it was), before
	// a restore moves any file, so one a crash interrupted is finished on open
	// rather than its moved files being scanned in as a new note.
	Restoring *string `json:"restoring,omitempty"`
}

func (v *Vault) trashPath(id string) string {
	return filepath.Join(v.root, idsDir, trashDir, id)
}

func (v *Vault) readTrash(id string) (trashRecord, error) {
	if !idRe.MatchString(id) {
		return trashRecord{}, ErrNotFound
	}
	b, err := os.ReadFile(filepath.Join(v.trashPath(id), "record.json"))
	if errors.Is(err, fs.ErrNotExist) {
		return trashRecord{}, ErrNotFound
	}
	if err != nil {
		return trashRecord{}, err
	}
	var r trashRecord
	if err := json.Unmarshal(b, &r); err != nil {
		return trashRecord{}, err
	}
	r.Group.ID = id
	return r, nil
}

// Trash moves a whole group into the trash. It works on a group whose files
// are already gone (a "missing" note the user chose to remove): it moves what
// exists and remembers everything. If it is interrupted, calling it again
// finishes the job, and Restore works at every step.
//
// The delete takes the next number, like any change, so the change feed
// lists it and devices delete their copies too (see rev.go).
func (v *Vault) Trash(id string) error {
	_, err := v.TrashIf(id, 0)
	return err
}

// TrashIf trashes the group only if its Rev is still base (0: whatever it
// is). Otherwise it returns the current group and ErrConflict: a device
// deleting a version that has since changed here.
func (v *Vault) TrashIf(id string, base uint64) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return Group{}, err
	}
	if base != 0 && g.Rev != base {
		return g, ErrConflict
	}
	return Group{}, v.trash(g, false)
}

// Verify returns group id after checking that every one of its files on disk
// still has the content its record says. An edit not scanned yet (or a file
// gone missing) is ErrConflict: the record can't be trusted for this group yet.
func (v *Vault) Verify(id string) (Group, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return Group{}, err
	}
	return g, v.verify(g)
}

func (v *Vault) verify(g Group) error {
	for _, f := range g.all() {
		src, err := v.abs(f.Path)
		if err != nil {
			return err
		}
		h, err := hashFile(src)
		if errors.Is(err, fs.ErrNotExist) || (err == nil && h != f.Version) {
			return ErrConflict
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// TrashIfVerified is TrashIf that also refuses (ErrConflict) when a file on
// disk differs from the record, all under one lock: what goes to the trash is
// exactly the version the caller checked. The entry is marked LocalOnly: sync
// cleared it from this device only, and the note stays on the atrium.
func (v *Vault) TrashIfVerified(id string, base uint64) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return err
	}
	if g.Rev != base {
		return ErrConflict
	}
	if err := v.verify(g); err != nil {
		return err
	}
	return v.trash(g, true)
}

func (v *Vault) trash(g Group, localOnly bool) error {
	id := g.ID
	files := g.all()
	tdir := v.trashPath(id)
	fdir := filepath.Join(tdir, "files")

	type mv struct{ src, dst string }
	var mvs []mv
	names := map[string]bool{}
	for _, f := range files {
		base := path.Base(f.Path)
		if names[base] { // two files would land on one trash name
			return ErrExists
		}
		names[base] = true
		src, err := v.abs(f.Path)
		if err != nil {
			return err
		}
		if _, err := os.Lstat(src); errors.Is(err, fs.ErrNotExist) {
			continue // already gone, or moved by an earlier interrupted Trash
		} else if err != nil {
			return err
		}
		mvs = append(mvs, mv{src, filepath.Join(fdir, base)})
	}
	if err := os.MkdirAll(fdir, 0o700); err != nil { // inside .ids, which is private anyway
		return err
	}
	rev, err := v.nextRev()
	if err != nil {
		return err
	}
	b, err := json.Marshal(trashRecord{Group: g, Folder: path.Dir(files[0].Path), DeletedAt: time.Now().UTC(), DeletedRev: rev, LocalOnly: localOnly})
	if err != nil {
		return err
	}
	// Record first, files second, live record last: at every step the note is
	// either still live or restorable from the trash.
	if _, err := writeAtomic(filepath.Join(tdir, "record.json"), bytes.NewReader(b), true); err != nil {
		return err
	}
	for i, m := range mvs {
		if err := os.Rename(m.src, m.dst); err != nil {
			for _, d := range mvs[:i] {
				os.Rename(d.dst, d.src)
			}
			// Tidy the half-made entry, but only if it is empty: never RemoveAll
			// here, an earlier interrupted attempt may have left files in it.
			if os.Remove(fdir) == nil {
				os.Remove(filepath.Join(tdir, "record.json"))
				os.Remove(tdir)
			}
			return err
		}
	}
	if err := os.Remove(filepath.Join(v.root, idsDir, id)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	delete(v.missing, id)
	delete(v.revs, id)
	v.dels[id] = rev
	v.signal()
	return nil
}

// ListTrash returns the trash, ordered by ID. An entry that cannot be read is
// left alone and reported in the error; the rest are still returned.
func (v *Vault) ListTrash() ([]TrashedGroup, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	ids, err := v.trashIDs()
	if err != nil {
		return nil, err
	}
	var out []TrashedGroup
	var errs []error
	for _, id := range ids {
		r, err := v.readTrash(id)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			errs = append(errs, err)
			continue
		}
		out = append(out, TrashedGroup{r.Group, r.Folder, r.DeletedAt, r.DeletedRev, r.LocalOnly})
	}
	return out, errors.Join(errs...)
}

func (v *Vault) trashIDs() ([]string, error) {
	ents, err := os.ReadDir(filepath.Join(v.root, idsDir, trashDir))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var ids []string
	for _, e := range ents {
		if e.IsDir() && idRe.MatchString(e.Name()) {
			ids = append(ids, e.Name())
		}
	}
	return ids, nil
}

func (v *Vault) writeTrashRecord(id string, r trashRecord) error {
	b, err := json.Marshal(r)
	if err != nil {
		return err
	}
	_, err = writeAtomic(filepath.Join(v.trashPath(id), "record.json"), bytes.NewReader(b), true)
	return err
}

// openRestores finishes restores a crash interrupted. One that can't be
// finished stays in the trash, and its paths stay out of Rescan
// (restoringPaths); restoring it again from the trash finishes it.
func (v *Vault) openRestores() error {
	ids, err := v.trashIDs()
	if err != nil {
		return err
	}
	for _, id := range ids {
		r, err := v.readTrash(id)
		if err != nil || r.Restoring == nil {
			continue
		}
		if _, err := v.load(id); err == nil {
			v.dropTrash(id) // the live record was saved; only the entry was left
			continue
		}
		v.restore(id, "")
	}
	return nil
}

// restoringPaths returns where interrupted restores put their files.
func (v *Vault) restoringPaths(out map[string]bool) {
	ids, _ := v.trashIDs()
	for _, id := range ids {
		r, err := v.readTrash(id)
		if err != nil || r.Restoring == nil {
			continue
		}
		for _, f := range r.Group.all() {
			if *r.Restoring != "" {
				out[path.Join(*r.Restoring, path.Base(f.Path))] = true
			} else {
				out[f.Path] = true
			}
		}
	}
}

// Restore moves a trashed group back, keeping its ID. folder is where to put
// it; "" means where each file was. If any destination name is taken, nothing
// moves.
func (v *Vault) Restore(id, folder string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.restore(id, folder)
}

// Trashed returns the trash entry of group id, or ErrNotFound.
func (v *Vault) Trashed(id string) (TrashedGroup, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	r, err := v.readTrash(id)
	if err != nil {
		return TrashedGroup{}, err
	}
	return TrashedGroup{r.Group, r.Folder, r.DeletedAt, r.DeletedRev, r.LocalOnly}, nil
}

func (v *Vault) restore(id, folder string) error {
	rec, err := v.readTrash(id)
	if err != nil {
		return err
	}
	resuming := rec.Restoring != nil
	if resuming {
		folder = *rec.Restoring // some files may be there already
	}
	g := rec.Group
	fdir := filepath.Join(v.trashPath(id), "files")

	type mv struct {
		f        *File
		to       string
		src, dst string
	}
	var mvs []mv
	taken := map[string]bool{}
	for _, f := range g.all() {
		base := path.Base(f.Path)
		to := f.Path
		if folder != "" {
			to = path.Join(folder, base)
		}
		dst, err := v.abs(to)
		if err != nil {
			return err
		}
		if taken[to] {
			return ErrExists
		}
		taken[to] = true
		src := filepath.Join(fdir, base)
		_, serr := os.Lstat(src)
		if serr != nil && !errors.Is(serr, fs.ErrNotExist) {
			return serr
		}
		_, derr := os.Lstat(dst)
		if derr != nil && !errors.Is(derr, fs.ErrNotExist) {
			return derr
		}
		switch {
		case serr == nil && derr == nil:
			return ErrExists
		case serr == nil:
			mvs = append(mvs, mv{f, to, src, dst})
		case derr == nil:
			// Gone from the trash and a file is at its name: moved there by
			// the interrupted restore, or another note's file (never claim it).
			if h, err := hashFile(dst); err != nil || !resuming || h != f.Version {
				return errors.Join(ErrExists, err)
			}
		}
		// Neither: it was already missing when it was trashed.
	}
	if !resuming {
		nr := rec
		nr.Restoring = &folder
		if err := v.writeTrashRecord(id, nr); err != nil {
			return err
		}
	}
	for i, m := range mvs {
		err := v.mkdirs(filepath.Dir(m.dst))
		if err == nil {
			err = os.Rename(m.src, m.dst)
		}
		if err != nil {
			for _, d := range mvs[:i] {
				os.Rename(d.dst, d.src)
			}
			if !resuming { // all back in the trash: not a restore to finish on open
				v.writeTrashRecord(id, rec)
			}
			return err
		}
	}
	for _, f := range g.all() {
		if folder != "" {
			f.Path = path.Join(folder, path.Base(f.Path))
		}
	}
	if err := v.save(&g); err != nil {
		return err
	}
	delete(v.dels, id) // the record's new rev tells devices it is back
	v.dropTrash(id)
	return nil
}

// dropTrash removes the entry of a group that is live again, record first so
// it stops being listed.
func (v *Vault) dropTrash(id string) {
	os.Remove(filepath.Join(v.trashPath(id), "record.json"))
	os.Remove(filepath.Join(v.trashPath(id), "files"))
	os.Remove(v.trashPath(id))
}

// Purge permanently deletes trash entries deleted before the given time. It
// returns the IDs it removed and how many entries the trash held in total.
// With dryRun it deletes nothing and returns the IDs it would remove. It
// deletes only inside .ids/trash/<id>/ (and that note's history), skips any
// entry whose note is also live (an interrupted Trash), and removes files
// before the record so an interruption just means the next Purge finishes.
func (v *Vault) Purge(before time.Time, dryRun bool) (purged []string, total int, err error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	ids, err := v.trashIDs()
	if err != nil {
		return nil, 0, err
	}
	var errs []error
	for _, id := range ids {
		r, err := v.readTrash(id)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			errs = append(errs, err)
			continue
		}
		total++
		if !r.DeletedAt.Before(before) {
			continue
		}
		if _, err := os.Lstat(filepath.Join(v.root, idsDir, id)); err == nil {
			continue
		}
		if dryRun {
			purged = append(purged, id)
			continue
		}
		if err := v.removeTrashEntry(id, r); err != nil {
			errs = append(errs, err)
			continue
		}
		purged = append(purged, id)
	}
	return purged, total, errors.Join(errs...)
}

// DeleteForever permanently deletes one trash entry (the user's "delete
// forever"), with the same narrow rules as Purge: it deletes only inside
// .ids/trash/<id>/ and that note's history, and refuses an entry whose note is
// also live (an interrupted Trash), which should be restored instead.
func (v *Vault) DeleteForever(id string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	r, err := v.readTrash(id)
	if err != nil {
		return err
	}
	if _, err := os.Lstat(filepath.Join(v.root, idsDir, id)); err == nil {
		return ErrExists
	}
	return v.removeTrashEntry(id, r)
}

// removeTrashEntry deletes a trash entry's files and history, then its record
// last, so an interruption just leaves an entry the next delete finishes. The
// caller has read r with readTrash.
//
// The entry is also the delete's marker in the change feed, so first the
// vault notes that the feed forgets deletes up to its number: a device that
// has not read that far must do a full check (see Forgotten).
func (v *Vault) removeTrashEntry(id string, r trashRecord) error {
	if r.DeletedRev > v.forgotten {
		if err := writeSeq(filepath.Join(v.root, idsDir, syncDir, forgottenFile), r.DeletedRev); err != nil {
			return err
		}
		v.forgotten = r.DeletedRev
	}
	delete(v.dels, id)
	tdir := v.trashPath(id)
	if err := os.RemoveAll(filepath.Join(tdir, "files")); err != nil {
		return err
	}
	if err := os.RemoveAll(v.historyPath(id)); err != nil {
		return err
	}
	if err := os.Remove(filepath.Join(tdir, "record.json")); err != nil {
		return err
	}
	os.Remove(tdir)
	return nil
}
