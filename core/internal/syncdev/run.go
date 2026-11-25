package syncdev

import (
	"context"
	"time"
)

// How long Run waits before trying again: after a pass that could not finish
// (the atrium is unreachable), growing up to MaxBackoff; after a pass that
// left groups waiting, Retry.
var (
	MinBackoff = 2 * time.Second
	MaxBackoff = 5 * time.Minute
	Retry      = time.Minute
)

// Run syncs until ctx ends: a pass now, then another whenever the atrium
// reports a change (a long poll), this vault changes, or a retry is due.
func (e *Engine) Run(ctx context.Context) {
	backoff := time.Duration(0)
	for {
		changed := e.v.Changed() // taken first, so an edit during the pass starts another
		err := e.Sync(ctx)
		if ctx.Err() != nil {
			return
		}
		switch {
		case err != nil:
			backoff = min(max(2*backoff, MinBackoff), MaxBackoff)
			e.logf("sync: %v; trying again in %s", err, backoff)
			if !sleep(ctx, backoff, nil) {
				return
			}
		case e.holding():
			backoff = 0
			if !sleep(ctx, Retry, changed) {
				return
			}
		default:
			backoff = 0
			if !e.waitForChange(ctx, changed) {
				return
			}
		}
	}
}

// holding reports whether the last pass left groups waiting.
func (e *Engine) holding() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return len(e.status.Waiting) > 0
}

// waitForChange returns once the atrium has a change past the cursor (or the
// long poll ends), or this vault changes. False means ctx ended.
func (e *Engine) waitForChange(ctx context.Context, local <-chan struct{}) bool {
	cur, err := e.rec.cursor()
	if err != nil {
		return sleep(ctx, Retry, local)
	}
	pollCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		e.c.changes(pollCtx, cur.Since, int(30))
	}()
	select {
	case <-ctx.Done():
		return false
	case <-local:
	case <-done:
	}
	return true
}

// sleep waits d, or until wake is closed. False means ctx ended.
func sleep(ctx context.Context, d time.Duration, wake <-chan struct{}) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
	case <-wake:
	}
	return true
}
