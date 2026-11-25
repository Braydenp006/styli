// Package watch keeps the vault records and the search index in step with
// changes made outside the app.
//
// File events (fsnotify) are only a nudge: any event starts a short timer, and
// when it fires we run vault.Rescan, which looks at the folder as it is now.
// So lost, merged or oddly ordered events cannot cause a wrong result, and a
// timer runs Rescan anyway as a safety net.
package watch

import (
	"context"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/fsnotify/fsnotify"

	"styli/core/internal/store"
	"styli/core/internal/vault"
)

var newWatcher = fsnotify.NewWatcher // swapped in tests

// Options tune Run. Zero values use the defaults.
type Options struct {
	Debounce time.Duration                 // quiet time after an event before scanning (300 ms)
	Interval time.Duration                 // safety-net scan period (60 s)
	OnChange func([]vault.Change)          // called with every non-empty scan result
	Ready    func()                        // called once the first scan is done
	Logf     func(format string, a ...any) // defaults to log.Printf
}

// Run scans once, rebuilds the index from the vault, then keeps both current
// until ctx ends. If file events are unavailable (OS watch limit, odd
// filesystem) it logs that and relies on the periodic scan.
func Run(ctx context.Context, v *vault.Vault, s *store.Store, o Options) error {
	if o.Debounce == 0 {
		o.Debounce = 300 * time.Millisecond
	}
	if o.Interval == 0 {
		o.Interval = 60 * time.Second
	}
	if o.Logf == nil {
		o.Logf = log.Printf
	}

	var events chan fsnotify.Event // a nil channel never fires in select
	var errs chan error
	w, err := newWatcher()
	if err != nil {
		o.Logf("watch: file events unavailable, scanning every %s: %v", o.Interval, err)
	} else {
		defer w.Close()
		events, errs = w.Events, w.Errors
		addTree(w, v.Root(), o.Logf) // before the first scan, so no change slips between them
	}

	changes, pending, err := v.Rescan()
	if err != nil {
		return err
	}
	if err := s.Reindex(v); err != nil {
		return err
	}
	if len(changes) > 0 && o.OnChange != nil {
		o.OnChange(changes)
	}
	if o.Ready != nil {
		o.Ready()
	}

	timer := time.NewTimer(time.Hour)
	timer.Stop()
	retryAfterSettle := func() { timer.Reset(vault.Settle + 100*time.Millisecond) }
	if pending {
		retryAfterSettle()
	}
	scan := func() {
		changes, pending, err := v.Rescan()
		if err != nil {
			o.Logf("watch: scan: %v", err)
			return
		}
		for _, c := range changes {
			if c.Kind == vault.WentMissing {
				continue // nothing to index; the dashboard lists it
			}
			if err := s.Refresh(v, c.ID); err != nil {
				o.Logf("watch: index %s: %v", c.ID, err)
			}
		}
		if len(changes) > 0 && o.OnChange != nil {
			o.OnChange(changes)
		}
		if pending {
			retryAfterSettle()
		}
	}

	tick := time.NewTicker(o.Interval)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case ev, ok := <-events:
			if !ok {
				events = nil
				continue
			}
			if ev.Op == fsnotify.Chmod || strings.HasPrefix(filepath.Base(ev.Name), ".") {
				continue
			}
			if ev.Has(fsnotify.Create) { // a new folder needs its own watch
				if fi, err := os.Lstat(ev.Name); err == nil && fi.IsDir() {
					addTree(w, ev.Name, o.Logf)
				}
			}
			timer.Reset(o.Debounce)
		case err, ok := <-errs:
			if !ok {
				errs = nil
				continue
			}
			o.Logf("watch: %v", err) // often a queue overflow: events were lost, so scan
			timer.Reset(o.Debounce)
		case <-timer.C:
			scan()
		case <-tick.C:
			scan()
		}
	}
}

// addTree watches root and every folder below it, except hidden ones (.ids).
// A failure (for example the OS watch limit) is logged once; the periodic
// scan still covers whatever could not be watched.
func addTree(w *fsnotify.Watcher, root string, logf func(string, ...any)) {
	failed := false
	filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil || !d.IsDir() {
			return nil
		}
		if p != root && strings.HasPrefix(d.Name(), ".") {
			return fs.SkipDir
		}
		if err := w.Add(p); err != nil && !failed {
			failed = true
			logf("watch: cannot watch %s (rest falls back to periodic scans): %v", p, err)
		}
		return nil
	})
}
