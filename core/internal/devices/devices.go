// Package devices is the atrium's list of paired devices: who may sync. It
// lives in the vault (.ids/devices.json) so it travels with backups and
// atrium handover. A device is known by its key's fingerprint (see devicekey).
package devices

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"sync"
	"time"

	"styli/core/internal/atomicfile"
)

const fileVersion = 1

var (
	ErrExists   = errors.New("devices: already paired")
	ErrNotFound = errors.New("devices: no such device")
	ErrBadID    = errors.New("devices: bad device ID")
)

var idRe = regexp.MustCompile(`^[0-9a-f]{64}$`)

// SeenEvery is how often a device's "last seen" time is written to disk; in
// between it is kept in memory.
var SeenEvery = time.Hour

// Device is one paired device.
type Device struct {
	ID        string     `json:"id"`
	Name      string     `json:"name"`
	PairedAt  time.Time  `json:"paired_at"`
	LastSeen  time.Time  `json:"last_seen,omitzero"`
	RevokedAt *time.Time `json:"revoked_at,omitempty"`
}

type file struct {
	Version int      `json:"version"`
	Devices []Device `json:"devices"`
}

// List is the paired devices. Safe for concurrent use.
type List struct {
	path string
	mu   sync.Mutex
	devs map[string]*Device
	// saved is when each device's last-seen time was last written.
	saved map[string]time.Time
	// OnRevoke, if set, is called after a device is revoked, so its open
	// connections can be closed.
	OnRevoke func(id string)
}

// Open reads the list from the vault. A missing file is an empty list; a
// damaged one or one from a newer build is an error, never an empty list,
// because an empty list would quietly lock out every device.
func Open(vaultRoot string) (*List, error) {
	l := &List{path: filepath.Join(vaultRoot, ".ids", "devices.json"), devs: map[string]*Device{}, saved: map[string]time.Time{}}
	b, err := os.ReadFile(l.path)
	if errors.Is(err, fs.ErrNotExist) {
		return l, nil
	}
	if err != nil {
		return nil, err
	}
	var f file
	if err := json.Unmarshal(b, &f); err != nil {
		return nil, fmt.Errorf("devices: %s is damaged: %w", l.path, err)
	}
	if f.Version > fileVersion {
		return nil, fmt.Errorf("devices: %s was written by a newer version of Styli; update this one", l.path)
	}
	for i := range f.Devices {
		d := f.Devices[i]
		if !idRe.MatchString(d.ID) {
			return nil, fmt.Errorf("devices: %s is damaged: bad ID %q", l.path, d.ID)
		}
		l.devs[d.ID] = &d
		l.saved[d.ID] = d.LastSeen
	}
	return l, nil
}

// Add pairs a new device. A key that was ever paired, even if revoked, is
// refused: a revoked device must make a new key to come back.
func (l *List) Add(id, name string) error {
	if !idRe.MatchString(id) {
		return ErrBadID
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if _, ok := l.devs[id]; ok {
		return ErrExists
	}
	l.devs[id] = &Device{ID: id, Name: name, PairedAt: time.Now().UTC()}
	if err := l.save(); err != nil {
		delete(l.devs, id)
		return err
	}
	return nil
}

// Revoke stops a device from syncing, at once and for good.
func (l *List) Revoke(id string) error {
	l.mu.Lock()
	d, ok := l.devs[id]
	if !ok {
		l.mu.Unlock()
		return ErrNotFound
	}
	if d.RevokedAt == nil {
		now := time.Now().UTC()
		d.RevokedAt = &now
		if err := l.save(); err != nil {
			d.RevokedAt = nil
			l.mu.Unlock()
			return err
		}
	}
	hook := l.OnRevoke
	l.mu.Unlock()
	if hook != nil {
		hook(id)
	}
	return nil
}

// Trusted reports whether a device may sync: paired and not revoked.
func (l *List) Trusted(id string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	d, ok := l.devs[id]
	return ok && d.RevokedAt == nil
}

// Seen notes that a device just made a request.
func (l *List) Seen(id string, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	d, ok := l.devs[id]
	if !ok {
		return
	}
	d.LastSeen = now.UTC()
	if now.Sub(l.saved[id]) >= SeenEvery {
		l.saved[id] = now
		l.save() // best effort: only the last-seen time is at stake
	}
}

// List returns every device, revoked ones included, in pairing order.
func (l *List) List() []Device {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.sorted()
}

func (l *List) sorted() []Device {
	out := make([]Device, 0, len(l.devs))
	for _, d := range l.devs {
		out = append(out, *d)
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].PairedAt.Equal(out[j].PairedAt) {
			return out[i].PairedAt.Before(out[j].PairedAt)
		}
		return out[i].ID < out[j].ID
	})
	return out
}

func (l *List) save() error {
	b, err := json.MarshalIndent(file{fileVersion, l.sorted()}, "", "  ")
	if err != nil {
		return err
	}
	_, err = atomicfile.Write(l.path, bytes.NewReader(b), true)
	return err
}
