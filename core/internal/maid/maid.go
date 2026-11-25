// Package maid is the housekeeping job: once a day it prunes version history
// and empties old trash, following the user's settings. It is the only code
// that deletes data without someone clicking, so before deleting it checks
// that the clock is sane (optionally against a time server) and holds back any
// job that would delete an unusually large share of what exists. Held-back
// jobs and clock problems are reported for the atrium dashboard; the user
// decides what happens next, and the maid never retries a hold on its own.
package maid

import (
	"context"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"styli/core/internal/settings"
	"styli/core/internal/vault"
)

const (
	StartDelay     = time.Minute     // first run after startup, once scanning has settled
	Interval       = 24 * time.Hour  // then once a day
	ClockTolerance = 5 * time.Minute // allowed difference from the time server

	// The circuit breaker: a job is held back if it would delete at least the
	// minimum AND more than this percentage of everything there is.
	trashMin    = 5
	versionMin  = 50
	breakerPct  = 25
	ntpTimeout  = 5 * time.Second
	clockWrong  = "wrong"
	clockOK     = "ok"
	clockOff    = "off"
	clockNoAnsw = "unreachable"
)

// JobResult is what one job did.
type JobResult struct {
	Deleted     int    // removed in this run
	WouldDelete int    // what the user's rules asked for
	Total       int    // how many there were
	HeldBack    bool   // the breaker stopped it: approve with RunNow(ctx, true)
	Skipped     string // why the job did not run at all, if it did not
	Err         string // a failure; the other job still ran
}

// Result is one run of the maid.
type Result struct {
	Time      time.Time // the time the maid used for "now"
	Preview   bool      // counted only, deleted nothing
	Forced    bool      // the user approved deleting past the breaker
	TimeCheck string    // "off", "ok", "unreachable" or "wrong"
	// ClockOffset is the time server's clock minus ours, when it answered.
	ClockOffset  time.Duration
	TimeCheckErr string
	History      JobResult
	Trash        JobResult
}

// NeedsAttention is true when the dashboard should show this run: a job was
// held back or failed, or the clock looked wrong.
func (r Result) NeedsAttention() bool {
	return r.TimeCheck == clockWrong ||
		r.History.HeldBack || r.Trash.HeldBack ||
		r.History.Err != "" || r.Trash.Err != ""
}

// Status is what the dashboard reads.
type Status struct {
	Running bool
	Last    *Result // nil until the first run
}

// Maid runs the housekeeping for one vault.
type Maid struct {
	v    *vault.Vault
	s    *settings.Store
	logf func(string, ...any)

	now        func() time.Time
	checkClock func(ctx context.Context, server string) (time.Duration, error)

	runMu sync.Mutex // one run at a time

	mu      sync.Mutex // guards running and last
	running bool
	last    *Result
}

// New makes a maid. logf defaults to log.Printf.
func New(v *vault.Vault, s *settings.Store, logf func(string, ...any)) *Maid {
	if logf == nil {
		logf = log.Printf
	}
	m := &Maid{v: v, s: s, logf: logf, now: time.Now}
	m.checkClock = func(ctx context.Context, server string) (time.Duration, error) {
		return clockOffset(ctx, server, m.now, ntpTimeout)
	}
	return m
}

// Run runs the maid after startDelay and then every interval, until ctx ends.
// Use StartDelay and Interval in the app.
func (m *Maid) Run(ctx context.Context, startDelay, interval time.Duration) {
	t := time.NewTimer(startDelay)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			m.RunNow(ctx, false)
			t.Reset(interval)
		}
	}
}

// RunNow runs both jobs now. force skips the circuit breaker (the user has
// seen what will be deleted and approved it); it never skips a failed clock
// check, because a wrong clock would make every rule delete the wrong things.
func (m *Maid) RunNow(ctx context.Context, force bool) Result {
	return m.run(ctx, force, false)
}

// Preview counts what a run would delete, without deleting anything or
// asking a time server.
func (m *Maid) Preview(ctx context.Context) Result {
	return m.run(ctx, false, true)
}

// Status returns whether a run is going and the last run's result.
func (m *Maid) Status() Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	st := Status{Running: m.running}
	if m.last != nil {
		r := *m.last
		st.Last = &r
	}
	return st
}

