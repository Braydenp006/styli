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

	// Merge combines a group changed on both sides (mergeGroup). If nil, such a
	// group is left unsynced and listed in the status; nothing is lost.
	Merge MergeFunc

	// DeviceName is how this device is named in the file it keeps when an
	// attached file changed on both sides ("Photo (Tablet).png").
	DeviceName string

	mu     sync.Mutex
	status Status

	passMu sync.Mutex // one sync pass or Clear at a time
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
	e := &Engine{v: v, st: st, c: newClient(tc, addr), rec: newRecords(v.Root()), logf: logf, status: Status{Atrium: addr, Waiting: []Waiting{}}}
	e.Merge = e.mergeGroup
	return e
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
	waiting  map[string]string // group ID -> why it is waiting
	paths    map[string]string // local path -> group ID; built when a name clash needs it
	merges   map[string]int    // group ID -> merge rounds this pass (see maxMerges)
	restored bool              // the atrium was restored from a backup (a new epoch)
}

// Sync runs one pass. It returns an error only when the pass could not reach
// the end (the atrium is unreachable, a disk error); groups that cannot sync
// yet are in the status instead.
func (e *Engine) Sync(ctx context.Context) error {
	e.passMu.Lock()
	defer e.passMu.Unlock()
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
	// means the cursor can't be trusted, and a cursor below the atrium's
	// forgotten number may have missed deletes. Read every group from the start.
	restored := cur.Epoch != h.Epoch || cur.Since > h.Seq
	full := restored || cur.Since < h.Forgotten
	p.restored = restored
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
		if err := e.gone(p, locals, seen, restored); err != nil {
			return err
		}
		if locals, err = e.v.List(); err != nil {
			return err
		}
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
		if b != nil && b.Deleted == 0 && !b.Cleared && vault.SameState(l, b.Group(l.ID)) {
			continue // a base marked deleted with a live copy: restored here
		}
		if err := e.group(ctx, p, l.ID, nil); err != nil {
			var sk *skip
			if !errors.As(err, &sk) {
				return err
			}
			p.waiting[l.ID] = sk.reason
		}
	}

	// 3. Groups gone from this vault since they synced (trashed here, or
	// emptied from the trash): deletes to send.
	live := map[string]bool{}
	for _, l := range locals {
		live[l.ID] = true
	}
	ids, err := e.rec.baseIDs()
	if err != nil {
		return err
	}
	for _, id := range ids {
		if seen[id] || live[id] {
			continue
		}
		b, err := e.rec.base(id)
		if err != nil {
			return err
		}
		if b == nil || b.Deleted != 0 {
			continue
		}
		if err := e.group(ctx, p, id, nil); err != nil {
			var sk *skip
			if !errors.As(err, &sk) {
				return err
			}
			p.waiting[id] = sk.reason
		}
	}
	return nil
}

// gone handles, in a full check, live groups this device has synced that the
// atrium's complete list does not have at all. After a restored atrium (a new
// epoch) they may be newer than its backup, so their bases are dropped and
// they are pushed again as new. Otherwise they were deleted while this device
// was away and the delete was forgotten: an unchanged one goes to the trash
// here; a changed one is left for step 2, whose push finds it gone and keeps
// it on the atrium as a recovery candidate.
func (e *Engine) gone(p *pass, locals []vault.Group, seen map[string]bool, restored bool) error {
	for _, l := range locals {
		if seen[l.ID] {
			continue
		}
		b, err := e.rec.base(l.ID)
		if err != nil {
			return err
		}
		if b == nil || b.Cleared {
			continue // a cleared copy that came back is checked in step 2
		}
		switch {
		case restored:
			e.logf("sync: %s is not on the restored atrium; sending it again", firstPath(l))
			if err := e.rec.removeBase(l.ID); err != nil {
				return err
			}
		case b.Deleted == 0 && (vault.SameState(l, b.Group(l.ID)) || b.isParked(l)):
			if _, err := e.v.TrashIf(l.ID, l.Rev); err != nil {
				p.waiting[l.ID] = groupErr(err, "moving a note deleted everywhere to the trash failed").Error()
				continue
			}
			e.logf("sync: %s was deleted everywhere while this device was away; moved to the trash here", firstPath(l))
			e.unindex(l.ID)
			b.Deleted, b.Parked, b.TrashRev = forgottenDelete, nil, e.trashRev(l.ID)
			if err := e.rec.saveBase(l.ID, *b); err != nil {
				return err
			}
		}
	}
	return nil
}

