package syncdev

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/epiclabs-io/diff3"

	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

// maxMerges caps merge-then-push rounds for one group in one pass. Each round
// means the atrium changed again while this device was merging.
const maxMerges = 5

// mergeGroup is the engine's Merge: it makes the local group the three-way
// merge of local, remote and the base (see "Merge happens on the device" in
// DESIGN.md). base is nil if the two sides never agreed.
//
// The order matters for crashes. The files, paths and flag are saved in one
// vault.Apply first; the note's text is written last, with the device's own
// text kept as a history version. A crash before the text write finds the
// text still unmerged and merges it again. (A crash after it, before the push,
// can merge the merged text a second time: nested markers, nothing lost.)
func (e *Engine) mergeGroup(ctx context.Context, id string, base *Base, local, remote vault.Group) error {
	var b vault.Group
	if base != nil {
		b = base.Group(id)
	}

	// The note's text.
	var mine, merged []byte
	var ver string
	textConflict := false
	if local.Note != nil && remote.Note != nil {
		var err error
		if mine, ver, err = e.v.ReadNote(id); err != nil {
			return err
		}
		if ver != local.Note.Version {
			return held("changed on disk; waiting for the scan to catch up")
		}
		theirs := mine
		if remote.Note.Version != local.Note.Version {
			if theirs, err = e.fetch(ctx, id, -1, remote.Note.Version); err != nil {
				return err
			}
		}
		var baseText []byte
		if base != nil {
			baseText = base.Text
		}
		switch {
		case bytes.Equal(mine, theirs):
			merged = mine
		case base != nil && bytes.Equal(mine, baseText):
			merged = theirs
		case base != nil && bytes.Equal(theirs, baseText):
			merged = mine
		default:
			merged, textConflict = merge3(mine, baseText, theirs)
		}
	}

	// Paths, attached files and the flag.
	want := vault.Group{ID: id, Note: local.Note, History: pick(b.History, local.History, remote.History, ptrEq)}
	dir := path.Dir(firstPath(local))
	if fs := allFiles(b); len(fs) > 0 {
		dir = pick(path.Dir(fs[0].Path), dir, path.Dir(firstPath(remote)), eq)
	} else {
		dir = path.Dir(firstPath(remote)) // never agreed: the atrium's choice wins
	}
	place := func(bp, lp, rp string) string {
		return path.Join(dir, pick(path.Base(bp), path.Base(lp), path.Base(rp), eq))
	}
	// A path missing on a side takes the other side's.
	basePath := func(f *vault.File) string {
		if f == nil {
			return ""
		}
		return f.Path
	}
	switch {
	case local.Note == nil && remote.Note != nil:
		if _, err := e.fetch(ctx, id, -1, remote.Note.Version); err != nil {
			return err
		}
		want.Note = &vault.File{Path: place(basePath(b.Note), remote.Note.Path, remote.Note.Path), Version: remote.Note.Version}
	case local.Note != nil && remote.Note != nil:
		want.Note = &vault.File{Path: place(basePath(b.Note), local.Note.Path, remote.Note.Path), Version: local.Note.Version}
	case local.Note != nil:
		want.Note = &vault.File{Path: place(basePath(b.Note), local.Note.Path, local.Note.Path), Version: local.Note.Version}
	}

	fileConflict := false
	var extra []vault.File // this device's version of a file changed on both sides
	taken := map[string]bool{}
	for i := 0; i < max(len(local.Files), len(remote.Files)); i++ {
		var bf *vault.File
		if i < len(b.Files) {
			bf = &b.Files[i]
		}
		switch {
		case i >= len(remote.Files): // only here
			lf := local.Files[i]
			want.Files = append(want.Files, vault.File{Path: place(basePath(bf), lf.Path, lf.Path), Version: lf.Version})
		case i >= len(local.Files): // only on the atrium
			rf := remote.Files[i]
			if _, err := e.fetch(ctx, id, i, rf.Version); err != nil {
				return err
			}
			want.Files = append(want.Files, vault.File{Path: place(basePath(bf), rf.Path, rf.Path), Version: rf.Version})
		default:
			lf, rf := local.Files[i], remote.Files[i]
			p := place(basePath(bf), lf.Path, rf.Path)
			lChanged := bf == nil || lf.Version != bf.Version
			rChanged := bf == nil || rf.Version != bf.Version
			switch {
			case lf.Version == rf.Version || !rChanged:
				want.Files = append(want.Files, vault.File{Path: p, Version: lf.Version})
			default:
				if _, err := e.fetch(ctx, id, i, rf.Version); err != nil {
					return err
				}
				want.Files = append(want.Files, vault.File{Path: p, Version: rf.Version})
				if lChanged { // changed on both sides: keep both
					if err := e.stageLocal(id, i, lf.Version); err != nil {
						return err
					}
					extra = append(extra, vault.File{Path: lf.Path, Version: lf.Version})
					fileConflict = true
				}
			}
		}
		taken[want.Files[len(want.Files)-1].Path] = true
	}
	for _, f := range extra {
		f.Path = e.keptName(path.Join(dir, path.Base(f.Path)), taken)
		taken[f.Path] = true
		want.Files = append(want.Files, f)
	}

	want.Conflict = pick(b.Conflict, local.Conflict, remote.Conflict, eq) || textConflict || fileConflict
	if !vault.SameState(local, want) {
		if _, err := e.v.Apply(id, local.Rev, want); err != nil {
			return err
		}
		e.refresh(id)
	}
	if merged != nil && !bytes.Equal(merged, mine) {
		if _, err := e.v.WriteNoteMerged(id, merged, ver, want.Conflict); err != nil {
			return err
		}
		e.refresh(id)
	}
	return nil
}

