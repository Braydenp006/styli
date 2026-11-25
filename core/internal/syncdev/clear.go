package syncdev

import (
	"context"
	"errors"
	"fmt"

	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

// Why Clear refused. Nothing was removed in any of these cases.
var (
	ErrUnsynced      = errors.New("this note has changes the atrium doesn't have yet")
	ErrAtriumDiffers = errors.New("the atrium doesn't hold this exact version yet")
	ErrUnreachable   = errors.New("the atrium couldn't be reached to confirm it holds this version")
)

// Clear removes group id from this device only: its copy goes to this
// device's trash, and the note stays on the atrium. It goes ahead only when
// the copy here is exactly what this device and the atrium last agreed on (no
// unsynced change, none waiting to be scanned) and the atrium confirms, now,
// that it holds that same state. See "safe clearing" in DESIGN.md.
//
// Order, for crashes: the base is marked Cleared first, then the copy moves.
// A crash in between leaves a live copy with a cleared base, which the next
// pass treats as a copy that came back (cleared).
func (e *Engine) Clear(ctx context.Context, id string) error {
	e.passMu.Lock()
	defer e.passMu.Unlock()
	local, err := e.v.Verify(id)
	if errors.Is(err, vault.ErrConflict) {
		return ErrUnsynced // edited outside the app, not scanned yet
	}
	if err != nil {
		return err
	}
	base, err := e.rec.base(id)
	if err != nil {
		return err
	}
	if base == nil || base.Cleared || base.Deleted != 0 || base.Parked != nil || !vault.SameState(local, base.Group(id)) {
		return ErrUnsynced
	}
	// The same protocol rule as a pass, before anything is asked of the atrium.
	h, err := e.c.hello(ctx)
	if err != nil {
		return fmt.Errorf("%w: %w", ErrUnreachable, err)
	}
	if err := checkProtocol(h.Protocol); err != nil {
		return err
	}
	r, err := e.c.group(ctx, id)
	if err != nil {
		var ae *apiError
		if errors.As(err, &ae) && ae.Status < 500 {
			return ErrAtriumDiffers // changed there and not scanned yet, or gone
		}
		return fmt.Errorf("%w: %w", ErrUnreachable, err)
	}
	if r.Deleted || !vault.SameState(fromWire(r), local) {
		return ErrAtriumDiffers
	}

	nb := *base
	nb.Cleared = true
	if err := e.rec.saveBase(id, nb); err != nil {
		return err
	}
	if err := e.v.TrashIfVerified(id, local.Rev); err != nil {
		// Changed here in between: the copy stays, so its base must not say
		// cleared. If even that fails, the next pass checks it again anyway.
		if uerr := e.rec.saveBase(id, *base); uerr != nil {
			return errors.Join(err, uerr)
		}
		if errors.Is(err, vault.ErrConflict) {
			return ErrUnsynced
		}
		return err
	}
	e.unindex(id)
	e.logf("sync: %s removed from this device only; it stays on the atrium", firstPath(local))
	return nil
}

// cleared handles a group cleared from this device whose copy is here again:
// restored from this device's trash, or a crash between marking the base and
// moving the copy. Nothing about it synced while it was cleared, so the atrium
// is asked what it holds now, and the group then syncs as usual from the base
// it was cleared at. The mark is lifted only once that has succeeded, so an
// interrupted pass asks again.
func (e *Engine) cleared(ctx context.Context, p *pass, id string, base *Base, local vault.Group) error {
	nb := *base
	nb.Cleared = false
	var remote *syncsrv.Group
	fresh, err := e.c.group(ctx, id)
	if err == nil {
		remote = &fresh
	} else {
		var ae *apiError
		if !errors.As(err, &ae) || ae.Status >= 500 {
			return err
		}
		switch ae.Code {
		case "changed":
			return held("the atrium is catching up on an edit made there; trying again soon")
		case "not_found":
			if p.restored {
				// The restored atrium's backup is older than this copy: send it as new.
				e.logf("sync: %s is not on the restored atrium; sending it again", firstPath(local))
				if err := e.rec.removeBase(id); err != nil {
					return err
				}
				return e.push(ctx, p, id, local, nil, vault.Group{})
			}
			if vault.SameState(local, base.Group(id)) {
				// Deleted everywhere and emptied from the atrium's trash while
				// cleared here: the copy goes back to this device's trash.
				if _, err := e.v.TrashIf(id, local.Rev); err != nil {
					return groupErr(err, "moving a note deleted everywhere to the trash failed")
				}
				e.logf("sync: %s was deleted everywhere while removed from this device; moved to the trash here", firstPath(local))
				e.unindex(id)
				nb.Deleted, nb.Parked, nb.TrashRev = forgottenDelete, nil, e.trashRev(id)
				return e.rec.saveBase(id, nb)
			}
			// Changed here: the push finds it gone and keeps it as a candidate.
		default:
			return groupErr(err, "asking the atrium about a note removed from this device failed")
		}
	}
	if err := e.decide(ctx, p, id, &nb, local, true, remote); err != nil {
		return err
	}
	// Steps that write a new base already lifted the mark; the rest did not
	// need to write one.
	b, err := e.rec.base(id)
	if err != nil || b == nil || !b.Cleared {
		return err
	}
	b.Cleared = false
	return e.rec.saveBase(id, *b)
}
