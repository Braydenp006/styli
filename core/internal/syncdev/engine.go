// Package syncdev is the device side of sync: it keeps this device's vault in
// step with its atrium (see "Sync protocol" in docs/DESIGN.md). The atrium's
// side is syncsrv.
//
// Each pass compares, for every group that changed on either side, three
// states: the base (what both last agreed on), local (this vault) and remote
// (the atrium). It then pushes, pulls, records a new base or merges. A pass
// looks at what is actually there, never at a log of half-done steps, so a
// crash at any point only means the next pass does the step again.
package syncdev

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"styli/core/internal/store"
	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

// MergeFunc merges a group changed on both sides. It must leave the local
// group holding the merged result (saving the device's own text as a version
// first); the engine then pushes it on top of remote. base is nil for a group
// both sides have but never agreed on.
type MergeFunc func(ctx context.Context, id string, base *Base, local, remote vault.Group) error

// Engine syncs one device vault with its atrium.
type Engine struct {
	v    *vault.Vault
	st   *store.Store // optional; kept in step with what is pulled
	c    *client
	rec  records
	logf func(string, ...any)

	// Merge is nil until merging is built: a group changed on both sides is
	// then left unsynced and listed in the status. Nothing is lost.
	Merge MergeFunc

	mu     sync.Mutex
	status Status
}

// Status is what the device's screens show about sync.
type Status struct {
	Atrium   string    `json:"atrium"`             // the atrium's address
	LastSync time.Time `json:"last_sync,omitzero"` // the last pass that finished
	Error    string    `json:"error,omitempty"`    // why the last pass stopped, if it did
	Waiting  []Waiting `json:"waiting"`            // groups that could not sync yet
}

// Waiting is a group that could not sync on the last pass, and why.
type Waiting struct {
	ID     string `json:"id"`
	Path   string `json:"path"`
	Reason string `json:"reason"`
}

// New makes an engine for the vault v, talking to the atrium at addr with the
// pinned TLS setup tc (see syncsrv.ClientTLS).
func New(v *vault.Vault, st *store.Store, tc *tls.Config, addr string, logf func(string, ...any)) *Engine {
	return &Engine{v: v, st: st, c: newClient(tc, addr), rec: newRecords(v.Root()), logf: logf, status: Status{Atrium: addr, Waiting: []Waiting{}}}
}

// Status returns the last pass's status.
func (e *Engine) Status() Status {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := e.status
	s.Waiting = slices.Clone(s.Waiting)
	return s
}

// skip is a group that cannot sync on this pass. The pass goes on with the
// other groups; hold keeps the cursor from moving past the group's change, so
// it is read again next time.
type skip struct {
	reason string
	hold   bool
}

func (s *skip) Error() string { return s.reason }

func held(reason string) error { return &skip{reason, true} }

// groupErr turns an error about one group into a skip, or leaves it as an
// error that ends the pass (the network, the atrium failing, this disk).
func groupErr(err error, reason string) error {
	var ae *apiError
	if errors.As(err, &ae) {
		if ae.Status >= 500 {
			return err
		}
		return held(fmt.Sprintf("%s (%s)", reason, ae.Code))
	}
	for _, known := range []error{vault.ErrConflict, vault.ErrExists, vault.ErrTrashed, vault.ErrMissingFile, vault.ErrNotFound,
		vault.ErrNoNote, vault.ErrBadPath, vault.ErrBadGroup, vault.ErrTooLarge, vault.ErrHashMismatch} {
		if errors.Is(err, known) {
			return held(fmt.Sprintf("%s (%v)", reason, err))
		}
	}
	return err
}

// pass is the state of one sync pass.
type pass struct {
	waiting map[string]string // group ID -> why it is waiting
	paths   map[string]string // local path -> group ID; built when a name clash needs it
}