// fetch downloads one file of the atrium's group and returns its content.
func (e *Engine) fetch(ctx context.Context, id string, index int, version string) ([]byte, error) {
	if err := e.download(ctx, id, index, version); err != nil {
		return nil, err
	}
	// Through the engine's own download, so a dropped reply hits it too; the
	// vault checked the hash when it staged the file.
	return os.ReadFile(filepath.Join(e.v.Root(), ".ids", "sync", "blobs", version))
}

// stageLocal copies a local attached file into the staging area, so the
// device's version can be written back under a second name.
func (e *Engine) stageLocal(id string, index int, version string) error {
	f, _, err := e.v.OpenFile(id, index)
	if err != nil {
		return err
	}
	defer f.Close()
	return e.v.StageBlob(version, f, syncsrv.MaxBlob)
}

// keptName turns "dir/Name.png" into "dir/Name (device name).png", numbered if
// that is taken in the group or on disk.
func (e *Engine) keptName(p string, taken map[string]bool) string {
	dev := strings.NewReplacer("/", "-", "\\", "-").Replace(e.DeviceName)
	if dev == "" {
		dev = "this device"
	}
	ext := path.Ext(p)
	stem := strings.TrimSuffix(p, ext)
	cand := fmt.Sprintf("%s (%s)%s", stem, dev, ext)
	for n := 2; taken[cand] || exists(e.v.Root(), cand); n++ {
		cand = fmt.Sprintf("%s (%s %d)%s", stem, dev, n, ext)
	}
	return cand
}

// merge3 is a line-based three-way merge. Lines both sides changed in
// different ways are written between conflict markers, this device's first.
func merge3(mine, base, theirs []byte) ([]byte, bool) {
	var out []string
	conflict := false
	for _, r := range diff3.Diff3Merge(lines(mine), lines(base), lines(theirs), true) {
		if r.Conflict == nil {
			out = append(out, r.Ok...)
			continue
		}
		conflict = true
		out = append(out, "<<<<<<< this device")
		out = append(out, r.Conflict.A...)
		out = append(out, "=======")
		out = append(out, r.Conflict.B...)
		out = append(out, ">>>>>>> atrium")
	}
	s := strings.Join(out, "\n")
	if len(out) > 0 && bytes.HasSuffix(mine, []byte("\n")) {
		s += "\n"
	}
	return []byte(s), conflict
}

// lines splits text into lines without the final newline's empty last line.
func lines(b []byte) []string {
	s := strings.TrimSuffix(string(b), "\n")
	if s == "" {
		return nil
	}
	return strings.Split(s, "\n")
}

// pick is a three-way choice of one field: a side that changed it wins, and
// if both changed it differently the atrium's (remote) value wins.
func pick[T any](base, local, remote T, same func(a, b T) bool) T {
	switch {
	case same(local, remote):
		return local
	case same(local, base):
		return remote
	case same(remote, base):
		return local
	}
	return remote
}

func eq[T comparable](a, b T) bool { return a == b }

func ptrEq(a, b *bool) bool { return (a == nil) == (b == nil) && (a == nil || *a == *b) }
