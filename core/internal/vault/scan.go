package vault

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// Settle is how recently a file must have been modified before Rescan leaves
// it for the next scan, so a half-written file is never read.
var Settle = 2 * time.Second

// Kind says what Rescan found for a group.
type Kind string

const (
	Added       Kind = "added"    // a new file in the vault became a new group
	Modified    Kind = "modified" // a file's content changed outside the app
	Moved       Kind = "moved"    // a file or folder was moved or renamed outside the app
	WentMissing Kind = "missing"  // a file is gone; the record is kept
)

// Change is one group affected by a Rescan.
type Change struct {
	Kind Kind
	ID   string
}

// MissingGroup is a group with files that are no longer on disk.
type MissingGroup struct {
	ID    string
	Paths []string
}

type cached struct {
	size int64
	mod  time.Time
	hash string
}

var emptyHash = hashBytes(nil)

// Rescan compares the folder with the records and fixes the records to match:
//   - changed content updates the record's version
//   - a file that vanished and reappeared elsewhere with the same content is a
//     move; the group keeps its ID (works for whole renamed folders too)
//   - every other new file becomes its own group
//   - a file that is gone for good is never deleted from the records; the
//     group is reported once as WentMissing and listed by Missing until the
//     user decides what to do
//
// pending is true if some files were too fresh to read; scan again after Settle.
// shortcut: the first scan hashes every file while holding the vault lock;
// later scans only re-read files whose size or modified time changed.
func (v *Vault) Rescan() (changes []Change, pending bool, err error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	groups, err := v.list()
	if err != nil {
		return nil, false, err
	}
	owner := map[string]*File{} // path → record entry
	groupOf := map[string]int{}
	for i := range groups {
		for _, f := range groups[i].all() {
			owner[f.Path], groupOf[f.Path] = f, i
		}
	}

	type entry struct {
		rel  string
		size int64
		mod  time.Time
	}
	var found []entry
	err = filepath.WalkDir(v.root, func(p string, d fs.DirEntry, err error) error {
		if errors.Is(err, fs.ErrNotExist) && p != v.root {
			return nil // vanished while we walked
		}
		if err != nil {
			return err
		}
		if p == v.root {
			return nil
		}
		if d.IsDir() {
			if strings.HasPrefix(d.Name(), ".") { // .ids and other hidden folders
				return fs.SkipDir
			}
			return nil
		}
		if !d.Type().IsRegular() || ignored(d.Name()) {
			return nil
		}
		fi, err := d.Info()
		if errors.Is(err, fs.ErrNotExist) {
			return nil
		}
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(v.root, p)
		if err != nil {
			return err
		}
		found = append(found, entry{filepath.ToSlash(rel), fi.Size(), fi.ModTime()})
		return nil
	})
	if err != nil {
		return nil, false, err
	}

	now := time.Now()
	seen := map[string]cached{}
	changed := map[int]Kind{}
	var cands []entry // new files not in any record
	candHash := map[string]string{}
	for _, e := range found {
		if d := now.Sub(e.mod); d >= 0 && d < Settle {
			pending = true
			continue
		}
		h, ok := "", false
		if c, hit := v.seen[e.rel]; hit && c.size == e.size && c.mod.Equal(e.mod) {
			h, ok = c.hash, true
		}
		if !ok {
			h, err = hashFile(filepath.Join(v.root, filepath.FromSlash(e.rel)))
			if errors.Is(err, fs.ErrNotExist) {
				continue
			}
			if err != nil {
				return nil, false, err
			}
		}
		seen[e.rel] = cached{e.size, e.mod, h}
		if f, claimed := owner[e.rel]; claimed {
			gi := groupOf[e.rel]
			if h != f.Version && groups[gi].Note == f {
				// A note edited outside the app: save the new text as a version.
				b, err := readCapped(filepath.Join(v.root, filepath.FromSlash(e.rel)))
				switch {
				case errors.Is(err, fs.ErrNotExist):
					continue
				case errors.Is(err, ErrTooLarge):
					// too large to keep versions of: the hash streamed above stands
				case err != nil:
					return nil, false, err
				default:
					h = hashBytes(b) // what we read, not what we hashed a moment ago
					seen[e.rel] = cached{e.size, e.mod, h}
					if h != f.Version {
						if err := v.snapshot(groups[gi], b, nil, false); err != nil {
							return nil, false, err
						}
					}
				}
			}
			if h != f.Version {
				f.Version = h
				if changed[gi] == "" {
					changed[gi] = Modified
				}
			}
		} else {
			cands = append(cands, e)
			candHash[e.rel] = h
		}
	}

	// Recorded files that are gone: look for the same content somewhere new.
	paths := make([]string, 0, len(owner))
	for p := range owner {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	used := map[string]bool{}
	for _, p := range paths {
		f := owner[p]
		if _, err := os.Lstat(filepath.Join(v.root, filepath.FromSlash(p))); !errors.Is(err, fs.ErrNotExist) {
			continue
		}
		if f.Version == emptyHash { // every empty file looks alike; never guess
			continue
		}
		for _, c := range cands {
			if !used[c.rel] && candHash[c.rel] == f.Version {
				used[c.rel] = true
				f.Path = c.rel
				changed[groupOf[p]] = Moved
				break
			}
		}
	}

	idx := make([]int, 0, len(changed))
	for i := range changed {
		idx = append(idx, i)
	}
	sort.Ints(idx)
	for _, i := range idx {
		if err := v.save(groups[i]); err != nil {
			return nil, false, err
		}
		changes = append(changes, Change{changed[i], groups[i].ID})
	}
	for _, c := range cands {
		if used[c.rel] {
			continue
		}
		g := Group{ID: newID(), Files: []File{}}
		f := File{c.rel, candHash[c.rel]}
		if isMD(c.rel) {
			b, err := readCapped(filepath.Join(v.root, filepath.FromSlash(c.rel)))
			switch {
			case errors.Is(err, fs.ErrNotExist):
				continue
			case errors.Is(err, ErrTooLarge):
				// adopted, but no version is kept; the streamed hash stands
			case err != nil:
				return nil, false, err
			default:
				f.Version = hashBytes(b)
				if err := v.snapshot(g, b, nil, false); err != nil {
					return nil, false, err
				}
			}
			g.Note = &f
		} else {
			g.Files = append(g.Files, f)
		}
		if err := v.save(g); err != nil {
			return nil, false, err
		}
		changes = append(changes, Change{Added, g.ID})
	}

	now2 := map[string]bool{}
	for _, m := range v.missingIn(groups) {
		now2[m.ID] = true
		if !v.missing[m.ID] {
			changes = append(changes, Change{WentMissing, m.ID})
		}
	}
	v.missing, v.seen = now2, seen
	return changes, pending, nil
}

// Missing lists the groups that have files no longer on disk, with those
// paths. A group leaves the list when its files come back.
func (v *Vault) Missing() ([]MissingGroup, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	groups, err := v.list()
	if err != nil {
		return nil, err
	}
	return v.missingIn(groups), nil
}

func (v *Vault) missingIn(groups []Group) []MissingGroup {
	var out []MissingGroup
	for i := range groups {
		m := MissingGroup{ID: groups[i].ID}
		for _, f := range groups[i].all() {
			if _, err := os.Lstat(filepath.Join(v.root, filepath.FromSlash(f.Path))); errors.Is(err, fs.ErrNotExist) {
				m.Paths = append(m.Paths, f.Path)
			}
		}
		if len(m.Paths) > 0 {
			out = append(out, m)
		}
	}
	return out
}

// ignored names are never adopted: hidden files (including our temp files),
// editor leftovers, and names the vault's path rules would reject anyway.
func ignored(name string) bool {
	return strings.HasPrefix(name, ".") || strings.HasSuffix(name, "~") ||
		strings.HasSuffix(name, ".swp") || strings.HasSuffix(name, ".swx") ||
		strings.ContainsAny(name, `\:`)
}

func hashFile(p string) (string, error) {
	f, err := os.Open(p)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
