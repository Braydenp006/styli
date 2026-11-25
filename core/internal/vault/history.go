package vault

import (
	"bytes"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Version history keeps older text of a note (the Markdown only; ink has its
// own stroke log and PDFs never change). Each version is a gzip full copy:
//
//	<root>/.ids/history/<id>/<unix milliseconds>.gz
//
// The folder is keyed by the note's ID, so moves and renames do not touch it,
// and it lives in the vault so it travels with backups and atrium handover.
//
// A version is saved whenever the note's text changes. If the newest version
// is younger than the window (30 minutes by default) it is replaced instead of
// added to, so a long editing session leaves one version per window holding
// that window's latest text, and the last state of every session is kept.
const historyDir = "history"

var versionRe = regexp.MustCompile(`^\d{13}$`)

// Version is one saved copy of a note.
type Version struct {
	ID   string
	Time time.Time // when this version's window started
}

// historySeed is the text a note had before its first recorded change.
type historySeed struct {
	content []byte
	at      time.Time
}

// SetHistoryDefault sets whether notes keep history unless they choose otherwise.
func (v *Vault) SetHistoryDefault(on bool) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.historyOn = on
}

// SetHistoryWindow sets how long edits fold into the newest version. Zero
// means every change makes a new version.
func (v *Vault) SetHistoryWindow(d time.Duration) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.historyWindow = d
}

// SetHistory turns history on or off for one note; nil goes back to the
// default. Turning it off keeps the versions already saved.
func (v *Vault) SetHistory(id string, on *bool) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return err
	}
	g.History = on
	return v.save(&g)
}

// HistoryEnabled reports whether new versions are being saved for a note.
func (v *Vault) HistoryEnabled(id string) (bool, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	g, err := v.load(id)
	if err != nil {
		return false, err
	}
	return v.historyFor(g), nil
}

func (v *Vault) historyFor(g Group) bool {
	if g.History != nil {
		return *g.History
	}
	return v.historyOn
}

func (v *Vault) historyPath(id string) string {
	return filepath.Join(v.root, idsDir, historyDir, id)
}

// dropHistory removes the history folder of an ID that was just generated, or
// that a Purge is deleting.
func (v *Vault) dropHistory(id string) { os.RemoveAll(v.historyPath(id)) }

// History lists a note's saved versions, newest first.
func (v *Vault) History(id string) ([]Version, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if _, err := v.load(id); err != nil {
		return nil, err
	}
	vs, err := listVersions(v.historyPath(id))
	for i, j := 0, len(vs)-1; i < j; i, j = i+1, j-1 {
		vs[i], vs[j] = vs[j], vs[i]
	}
	return vs, err
}

// ReadVersion returns the text of one saved version.
func (v *Vault) ReadVersion(id, versionID string) ([]byte, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.readVersion(id, versionID)
}

func (v *Vault) readVersion(id, versionID string) ([]byte, error) {
	if _, err := v.load(id); err != nil {
		return nil, err
	}
	if !versionRe.MatchString(versionID) {
		return nil, ErrNotFound
	}
	b, err := readGz(filepath.Join(v.historyPath(id), versionID+".gz"))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, ErrNotFound
	}
	return b, err
}

// RestoreVersion makes an old version the note's text. It is a normal write
// (base is the version the caller last read, as in WriteNote) and always adds
// a new history version, so the text it replaces stays recoverable.
func (v *Vault) RestoreVersion(id, versionID, base string) (string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	text, err := v.readVersion(id, versionID)
	if err != nil {
		return "", err
	}
	return v.writeNote(id, text, base, true)
}

// snapshot saves a version (see saveVersion), then applies the count limit to
// the note's history. Only the count limit runs here: the age and per-period
// limits depend on the clock, so they run only in PruneHistory, which the maid
// calls behind its safety checks. Pruning here is best effort: if it fails the
// save still stands, and the next PruneHistory reports the problem.
func (v *Vault) snapshot(g Group, content []byte, seed *historySeed, force bool) error {
	if err := v.saveVersion(g, content, seed, force); err != nil {
		return err
	}
	if v.historyFor(g) && v.historyPolicy.MaxVersions > 0 {
		v.pruneDir(v.historyPath(g.ID), HistoryPolicy{MaxVersions: v.historyPolicy.MaxVersions}, v.now(), false)
	}
	return nil
}