// Sync runs one pass. It returns an error only when the pass could not reach
// the end (the atrium is unreachable, a disk error); groups that cannot sync
// yet are in the status instead.
func (e *Engine) Sync(ctx context.Context) error {
	p := &pass{waiting: map[string]string{}}
	err := e.sync(ctx, p)
	e.mu.Lock()
	defer e.mu.Unlock()
	if err != nil {
		e.status.Error = err.Error()
		return err
	}
	e.status.Error = ""
	e.status.LastSync = time.Now()
	e.status.Waiting = []Waiting{}
	for id, why := range p.waiting {
		w := Waiting{ID: id, Reason: why}
		if g, err := e.v.Get(id); err == nil {
			w.Path = firstPath(g)
		} else if b, _ := e.rec.base(id); b != nil {
			w.Path = firstPath(b.Group(id))
		}
		e.status.Waiting = append(e.status.Waiting, w)
	}
	slices.SortFunc(e.status.Waiting, func(a, b Waiting) int { return strings.Compare(a.Path, b.Path) })
	return nil
}

func (e *Engine) sync(ctx context.Context, p *pass) error {
	h, err := e.c.hello(ctx)
	if err != nil {
		return fmt.Errorf("cannot reach the atrium: %w", err)
	}
	if h.Protocol != syncsrv.Protocol {
		return fmt.Errorf("the atrium speaks sync protocol %d and this device %d; update the older one", h.Protocol, syncsrv.Protocol)
	}
	cur, err := e.rec.cursor()
	if err != nil {
		return err
	}
	// Full check: a new epoch (restored atrium) or a cursor ahead of the atrium
	// means the cursor can't be trusted, so read every group from the start.
	full := cur.Epoch != h.Epoch || cur.Since > h.Seq
	since := cur.Since
	if full {
		since = 0
	}

	// 1. What changed on the atrium.
	seen := map[string]bool{}
	pos, done, holding := since, since, false
	for {
		pg, err := e.c.changes(ctx, pos, 0)
		if err != nil {
			return fmt.Errorf("reading the atrium's changes: %w", err)
		}
		if pg.Epoch != h.Epoch {
			return errors.New("the atrium was restored during sync; trying again")
		}
		for i := range pg.Groups {
			g := pg.Groups[i]
			seen[g.ID] = true
			if err := e.group(ctx, p, g.ID, &g); err != nil {
				var sk *skip
				if !errors.As(err, &sk) {
					return err
				}
				p.waiting[g.ID] = sk.reason
				holding = holding || sk.hold
			}
			if !holding {
				done = g.Rev
			}
		}
		pos = pg.Next
		if !pg.More {
			break
		}
	}
	if !holding {
		done = max(done, pos)
	}
	if full || done != cur.Since {
		if err := e.rec.saveCursor(Cursor{Epoch: h.Epoch, Since: done}); err != nil {
			return err
		}
	}

	locals, err := e.v.List()
	if err != nil {
		return err
	}
	if full {
		e.reportGone(locals, seen)
	}

	// 2. What changed here and wasn't handled above.
	for _, l := range locals {
		if seen[l.ID] {
			continue
		}
		b, err := e.rec.base(l.ID)
		if err != nil {
			return err
		}
		if b != nil && vault.SameState(l, b.Group(l.ID)) {
			continue
		}
		if err := e.group(ctx, p, l.ID, nil); err != nil {
			var sk *skip
			if !errors.As(err, &sk) {
				return err
			}
			p.waiting[l.ID] = sk.reason
		}
	}
	return nil
}

// reportGone logs groups this device has synced that the atrium's complete
// list no longer has. Deletes are not built yet, so they are left as they are.
func (e *Engine) reportGone(locals []vault.Group, seen map[string]bool) {
	for _, l := range locals {
		if seen[l.ID] {
			continue
		}
		if b, err := e.rec.base(l.ID); err == nil && b != nil {
			e.logf("sync: %s (%s) is no longer on the atrium; left as it is here", l.ID, firstPath(l))
		}
	}
}

