// Package settings stores the user's settings in two layers, as the design
// says: atrium settings (vault-wide defaults, kept inside the vault so they travel
// with its backups and handover) and device settings (this device only, kept in
// the user's config folder). A device setting, where it has one, wins over the
// atrium's.
package settings

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"styli/core/internal/atomicfile"
	"styli/core/internal/vault"
)

const fileVersion = 1

var (
	// ErrInvalid means a value was rejected; nothing was written.
	ErrInvalid = errors.New("settings: invalid value")
	// ErrNewer means a settings file was written by a newer build.
	ErrNewer = errors.New("settings: file is newer than this build")
)

// History holds the version-history defaults. Zero limits are off.
type History struct {
	On            bool   `json:"on"`
	WindowMinutes int    `json:"window_minutes"`   // edits within this fold into one version
	MaxVersions   int    `json:"max_versions"`     // per note
	MaxAgeDays    int    `json:"max_age_days"`     // delete versions older than this
	PerPeriod     int    `json:"per_period"`       // keep at most this many per finished period
	Period        string `json:"period,omitempty"` // "day", "week" or "month"
}

// TimeCheck asks a time server whether this machine's clock is right before
// the maid deletes anything. Off by default: it is a call to an outside
// server (it sends no notes, only reveals that this address asked the time).
type TimeCheck struct {
	On     bool   `json:"on"`
	Server string `json:"server"` // host or host:port; port 123 if left out
}

// DefaultTimeServer is the public NTP pool; a local server can replace it.
const DefaultTimeServer = "pool.ntp.org"

// Hub is the vault-wide settings.
type Hub struct {
	Name               string    `json:"name"`
	History            History   `json:"history"`
	TrashRetentionDays int       `json:"trash_retention_days"` // 0 keeps trash forever
	TimeCheck          TimeCheck `json:"time_check"`
}

// Device is this device's own settings.
type Device struct {
	Name string `json:"name"`
	// TrashRetentionDays overrides the atrium's default. nil means "use the atrium's";
	// a pointer to 0 means "keep forever on this device".
	TrashRetentionDays *int `json:"trash_retention_days,omitempty"`
	// Atrium is the atrium this device paired with; nil until it pairs. Only
	// pairing changes it (SetAtrium): SetDevice keeps whatever is saved.
	Atrium *AtriumLink `json:"atrium,omitempty"`
}

// AtriumLink is what a device keeps about its atrium: the atrium's key (sync
// talks to nothing else) and where to reach it.
type AtriumLink struct {
	ID       string    `json:"id"`   // the atrium's key fingerprint
	Addr     string    `json:"addr"` // host:port of its sync port
	PairedAt time.Time `json:"paired_at"`
}

// DefaultHub is what a new atrium starts with.
func DefaultHub() Hub {
	return Hub{
		History:            History{On: true, WindowMinutes: 30},
		TrashRetentionDays: 30,
		TimeCheck:          TimeCheck{Server: DefaultTimeServer},
	}
}

type hubFile struct {
	Version int `json:"version"`
	Hub     Hub `json:"hub"`
}

type deviceFile struct {
	Version int    `json:"version"`
	Device  Device `json:"device"`
}

// Store holds both layers and saves changes.
type Store struct {
	mu      sync.RWMutex
	hubPath string
	devPath string
	hub     Hub
	device  Device
}

// DeviceDir is the default folder for this device's settings for a vault.
func DeviceDir(vaultRoot string) (string, error) {
	abs, err := filepath.Abs(vaultRoot)
	if err != nil {
		return "", err
	}
	cfg, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(abs))
	return filepath.Join(cfg, "styli", hex.EncodeToString(sum[:4])), nil
}

// Open loads both files. A missing file means defaults (nothing is written
// until a setter is called). A file that cannot be read, understood or that
// comes from a newer build is an error: it is never replaced by defaults,
// because that could silently change what the app deletes.
func Open(vaultRoot, deviceDir string) (*Store, error) {
	if err := os.MkdirAll(deviceDir, 0o700); err != nil {
		return nil, err
	}
	if err := os.Chmod(deviceDir, 0o700); err != nil {
		return nil, err
	}
	s := &Store{
		hubPath: filepath.Join(vaultRoot, ".ids", "settings.json"),
		devPath: filepath.Join(deviceDir, "device.json"),
	}
	hf := hubFile{Hub: DefaultHub()}
	if err := load(s.hubPath, &hf); err != nil {
		return nil, err
	}
	if err := validateHub(hf.Hub); err != nil {
		return nil, fmt.Errorf("%s: %w", s.hubPath, err)
	}
	df := deviceFile{}
	if err := load(s.devPath, &df); err != nil {
		return nil, err
	}
	if err := validateDevice(df.Device); err != nil {
		return nil, fmt.Errorf("%s: %w", s.devPath, err)
	}
	s.hub, s.device = hf.Hub, df.Device
	return s, nil
}

// load reads path into v (pre-filled with defaults). Unknown fields are refused.
func load(path string, v any) error {
	b, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	// Check the version first, leniently: a newer build's file may have fields
	// we do not know, and "newer" is a clearer error than "unknown field".
	var head struct{ Version int }
	if err := json.Unmarshal(b, &head); err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	switch {
	case head.Version > fileVersion:
		return fmt.Errorf("%w: %s", ErrNewer, path)
	case head.Version < 1:
		return fmt.Errorf("%s: missing or bad version", path)
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	return nil
}

func save(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	_, err = atomicfile.Write(path, bytes.NewReader(append(b, '\n')), true)
	return err
}

// Hub returns the atrium settings.
func (s *Store) Hub() Hub {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.hub
}

// SetHub validates and saves the atrium settings. It changes nothing if the
// values are invalid or the save fails. Call ApplyTo afterwards.
func (s *Store) SetHub(h Hub) error {
	if err := validateHub(h); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := save(s.hubPath, hubFile{fileVersion, h}); err != nil {
		return err
	}
	s.hub = h
	return nil
}

// Device returns this device's settings.
func (s *Store) Device() Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return copyDevice(s.device)
}