// saveVersion records content as the note's newest version, following the window
// rule above. force always adds a version. seed, if given, is the text the
// note had just before this change: if that text is not already the newest
// version (it was written while history was off, or edited outside the app and
// not scanned yet) it is saved first so it is not lost. Does nothing if
// history is off for the note. Call with the lock held.
func (v *Vault) saveVersion(g Group, content []byte, seed *historySeed, force bool) error {
	if !v.historyFor(g) {
		return nil
	}
	dir := v.historyPath(g.ID)
	vs, err := listVersions(dir)
	if err != nil {
		return err
	}
	now := v.now()
	var prev []byte // text of the newest version
	if n := len(vs); n > 0 {
		if prev, err = readGz(filepath.Join(dir, vs[n-1].ID+".gz")); err != nil {
			return err
		}
	}
	if seed != nil && !bytes.Equal(seed.content, content) && (len(vs) == 0 || !bytes.Equal(prev, seed.content)) {
		at := seed.at
		if at.After(now) {
			at = now
		}
		if err := addVersion(dir, seed.content, at); err != nil {
			return err
		}
		prev, force = seed.content, true // the new text gets its own version
		vs = append(vs, Version{})
	}
	if len(vs) > 0 {
		if bytes.Equal(prev, content) {
			return nil
		}
		last := vs[len(vs)-1]
		if !force && v.historyWindow > 0 && now.Sub(last.Time) < v.historyWindow {
			return writeGz(filepath.Join(dir, last.ID+".gz"), content, true)
		}
	}
	return addVersion(dir, content, now)
}

// addVersion writes a new version, bumping its timestamp so versions stay
// strictly ordered and never overwrite each other.
func addVersion(dir string, content []byte, at time.Time) error {
	vs, err := listVersions(dir)
	if err != nil {
		return err
	}
	ms := at.UnixMilli()
	if n := len(vs); n > 0 {
		if last := vs[n-1].Time.UnixMilli(); ms <= last {
			ms = last + 1
		}
	}
	return writeGz(filepath.Join(dir, fmt.Sprintf("%013d.gz", ms)), content, false)
}

// listVersions returns the versions in dir, oldest first.
func listVersions(dir string) ([]Version, error) {
	ents, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var vs []Version
	for _, e := range ents {
		id, ok := strings.CutSuffix(e.Name(), ".gz")
		if !ok || !versionRe.MatchString(id) {
			continue // temp files and strays
		}
		ms, _ := strconv.ParseInt(id, 10, 64)
		vs = append(vs, Version{id, time.UnixMilli(ms)})
	}
	return vs, nil // ReadDir sorts by name, and the names are fixed width
}

func writeGz(dst string, b []byte, overwrite bool) error {
	var buf bytes.Buffer
	zw := gzip.NewWriter(&buf)
	if _, err := zw.Write(b); err != nil {
		return err
	}
	if err := zw.Close(); err != nil {
		return err
	}
	_, err := writeAtomic(dst, &buf, overwrite)
	return err
}

func readGz(p string) ([]byte, error) {
	f, err := os.Open(p)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return nil, err
	}
	return io.ReadAll(zr)
}

// Period is the calendar unit the "per period" pruning rule counts in.
type Period int

const (
	PeriodDay  Period = iota + 1
	PeriodWeek        // weeks start on Monday
	PeriodMonth
)

// HistoryPolicy limits how much history is kept. Every limit is optional (zero
// is off). A version is pruned if it breaks any limit that is on, except that
// a note's newest version is never pruned.
type HistoryPolicy struct {
	MaxVersions int           // keep at most this many versions per note
	MaxAge      time.Duration // delete versions older than this
	PerPeriod   int           // keep at most this many versions per finished Period...
	Period      Period        // ...the newest ones; the current period is left alone
}