// group decides and carries out what one group needs. remote is the atrium's
// state from the change feed, or nil if the feed did not list it (then the
// atrium still holds the base).
func (e *Engine) group(ctx context.Context, p *pass, id string, remote *syncsrv.Group) error {
	base, err := e.rec.base(id)
	if err != nil {
		return err
	}
	local, err := e.v.Get(id)
	hasLocal := err == nil
	if err != nil && !errors.Is(err, vault.ErrNotFound) {
		return err
	}

	var r vault.Group
	switch {
	case remote != nil:
		r = fromWire(*remote)
	case base != nil:
		r = base.Group(id)
	}

	switch {
	case !hasLocal && remote == nil:
		return nil
	case !hasLocal && base != nil:
		// Synced before, gone from this vault now (trashed here): deletes are
		// not built yet, so the atrium keeps it and so does this device's trash.
		return &skip{reason: "deleted on this device; deletes don't sync yet", hold: false}
	case !hasLocal:
		return e.pull(ctx, p, id, nil, r)
	case base == nil && remote == nil:
		return e.push(ctx, p, id, local, nil, vault.Group{})
	case base == nil:
		// Both have it but never agreed: an earlier push or pull whose base
		// was not written before a crash, or a real clash.
		if vault.SameState(local, r) {
			return e.recordBase(id, r)
		}
		return e.merge(ctx, p, id, nil, local, r)
	}

	b := base.Group(id)
	lChanged, rChanged := !vault.SameState(local, b), !vault.SameState(r, b)
	switch {
	case !lChanged && !rChanged:
		if r.Rev != base.Rev { // a restored atrium renumbered it
			base.Rev = r.Rev
			return e.rec.saveBase(id, *base)
		}
		return nil
	case lChanged && !rChanged:
		return e.push(ctx, p, id, local, base, r)
	case !lChanged:
		return e.pull(ctx, p, id, &local, r)
	case vault.SameState(local, r):
		return e.recordBase(id, r)
	default:
		return e.merge(ctx, p, id, base, local, r)
	}
}

func (e *Engine) merge(ctx context.Context, p *pass, id string, base *Base, local, remote vault.Group) error {
	if e.Merge == nil {
		return held("changed here and on the atrium; waiting for merging, which isn't built yet")
	}
	if err := e.Merge(ctx, id, base, local, remote); err != nil {
		return groupErr(err, "the merge failed")
	}
	merged, err := e.v.Get(id)
	if err != nil {
		return groupErr(err, "the merged note is gone")
	}
	return e.push(ctx, p, id, merged, base, remote)
}

// push sends the local group to the atrium. remote is what the atrium is
// believed to hold (empty for a new group): its rev is the commit's base, and
// only content that differs from it is uploaded.
func (e *Engine) push(ctx context.Context, p *pass, id string, local vault.Group, base *Base, remote vault.Group) error {
	text, err := e.upload(ctx, id, local, remote)
	if err != nil {
		return err
	}
	for try := 0; ; try++ {
		got, err := e.c.commit(ctx, id, remote.Rev, local)
		if err == nil {
			return e.saveBase(id, fromWire(got), text)
		}
		var ae *apiError
		if !errors.As(err, &ae) || ae.Status >= 500 {
			return err
		}
		switch {
		case ae.Code == "conflict" && ae.Current != nil:
			cur := fromWire(*ae.Current)
			if vault.SameState(cur, remote) {
				if cur.Rev == remote.Rev || try > 0 {
					// The atrium refused although nothing changed there: it has an
					// outside edit it hasn't scanned yet. Not a merge; try later.
					return held("the atrium is catching up on an edit made there; trying again soon")
				}
				remote.Rev = cur.Rev // only the numbering moved on
				continue
			}
			return e.merge(ctx, p, id, base, local, cur)
		case ae.Code == "path_taken" && remote.Rev == 0:
			taken, err := e.atriumPaths()
			if err != nil {
				return err
			}
			if err := e.renameLocal(p, local, taken); err != nil {
				return err
			}
			return held("its name was taken on the atrium, so it was renamed here; it syncs on the next pass")
		default:
			return groupErr(err, "the atrium refused the change")
		}
	}
}

// upload sends the local files that differ from remote at the same position,
// and returns the note's text (needed for the base). Content that no longer
// matches the record (an edit not scanned yet) is not sent.
func (e *Engine) upload(ctx context.Context, id string, local, remote vault.Group) ([]byte, error) {
	var text []byte
	if local.Note != nil {
		t, ver, err := e.v.ReadNote(id)
		if err != nil {
			return nil, groupErr(err, "cannot read the note")
		}
		if ver != local.Note.Version {
			return nil, held("changed on disk; waiting for the scan to catch up")
		}
		text = t
		if remote.Note == nil || remote.Note.Version != ver {
			if err := e.c.putBlob(ctx, ver, strings.NewReader(string(t)), int64(len(t))); err != nil {
				return nil, groupErr(err, "uploading the note failed")
			}
		}
	}
	for i, f := range local.Files {
		if i < len(remote.Files) && remote.Files[i].Version == f.Version {
			continue
		}
		if err := e.uploadFile(ctx, id, i, f.Version); err != nil {
			return nil, err
		}
	}
	return text, nil
}