// forgottenDelete marks a base whose delete the atrium has emptied from its
// trash: no rev to restore against, so a restore here is sent as new.
const forgottenDelete = ^uint64(0)

// unknownRev is the base sent with a delete when this device doesn't know
// which version it deleted: the atrium then keeps its own as a candidate.
const unknownRev = ^uint64(0)

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

	if base != nil && base.Cleared {
		if hasLocal {
			return e.cleared(ctx, p, id, base, local)
		}
		if t, err := e.v.Trashed(id); err == nil && !t.LocalOnly {
			// Trashed by a person after a crash in the middle of clearing it:
			// a delete everywhere, which must still reach the atrium.
			nb := *base
			nb.Cleared = false
			return e.decide(ctx, p, id, &nb, local, false, remote)
		}
		return nil // cleared from this device: it stays on the atrium only
	}
	return e.decide(ctx, p, id, base, local, hasLocal, remote)
}

// decide is the decision table for one group (see group).
func (e *Engine) decide(ctx context.Context, p *pass, id string, base *Base, local vault.Group, hasLocal bool, remote *syncsrv.Group) error {
	var r vault.Group
	switch {
	case remote != nil:
		r = fromWire(*remote)
	case base != nil:
		r = base.Group(id)
	}

	switch {
	case remote != nil && remote.Deleted:
		return e.deletedThere(ctx, p, id, base, local, hasLocal, r)
	case !hasLocal && base != nil && base.Deleted == 0:
		// Synced, then trashed (or emptied from the trash) here.
		return e.pushDelete(ctx, p, id, base, base.Rev)
	case !hasLocal && base != nil && remote != nil:
		// Restored on the atrium after a delete this device had applied,
		// unless someone here restored and trashed it again since: then this
		// is a new delete, of a version changed there (which the atrium keeps
		// as a candidate).
		if t := e.trashRev(id); t != 0 && t != base.TrashRev {
			return e.pushDelete(ctx, p, id, base, base.Rev)
		}
		return e.pullRestored(ctx, p, id, r)
	case !hasLocal && remote == nil:
		return nil
	case !hasLocal:
		if t, err := e.v.Trashed(id); err == nil {
			// In this device's trash with no base: synced, its base lost (a
			// crash, a lost reply), then trashed: a delete to send. If its
			// version differs from the atrium's, which one is newer is unknown,
			// so the atrium keeps its own as a candidate.
			rev := r.Rev
			if !vault.SameFiles(t.Group, r) {
				rev = unknownRev
			}
			return e.pushDelete(ctx, p, id, &Base{Rev: r.Rev, Note: r.Note, Files: r.Files, History: r.History, Conflict: r.Conflict}, rev)
		}
		return e.pull(ctx, p, id, nil, r)
	case base != nil && base.Deleted != 0 && remote == nil:
		// Restored here after a delete both agreed on: restore it everywhere.
		// Everything is uploaded, since the atrium's trash may hold another version.
		return e.push(ctx, p, id, local, base, vault.Group{Rev: base.Deleted})
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
	parked := base.isParked(local)
	lChanged, rChanged := !vault.SameState(local, b) && !parked, !vault.SameState(r, b)
	switch {
	case parked && !rChanged:
		// Parked for a pull that never came (the atrium has it where it was
		// after all): put it back.
		return e.unpark(p, local, base)
	case !lChanged && !rChanged:
		// A restored atrium renumbered it, or a park was written down but the
		// move never happened (a crash in between), or both sides restored it
		// after a delete: tidy the base.
		if r.Rev != base.Rev || base.Parked != nil || base.Deleted != 0 {
			base.Rev, base.Parked, base.Deleted = r.Rev, nil, 0
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

// deletedThere handles a deletion from the atrium's feed (r: the group as it
// was when deleted, r.Rev the delete's number). A copy here that is what both
// last agreed on goes to the trash; a changed copy is first kept on the atrium
// as a recovery candidate.
func (e *Engine) deletedThere(ctx context.Context, p *pass, id string, base *Base, local vault.Group, hasLocal bool, r vault.Group) error {
	if !hasLocal {
		// Trashed here too, or never here.
		if base == nil || base.Deleted == r.Rev {
			return nil
		}
		// Deleted here too, before this device knew of the delete there.
		// TrashRev 0 marks the trash entry as this person's own delete, which
		// no one restoring elsewhere has seen: such a restore waits here.
		base.Deleted, base.Parked, base.TrashRev = r.Rev, nil, 0
		return e.rec.saveBase(id, *base)
	}
	if base != nil && base.Deleted == r.Rev {
		// Restored here after this very delete: restore it everywhere.
		return e.push(ctx, p, id, local, base, vault.Group{Rev: r.Rev})
	}
	unchanged := vault.SameState(local, r) || (base != nil && (vault.SameState(local, base.Group(id)) || base.isParked(local)))
	if !unchanged {
		return e.keepAsCandidate(ctx, p, id, local, base)
	}
	if _, err := e.v.TrashIf(id, local.Rev); err != nil {
		return groupErr(err, "moving a note deleted everywhere to the trash failed")
	}
	e.logf("sync: %s was deleted everywhere; moved to the trash here", firstPath(local))
	e.unindex(id)
	if p.paths != nil {
		for _, f := range allFiles(local) {
			delete(p.paths, f.Path)
		}
	}
	nb := Base{Rev: r.Rev, Note: r.Note, Files: r.Files, History: r.History, Conflict: r.Conflict}
	if base != nil {
		nb = *base
		nb.Parked = nil
	}
	nb.Deleted, nb.TrashRev = r.Rev, e.trashRev(id)
	return e.rec.saveBase(id, nb)
}

// pushDelete sends a delete made here, of the version the atrium numbered
// rev. The delete stands; if the atrium has a newer version, it keeps that as
// a recovery candidate first.
func (e *Engine) pushDelete(ctx context.Context, p *pass, id string, base *Base, rev uint64) error {
	got, err := e.c.deleteGroup(ctx, id, rev)
	if err == nil {
		base.Deleted, base.Parked, base.TrashRev = got.Rev, nil, e.trashRev(id)
		return e.rec.saveBase(id, *base)
	}
	var ae *apiError
	if !errors.As(err, &ae) || ae.Status >= 500 {
		return err
	}
	switch ae.Code {
	case "not_found":
		// Already emptied from the atrium's trash.
		base.Deleted, base.Parked, base.TrashRev = forgottenDelete, nil, e.trashRev(id)
		return e.rec.saveBase(id, *base)
	case "changed":
		return held("the atrium is catching up on an edit made there; trying again soon")
	default:
		return groupErr(err, "the atrium refused the delete")
	}
}

// keepAsCandidate handles a copy changed here of a group deleted everywhere:
// the delete stands, and the atrium keeps this copy as a recovery candidate
// for someone to restore or delete. Only once the atrium has confirmed it does
// this device let the delete stand too (its copy goes to the trash here).
func (e *Engine) keepAsCandidate(ctx context.Context, p *pass, id string, local vault.Group, base *Base) error {
	if _, err := e.upload(ctx, id, local, vault.Group{}); err != nil {
		return err
	}
	deletedRev, err := e.c.recovery(ctx, id, local)
	if err != nil {
		var ae *apiError
		if errors.As(err, &ae) && ae.Code == "conflict" {
			return held("restored on the atrium meanwhile; it syncs on the next pass")
		}
		return groupErr(err, "sending the copy changed after the delete failed")
	}
	if _, err := e.v.TrashIf(id, local.Rev); err != nil {
		// Changed again here meanwhile: the next pass sends that version too.
		return groupErr(err, "moving a note deleted everywhere to the trash failed")
	}
	e.logf("sync: %s was deleted everywhere; the copy changed here is kept on the atrium for review", firstPath(local))
	e.unindex(id)
	if p.paths != nil {
		for _, f := range allFiles(local) {
			delete(p.paths, f.Path)
		}
	}
	nb := Base{Note: local.Note, Files: local.Files, History: local.History, Conflict: local.Conflict}
	if base != nil {
		nb = *base
		nb.Parked = nil
	}
	nb.Deleted, nb.TrashRev = deletedRev, e.trashRev(id)
	if deletedRev == 0 {
		nb.Deleted = forgottenDelete
	}
	return e.rec.saveBase(id, nb)
}

// pullRestored pulls a group the atrium restored after a delete this device
// applied. If it is still in this device's trash, the vault restores it there
// first (Apply with the trash's delete number), so nothing is downloaded twice.
func (e *Engine) pullRestored(ctx context.Context, p *pass, id string, r vault.Group) error {
	t, err := e.v.Trashed(id)
	if err != nil {
		return e.pull(ctx, p, id, nil, r) // emptied from the trash here: a fresh pull
	}
	have := t.Group
	have.Rev = t.DeletedRev
	return e.pull(ctx, p, id, &have, r)
}

// trashRev is this device's number for its trash entry of id, or 0.
func (e *Engine) trashRev(id string) uint64 {
	t, err := e.v.Trashed(id)
	if err != nil {
		return 0
	}
	return t.DeletedRev
}

func (e *Engine) unindex(id string) {
	if e.st == nil {
		return
	}
	if err := e.st.Remove(id); err != nil {
		e.logf("sync: index %s: %v", id, err) // a restart reindexes
	}
}

func (e *Engine) merge(ctx context.Context, p *pass, id string, base *Base, local, remote vault.Group) error {
	if e.Merge == nil {
		return held("changed here and on the atrium; waiting for merging, which isn't built yet")
	}
	if p.merges == nil {
		p.merges = map[string]int{}
	}
	if p.merges[id]++; p.merges[id] > maxMerges {
		return held("the atrium keeps changing while merging; trying again soon")
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
		case ae.Code == "deleted":
			return e.keepAsCandidate(ctx, p, id, local, base)
		case ae.Code == "not_found" && base != nil && base.Deleted != 0 && remote.Rev == base.Deleted:
			// A restore of a delete the atrium has since emptied from its
			// trash: send it as new (everything is uploaded already).
			remote = vault.Group{}
			continue
		case ae.Code == "not_found" && base != nil:
			// Deleted while this device was away and emptied since, but changed here.
			return e.keepAsCandidate(ctx, p, id, local, base)
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
	} else if g, ok, err := e.adoptable(p, remote); err != nil {
		return err
	} else if ok {
		return e.adopt(p, g, id, remote) // before downloading what is already here
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

// clearPaths frees the paths remote needs here. A never-synced group in the
// way is renamed for good ("Name 2.md"). A synced one must have moved on the
// atrium (the atrium never has two groups on one path), so if it has no
// local changes it is parked at a spare name until its own pull, later in
// this pass, puts it where the atrium has it. That is what lets two notes
// swap names. A synced group with local changes waits for its merge.
func (e *Engine) clearPaths(p *pass, id string, remote vault.Group) error {
	paths, err := e.pathMap(p)
	if err != nil {
		return err
	}
	for _, f := range allFiles(remote) {
		other, ok := paths[f.Path]
		if !ok || other == id {
			continue
		}
		b, err := e.rec.base(other)
		if err != nil {
			return err
		}
		g, err := e.v.Get(other)
		if err != nil {
			return groupErr(err, "cannot read the note in the way")
		}
		taken := map[string]bool{}
		for _, f := range allFiles(remote) {
			taken[f.Path] = true
		}
		switch {
		case b == nil:
			err = e.renameLocal(p, g, taken)
		case vault.SameState(g, b.Group(other)) || b.isParked(g):
			err = e.park(p, g, b, taken)
		default:
			err = held(fmt.Sprintf("%s is still taken here by a note with changes on this device; waiting for it to sync", f.Path))
		}
		if err != nil {
			return err
		}
	}
	return nil
}

// pathMap returns the pass's map of local paths to group IDs, building it the
// first time a pass needs it.
func (e *Engine) pathMap(p *pass) (map[string]string, error) {
	if p.paths == nil {
		gs, err := e.v.List()
		if err != nil {
			return nil, err
		}
		p.paths = map[string]string{}
		for _, g := range gs {
			for _, f := range allFiles(g) {
				p.paths[f.Path] = g.ID
			}
		}
	}
	return p.paths, nil
}

// adoptable returns the never-synced local group that holds exactly remote's
// files at remote's paths, if there is one: a copy of the same note made
// before this device joined (two copies of one vault, for example).
func (e *Engine) adoptable(p *pass, remote vault.Group) (vault.Group, bool, error) {
	paths, err := e.pathMap(p)
	if err != nil {
		return vault.Group{}, false, err
	}
	other, ok := paths[firstPath(remote)]
	if !ok {
		return vault.Group{}, false, nil
	}
	if b, err := e.rec.base(other); err != nil || b != nil {
		return vault.Group{}, false, err // a synced group has a shared ID: never taken over
	}
	g, err := e.v.Get(other)
	if err != nil {
		return vault.Group{}, false, nil // gone since the map was built; the pull decides
	}
	return g, vault.SameFiles(g, remote), nil
}

// adopt gives the never-synced local group g the atrium's ID id, since it
// holds the same files: no duplicate, and nothing to download. Its own ID was
// never shared, so nothing else refers to it.
func (e *Engine) adopt(p *pass, g vault.Group, id string, remote vault.Group) error {
	saved, err := e.v.Adopt(g.ID, id, remote)
	if err != nil {
		return groupErr(err, "taking the atrium's ID for the same note here failed")
	}
	e.logf("sync: %s is already here, so it took the atrium's ID %s (was %s)", firstPath(saved), id, g.ID)
	e.unindex(g.ID)
	e.moved(p, g, saved)
	return e.recordBase(id, remote)
}

// park moves a synced group to "Name (moving).md" (every file the same), the
// first such name free here and not in busy. The base records the parked
// paths before anything moves, so a crash in between can't be mistaken for
// a rename made on this device.
func (e *Engine) park(p *pass, g vault.Group, b *Base, busy map[string]bool) error {
	for n := 1; n < 1000; n++ {
		want, free := e.renamed(g, busy, func(path string) string { return moving(path, n) })
		if !free {
			continue
		}
		b.Parked = &Parked{Note: want.Note, Files: want.Files}
		if err := e.rec.saveBase(g.ID, *b); err != nil {
			return err
		}
		saved, err := e.v.Apply(g.ID, g.Rev, want)
		if errors.Is(err, vault.ErrExists) {
			continue
		}
		if err != nil {
			return groupErr(err, "moving a note aside failed")
		}
		e.moved(p, g, saved)
		return nil
	}
	return held("no free name was found to move a note aside")
}

// unpark moves a parked group back to its base paths.
func (e *Engine) unpark(p *pass, local vault.Group, b *Base) error {
	saved, err := e.v.Apply(local.ID, local.Rev, b.Group(local.ID))
	if err != nil {
		return groupErr(err, "moving a note back failed")
	}
	e.moved(p, local, saved)
	b.Parked = nil
	return e.rec.saveBase(local.ID, *b)
}

// renameLocal renames a never-synced group to "Name 2.md" (every file the
// same number), the first number whose names are free here and not in busy
// (paths the atrium uses). It goes through vault.Apply, so it never
// overwrites anything.
func (e *Engine) renameLocal(p *pass, g vault.Group, busy map[string]bool) error {
	for n := 2; n < 1000; n++ {
		want, free := e.renamed(g, busy, func(path string) string { return numbered(path, n) })
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
		e.moved(p, g, saved)
		return nil
	}
	return held("no free name was found for a note whose name is taken")
}

// renamed returns g with every path passed through name, and whether all
// the new paths are free here and not in busy.
func (e *Engine) renamed(g vault.Group, busy map[string]bool, name func(string) string) (vault.Group, bool) {
	want := g
	want.Files = slices.Clone(g.Files)
	if g.Note != nil {
		nf := *g.Note
		want.Note = &nf
	}
	free := true
	for _, f := range allFiles(want) {
		f.Path = name(f.Path)
		free = free && !busy[f.Path] && !exists(e.v.Root(), f.Path)
	}
	return want, free
}

// moved updates the pass's path map and the search index after a local move.
func (e *Engine) moved(p *pass, from, to vault.Group) {
	if p.paths != nil {
		for _, f := range allFiles(from) {
			delete(p.paths, f.Path)
		}
		for _, f := range allFiles(to) {
			p.paths[f.Path] = to.ID
		}
	}
	e.refresh(to.ID)
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
	return e.rec.saveBase(id, Base{Rev: g.Rev, Note: g.Note, Files: g.Files, History: g.History, Conflict: g.Conflict, Text: text})
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
	return vault.Group{ID: g.ID, Rev: g.Rev, Note: g.Note, Files: files, History: g.History, Conflict: g.Conflict, Deleted: g.Deleted}
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

// moving turns "a/Name.md" into "a/Name (moving).md", or "(moving 2)" and on.
func moving(p string, n int) string {
	ext := path.Ext(p)
	if n == 1 {
		return fmt.Sprintf("%s (moving)%s", strings.TrimSuffix(p, ext), ext)
	}
	return fmt.Sprintf("%s (moving %d)%s", strings.TrimSuffix(p, ext), n, ext)
}

func exists(root, rel string) bool {
	_, err := os.Lstat(filepath.Join(root, filepath.FromSlash(rel)))
	return err == nil
}
