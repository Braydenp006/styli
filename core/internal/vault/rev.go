package vault

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Every vault numbers its own changes. A counter that only goes up gives each
// saved group record the next number as its Rev. On the atrium these are the
// numbers devices sync by ("what changed since N?"); on a device they tell the
// sync engine which groups changed locally. See "Sync protocol" in DESIGN.md.
//
//	<root>/.ids/sync/seq     the highest number handed out
//	<root>/.ids/sync/epoch   random; a new one means old numbers cannot be trusted
//
// A copy of the counter is kept outside the vault (SetSeqMirror). If the copy
// is ahead of the vault, the vault was restored from a backup and its numbers
// went backwards, so a new epoch is picked and devices do a full check.
const (
	syncDir   = "sync"
	seqFile   = "seq"
	epochFile = "epoch"
)

// openRevs loads the counter and epoch, and gives a number to every record
// that has none (records made before revisions existed). Called from Open.
func (v *Vault) openRevs() error {
	dir := filepath.Join(v.root, idsDir, syncDir)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	seq, err := readSeq(filepath.Join(dir, seqFile))
	if err != nil {
		return err
	}
	// The counter is written before each record, so it should never be behind
	// one; if the file was lost or is old, the records repair it.
	top, unnumbered, err := v.highestRev()
	if err != nil {
		return err
	}
	v.seq = max(seq, top)

	b, err := os.ReadFile(filepath.Join(dir, epochFile))
	switch {
	case errors.Is(err, fs.ErrNotExist):
		if err := v.newEpoch(); err != nil {
			return err
		}
	case err != nil:
		return err
	default:
		v.epoch = strings.TrimSpace(string(b))
		if !idRe.MatchString(v.epoch) {
			return fmt.Errorf("vault: %s is not a valid epoch", filepath.Join(dir, epochFile))
		}
	}

	for _, id := range unnumbered {
		g, err := v.load(id)
		if err != nil {
			return err
		}
		if err := v.save(&g); err != nil {
			return err
		}
	}
	return nil
}

// highestRev returns the highest Rev in the live and trashed records, and the
// IDs of live records with no Rev yet.
func (v *Vault) highestRev() (uint64, []string, error) {
	groups, err := v.list()
	if err != nil {
		return 0, nil, err
	}
	var top uint64
	var unnumbered []string
	for _, g := range groups {
		top = max(top, g.Rev)
		if g.Rev == 0 {
			unnumbered = append(unnumbered, g.ID)
		}
	}
	ids, err := v.trashIDs()
	if err != nil {
		return 0, nil, err
	}
	for _, id := range ids {
		r, err := v.readTrash(id)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return 0, nil, err
		}
		top = max(top, r.Rev)
	}
	return top, unnumbered, nil
}

// nextRev hands out the next number. The counter (and its copy, if set) is
// written before the caller writes the record, so after a crash the counter
// is ahead of the records, never behind. Call with the lock held.
func (v *Vault) nextRev() (uint64, error) {
	n := v.seq + 1
	if err := writeSeq(filepath.Join(v.root, idsDir, syncDir, seqFile), n); err != nil {
		return 0, err
	}
	if v.mirror != "" {
		if err := writeSeq(v.mirror, n); err != nil {
			return 0, err
		}
	}
	v.seq = n
	return n, nil
}

// SetSeqMirror keeps a copy of the counter at path, a file outside the vault
// (the device's config folder). If the copy is ahead of the vault's counter,
// the vault was restored from a backup and a new epoch is picked. It reports
// whether that happened.
func (v *Vault) SetSeqMirror(path string) (restored bool, err error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	copySeq, err := readSeq(path)
	if err != nil {
		return false, err
	}
	if copySeq > v.seq {
		if err := v.newEpoch(); err != nil {
			return false, err
		}
		// Jump past the numbers the lost changes used, so no number is
		// ever handed out twice and the copy is never ahead again.
		if err := writeSeq(filepath.Join(v.root, idsDir, syncDir, seqFile), copySeq); err != nil {
			return true, err
		}
		v.seq, restored = copySeq, true
	}
	if err := writeSeq(path, v.seq); err != nil {
		return restored, err
	}
	v.mirror = path
	return restored, nil
}

// Seq returns the highest number handed out so far.
func (v *Vault) Seq() uint64 {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.seq
}

// Epoch returns the vault's epoch. Numbers from different epochs cannot be compared.
func (v *Vault) Epoch() string {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.epoch
}

func (v *Vault) newEpoch() error {
	e := newID()
	if _, err := writeAtomic(filepath.Join(v.root, idsDir, syncDir, epochFile), strings.NewReader(e+"\n"), true); err != nil {
		return err
	}
	v.epoch = e
	return nil
}

// readSeq reads a counter file. A missing file is 0; anything unreadable is
// an error rather than a guess, because a wrong counter breaks sync.
func readSeq(path string) (uint64, error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	n, err := strconv.ParseUint(strings.TrimSpace(string(b)), 10, 64)
	if err != nil {
		return 0, fmt.Errorf("vault: counter file %s is damaged: %w", path, err)
	}
	return n, nil
}

func writeSeq(path string, n uint64) error {
	_, err := writeAtomic(path, strings.NewReader(strconv.FormatUint(n, 10)+"\n"), true)
	return err
}