func (e *Engine) uploadFile(ctx context.Context, id string, i int, version string) error {
	f, _, err := e.v.OpenFile(id, i)
	if err != nil {
		return groupErr(err, "cannot read an attached file")
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return err
	}
	if err := e.c.putBlob(ctx, version, f, fi.Size()); err != nil {
		// hash_mismatch: the file changed since the scan; the next pass sends it.
		return groupErr(err, "uploading an attached file failed")
	}
	return nil
}

// pull makes the local group equal remote. local is nil if this device does
// not have it yet. Local files are only written if they are still what this
// pass saw: vault.Apply refuses otherwise, and the next pass decides again.
func (e *Engine) pull(ctx context.Context, p *pass, id string, local *vault.Group, remote vault.Group) error {
	var have vault.Group
	var localRev uint64
	if local != nil {
		have, localRev = *local, local.Rev
	}
	if remote.Note != nil && (have.Note == nil || have.Note.Version != remote.Note.Version) {
		if err := e.download(ctx, id, -1, remote.Note.Version); err != nil {
			return err
		}
	}
	for i, f := range remote.Files {
		if i < len(have.Files) && have.Files[i].Version == f.Version {
			continue
		}
		if err := e.download(ctx, id, i, f.Version); err != nil {
			return err
		}
	}

	for try := 0; ; try++ {
		_, err := e.v.Apply(id, localRev, remote)
		if err == nil {
			break
		}
		if errors.Is(err, vault.ErrExists) && try == 0 {
			// A path the atrium's version needs is taken here.
			if err := e.clearPaths(p, id, remote); err != nil {
				return err
			}
			continue
		}
		if errors.Is(err, vault.ErrConflict) {
			return held("changed on this device while syncing; trying again")
		}
		return groupErr(err, "cannot write the atrium's version here")
	}
	e.refresh(id)

	var text []byte
	if remote.Note != nil {
		t, ver, err := e.v.ReadNote(id)
		if err != nil {
			return groupErr(err, "cannot read the note just written")
		}
		if ver != remote.Note.Version {
			return held("changed on this device while syncing; trying again")
		}
		text = t
	}
	return e.saveBase(id, remote, text)
}

// download fetches one file of the atrium's group into this vault's staging
// area, where vault.Apply takes it from. The vault checks its hash.
func (e *Engine) download(ctx context.Context, id string, index int, version string) error {
	rc, err := e.c.download(ctx, id, index, version)
	if err != nil {
		return groupErr(err, "downloading from the atrium failed")
	}
	defer rc.Close()
	if err := e.v.StageBlob(version, rc, syncsrv.MaxBlob); err != nil {
		if errors.Is(err, vault.ErrHashMismatch) || errors.Is(err, vault.ErrTooLarge) {
			return held(fmt.Sprintf("the download was damaged (%v); trying again", err))
		}
		return err // this disk, or the connection dropped mid-file
	}
	return nil
}

// clearPaths moves never-synced local groups out of the paths remote needs.
// A synced group in the way is left alone: the atrium moved it too, and the
// pull that moves it comes in this pass or the next.
func (e *Engine) clearPaths(p *pass, id string, remote vault.Group) error {
	if p.paths == nil {
		gs, err := e.v.List()
		if err != nil {
			return err
		}
		p.paths = map[string]string{}
		for _, g := range gs {
			for _, f := range allFiles(g) {
				p.paths[f.Path] = g.ID
			}
		}
	}
	for _, f := range allFiles(remote) {
		other, ok := p.paths[f.Path]
		if !ok || other == id {
			continue
		}
		b, err := e.rec.base(other)
		if err != nil {
			return err
		}
		if b != nil {
			return held(fmt.Sprintf("%s is still taken here by another note; waiting for it to move", f.Path))
		}
		g, err := e.v.Get(other)
		if err != nil {
			return groupErr(err, "cannot read the note in the way")
		}
		taken := map[string]bool{}
		for _, f := range allFiles(remote) {
			taken[f.Path] = true
		}
		if err := e.renameLocal(p, g, taken); err != nil {
			return err
		}
	}
	return nil
}