// SetDevice validates and saves this device's settings. The saved atrium is
// kept as it is, whatever d holds: only SetAtrium changes it.
func (s *Store) SetDevice(d Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d.Atrium = s.device.Atrium
	return s.setDevice(d)
}

// SetAtrium saves the atrium this device paired with (nil forgets it).
func (s *Store) SetAtrium(a *AtriumLink) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := copyDevice(s.device)
	d.Atrium = a
	return s.setDevice(d)
}

func (s *Store) setDevice(d Device) error {
	if err := validateDevice(d); err != nil {
		return err
	}
	d = copyDevice(d)
	if err := save(s.devPath, deviceFile{fileVersion, d}); err != nil {
		return err
	}
	s.device = d
	return nil
}

func copyDevice(d Device) Device {
	if d.TrashRetentionDays != nil {
		n := *d.TrashRetentionDays
		d.TrashRetentionDays = &n
	}
	if d.Atrium != nil {
		a := *d.Atrium
		d.Atrium = &a
	}
	return d
}

// TrashRetention is how long trash is kept on this device: the device's own
// setting if it has one, otherwise the atrium's. Zero means keep forever.
func (s *Store) TrashRetention() time.Duration {
	s.mu.RLock()
	defer s.mu.RUnlock()
	days := s.hub.TrashRetentionDays
	if s.device.TrashRetentionDays != nil {
		days = *s.device.TrashRetentionDays
	}
	return time.Duration(days) * 24 * time.Hour
}

// ApplyTo pushes the history settings into the vault. Call it at startup and
// after SetHub. It deletes nothing; pruning happens when the vault next saves
// a version or PruneHistory runs.
func (s *Store) ApplyTo(v *vault.Vault) error {
	h := s.Hub().History
	if err := v.SetHistoryPolicy(vault.HistoryPolicy{
		MaxVersions: h.MaxVersions,
		MaxAge:      time.Duration(h.MaxAgeDays) * 24 * time.Hour,
		PerPeriod:   h.PerPeriod,
		Period:      periods[h.Period],
	}); err != nil {
		return err
	}
	v.SetHistoryDefault(h.On)
	v.SetHistoryWindow(time.Duration(h.WindowMinutes) * time.Minute)
	return nil
}

var periods = map[string]vault.Period{"day": vault.PeriodDay, "week": vault.PeriodWeek, "month": vault.PeriodMonth}

func validateHub(h Hub) error {
	hi := h.History
	switch {
	case !validName(h.Name):
		return invalid("atrium name must be 100 characters or fewer, with no control characters")
	case hi.WindowMinutes < 0 || hi.WindowMinutes > 7*24*60:
		return invalid("history window must be between 0 minutes and 7 days")
	case hi.MaxVersions < 0 || hi.MaxVersions > 100000:
		return invalid("max versions must be between 0 and 100000")
	case hi.MaxAgeDays < 0 || hi.MaxAgeDays > 36500:
		return invalid("max age must be between 0 and 36500 days")
	case hi.PerPeriod < 0 || hi.PerPeriod > 1000:
		return invalid("versions per period must be between 0 and 1000")
	case hi.Period != "" && periods[hi.Period] == 0:
		return invalid("period must be day, week or month")
	case hi.PerPeriod > 0 && hi.Period == "":
		return invalid("versions per period needs a period")
	case h.TrashRetentionDays < 0 || h.TrashRetentionDays > 36500:
		return invalid("trash retention must be between 0 and 36500 days")
	case !validServer(h.TimeCheck.Server, h.TimeCheck.On):
		return invalid("time server must be a host name or address, with an optional :port")
	}
	return nil
}

// validServer allows host, host:port, an IPv6 address or [v6]:port, made only
// of the characters those use. Empty is allowed only while the check is off.
func validServer(s string, on bool) bool {
	if s == "" {
		return !on
	}
	if len(s) > 255 {
		return false
	}
	for _, r := range s {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || strings.ContainsRune(".-:[]", r)) {
			return false
		}
	}
	return true
}

func validateDevice(d Device) error {
	switch {
	case !validName(d.Name):
		return invalid("device name must be 100 characters or fewer, with no control characters")
	case d.TrashRetentionDays != nil && (*d.TrashRetentionDays < 0 || *d.TrashRetentionDays > 36500):
		return invalid("trash retention must be between 0 and 36500 days")
	case d.Atrium != nil && !atriumIDRe.MatchString(d.Atrium.ID):
		return invalid("the saved atrium's key is not valid")
	case d.Atrium != nil && !validAddr(d.Atrium.Addr):
		return invalid("the saved atrium's address must be host:port")
	}
	return nil
}

func validName(n string) bool {
	if !utf8.ValidString(n) || utf8.RuneCountInString(n) > 100 {
		return false
	}
	for _, r := range n {
		if unicode.IsControl(r) {
			return false
		}
	}
	return true
}

var atriumIDRe = regexp.MustCompile(`^[0-9a-f]{64}$`)

func validAddr(a string) bool {
	host, port, err := net.SplitHostPort(a)
	return err == nil && host != "" && port != "" && validServer(a, true)
}

func invalid(msg string) error { return fmt.Errorf("%w: %s", ErrInvalid, msg) }