// ErrBadPolicy means a negative limit, or PerPeriod without a valid Period.
var ErrBadPolicy = errors.New("vault: bad history policy")

// SetHistoryPolicy sets the vault-wide pruning limits. It deletes nothing by
// itself: the limits apply on the next saved version or PruneHistory.
func (v *Vault) SetHistoryPolicy(p HistoryPolicy) error {
	if p.MaxVersions < 0 || p.MaxAge < 0 || p.PerPeriod < 0 ||
		(p.PerPeriod > 0 && (p.Period < PeriodDay || p.Period > PeriodMonth)) {
		return ErrBadPolicy
	}
	v.mu.Lock()
	defer v.mu.Unlock()
	v.historyPolicy = p
	return nil
}

// PruneHistory applies the whole policy to every note's history, with now as
// the current time (the maid passes the time it has checked). It returns how
// many versions it deleted and how many versions there were in total. With
// dryRun it deletes nothing and returns how many it would delete. Trashed
// notes are skipped (Purge deletes their history).
func (v *Vault) PruneHistory(now time.Time, dryRun bool) (deleted, total int, err error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	groups, err := v.list()
	if err != nil {
		return 0, 0, err
	}
	var errs []error
	for _, g := range groups {
		n, t, err := v.pruneDir(v.historyPath(g.ID), v.historyPolicy, now, dryRun)
		deleted += n
		total += t
		if err != nil {
			errs = append(errs, err)
		}
	}
	return deleted, total, errors.Join(errs...)
}

// pruneDir applies policy p to one note's history folder and returns how many
// versions it deleted (or would delete, with dryRun) and how many there were.
// It only ever removes version files (<13 digits>.gz) inside dir.
func (v *Vault) pruneDir(dir string, p HistoryPolicy, now time.Time, dryRun bool) (int, int, error) {
	vs, err := listVersions(dir) // oldest first
	if err != nil || len(vs) < 2 {
		return 0, len(vs), err
	}
	newest := len(vs) - 1
	drop := make([]bool, len(vs))

	if p.MaxAge > 0 {
		for i, ver := range vs {
			if i != newest && now.Sub(ver.Time) > p.MaxAge {
				drop[i] = true
			}
		}
	}
	if p.PerPeriod > 0 {
		cur := v.periodStart(now, p.Period)
		buckets := map[int64][]int{} // period start -> versions in it, oldest first
		for i, ver := range vs {
			if ps := v.periodStart(ver.Time, p.Period); ps.Before(cur) {
				buckets[ps.Unix()] = append(buckets[ps.Unix()], i)
			}
		}
		for _, idx := range buckets {
			for _, i := range idx[:max(0, len(idx)-p.PerPeriod)] {
				drop[i] = true
			}
		}
	}
	if p.MaxVersions > 0 {
		var kept []int
		for i := range vs {
			if !drop[i] {
				kept = append(kept, i)
			}
		}
		for _, i := range kept[:max(0, len(kept)-p.MaxVersions)] {
			if i != newest {
				drop[i] = true
			}
		}
	}

	n := 0
	var errs []error
	for i, d := range drop {
		if !d {
			continue
		}
		if !dryRun {
			if err := os.Remove(filepath.Join(dir, vs[i].ID+".gz")); err != nil && !errors.Is(err, fs.ErrNotExist) {
				errs = append(errs, err)
				continue
			}
		}
		n++
	}
	return n, len(vs), errors.Join(errs...)
}

// periodStart is the start of the day, week (Monday) or month containing t, in
// the vault's time zone.
func (v *Vault) periodStart(t time.Time, p Period) time.Time {
	t = t.In(v.loc)
	y, m, d := t.Date()
	switch p {
	case PeriodWeek:
		d -= (int(t.Weekday()) + 6) % 7
	case PeriodMonth:
		d = 1
	}
	return time.Date(y, m, d, 0, 0, 0, 0, v.loc)
}