// renameLocal renames a never-synced group to "Name 2.md" (every file the
// same number), the first number whose names are free here and not in busy
// (paths the atrium uses). It goes through vault.Apply, so it never
// overwrites anything.
func (e *Engine) renameLocal(p *pass, g vault.Group, busy map[string]bool) error {
	for n := 2; n < 1000; n++ {
		want := g
		want.Files = slices.Clone(g.Files)
		if g.Note != nil {
			nf := *g.Note
			want.Note = &nf
		}
		free := true
		for _, f := range allFiles(want) {
			f.Path = numbered(f.Path, n)
			free = free && !busy[f.Path] && !exists(e.v.Root(), f.Path)
		}
		if !free {
			continue
		}
		saved, err := e.v.Apply(g.ID, g.Rev, want)
		if errors.Is(err, vault.ErrExists) {
			continue
		}
		if err != nil {
			return groupErr(err, "renaming a note whose name is taken failed")
		}
		e.logf("sync: renamed %s to %s: the atrium has another note with that name", firstPath(g), firstPath(saved))
		if p.paths != nil {
			for _, f := range allFiles(g) {
				delete(p.paths, f.Path)
			}
			for _, f := range allFiles(saved) {
				p.paths[f.Path] = g.ID
			}
		}
		e.refresh(g.ID)
		return nil
	}
	return held("no free name was found for a note whose name is taken")
}

// atriumPaths returns the paths of every group this device has synced: what
// it knows of the atrium's names.
func (e *Engine) atriumPaths() (map[string]bool, error) {
	ids, err := e.rec.baseIDs()
	if err != nil {
		return nil, err
	}
	out := map[string]bool{}
	for _, id := range ids {
		b, err := e.rec.base(id)
		if err != nil {
			return nil, err
		}
		for _, f := range allFiles(b.Group(id)) {
			out[f.Path] = true
		}
	}
	return out, nil
}

func (e *Engine) recordBase(id string, r vault.Group) error {
	var text []byte
	if r.Note != nil {
		t, ver, err := e.v.ReadNote(id)
		if err != nil {
			return groupErr(err, "cannot read the note")
		}
		if ver != r.Note.Version {
			return held("changed on disk; waiting for the scan to catch up")
		}
		text = t
	}
	return e.saveBase(id, r, text)
}

func (e *Engine) saveBase(id string, g vault.Group, text []byte) error {
	return e.rec.saveBase(id, Base{Rev: g.Rev, Note: g.Note, Files: g.Files, History: g.History, Text: text})
}

func (e *Engine) refresh(id string) {
	if e.st == nil {
		return
	}
	if err := e.st.Refresh(e.v, id); err != nil {
		e.logf("sync: index %s: %v", id, err) // the files are right; a restart reindexes
	}
}

// ---- small helpers ----

func fromWire(g syncsrv.Group) vault.Group {
	files := g.Files
	if files == nil {
		files = []vault.File{}
	}
	return vault.Group{ID: g.ID, Rev: g.Rev, Note: g.Note, Files: files, History: g.History}
}

// allFiles returns pointers to the group's files, note first.
func allFiles(g vault.Group) []*vault.File {
	var out []*vault.File
	if g.Note != nil {
		out = append(out, g.Note)
	}
	for i := range g.Files {
		out = append(out, &g.Files[i])
	}
	return out
}

func firstPath(g vault.Group) string {
	if fs := allFiles(g); len(fs) > 0 {
		return fs[0].Path
	}
	return ""
}

// numbered turns "a/Name.md" into "a/Name 2.md".
func numbered(p string, n int) string {
	ext := path.Ext(p)
	return fmt.Sprintf("%s %d%s", strings.TrimSuffix(p, ext), n, ext)
}

func exists(root, rel string) bool {
	_, err := os.Lstat(filepath.Join(root, filepath.FromSlash(rel)))
	return err == nil
}