func (m *Maid) run(ctx context.Context, force, preview bool) Result {
	m.runMu.Lock()
	defer m.runMu.Unlock()
	m.setRunning(true)
	defer m.setRunning(false)

	r := Result{Time: m.now(), Preview: preview, Forced: force, TimeCheck: clockOff}
	hub := m.s.Hub()

	if !preview && hub.TimeCheck.On {
		off, err := m.checkClock(ctx, hub.TimeCheck.Server)
		switch {
		case err != nil:
			// Offline or blocked: carry on, the circuit breaker still guards.
			r.TimeCheck, r.TimeCheckErr = clockNoAnsw, err.Error()
			m.logf("maid: time check failed (%v); relying on the circuit breaker", err)
		case off > ClockTolerance || off < -ClockTolerance:
			r.TimeCheck, r.ClockOffset = clockWrong, off
			r.History.Skipped = "the clock looks wrong"
			r.Trash.Skipped = r.History.Skipped
			m.logf("maid: this machine's clock is %s off from %s; deleted nothing", off.Round(time.Second), hub.TimeCheck.Server)
			return m.finish(r)
		default:
			r.TimeCheck, r.ClockOffset = clockOK, off
		}
	}

	// Shutting down (or a cancelled check) is never a reason to delete.
	if err := ctx.Err(); err != nil {
		r.History.Skipped = "stopped: " + err.Error()
		r.Trash.Skipped = r.History.Skipped
		return m.finish(r)
	}

	// Make sure the vault prunes with the current settings.
	if err := m.s.ApplyTo(m.v); err != nil {
		r.History.Err = err.Error()
	} else {
		r.History = m.history(r.Time, force, preview)
	}
	r.Trash = m.trash(r.Time, force, preview)
	return m.finish(r)
}

func (m *Maid) history(now time.Time, force, preview bool) JobResult {
	var jr JobResult
	n, total, err := m.v.PruneHistory(now, true)
	jr.WouldDelete, jr.Total = n, total
	if err != nil {
		jr.Err = err.Error()
		m.logf("maid: history: %v", err)
		return jr
	}
	if preview || n == 0 {
		return jr
	}
	if !force && tripped(n, total, versionMin) {
		jr.HeldBack = true
		m.logf("maid: history: held back, would delete %d of %d versions; approve on the dashboard to run anyway", n, total)
		return jr
	}
	jr.Deleted, _, err = m.v.PruneHistory(now, false)
	if err != nil {
		jr.Err = err.Error()
	}
	m.logf("maid: history: deleted %d of %d versions", jr.Deleted, total)
	return jr
}

func (m *Maid) trash(now time.Time, force, preview bool) JobResult {
	var jr JobResult
	keep := m.s.TrashRetention()
	if keep == 0 {
		jr.Skipped = "trash is kept forever"
		return jr
	}
	before := now.Add(-keep)
	ids, total, err := m.v.Purge(before, true)
	jr.WouldDelete, jr.Total = len(ids), total
	if err != nil {
		jr.Err = err.Error()
		m.logf("maid: trash: %v", err)
		return jr
	}
	if preview || len(ids) == 0 {
		return jr
	}
	if !force && tripped(len(ids), total, trashMin) {
		jr.HeldBack = true
		m.logf("maid: trash: held back, would delete %d of %d entries; approve on the dashboard to run anyway", len(ids), total)
		return jr
	}
	done, _, err := m.v.Purge(before, false)
	jr.Deleted = len(done)
	if err != nil {
		jr.Err = err.Error()
	}
	m.logf("maid: trash: deleted %d of %d entries: %s", len(done), total, strings.Join(done, ", "))
	return jr
}

// tripped is the circuit breaker.
func tripped(n, total, min int) bool {
	return n >= min && n*100 > total*breakerPct
}

func (m *Maid) finish(r Result) Result {
	m.mu.Lock()
	m.last = &r
	m.mu.Unlock()
	if r.NeedsAttention() && !r.Preview {
		m.logf("maid: needs attention: %s", summary(r))
	}
	return r
}

func (m *Maid) setRunning(on bool) {
	m.mu.Lock()
	m.running = on
	m.mu.Unlock()
}

func summary(r Result) string {
	var parts []string
	if r.TimeCheck == clockWrong {
		parts = append(parts, fmt.Sprintf("clock is %s off", r.ClockOffset.Round(time.Second)))
	}
	for _, job := range []struct {
		name string
		j    JobResult
	}{{"history", r.History}, {"trash", r.Trash}} {
		name, j := job.name, job.j
		if j.HeldBack {
			parts = append(parts, fmt.Sprintf("%s held back (%d of %d)", name, j.WouldDelete, j.Total))
		}
		if j.Err != "" {
			parts = append(parts, fmt.Sprintf("%s failed: %s", name, j.Err))
		}
	}
	return strings.Join(parts, "; ")
}
