package settings

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"styli/core/internal/vault"
)

func must[T any](v T, err error) T {
	if err != nil {
		panic(err) // a test panic fails that test with the error shown
	}
	return v
}

func open(t *testing.T) (*Store, string, string) {
	t.Helper()
	root, dev := t.TempDir(), t.TempDir()
	return must(Open(root, dev)), root, dev
}

func ptr(n int) *int { return &n }

func TestDefaultsWhenNoFilesAndNothingIsWritten(t *testing.T) {
	s, root, dev := open(t)
	want := Hub{History: History{On: true, WindowMinutes: 30}, TrashRetentionDays: 30,
		TimeCheck: TimeCheck{On: false, Server: "pool.ntp.org"}}
	if got := s.Hub(); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v", got)
	}
	if s.TrashRetention() != 30*24*time.Hour {
		t.Fatalf("retention %v", s.TrashRetention())
	}
	for _, p := range []string{filepath.Join(root, ".ids/settings.json"), filepath.Join(dev, "device.json")} {
		if _, err := os.Stat(p); err == nil {
			t.Errorf("%s written before any change", p)
		}
	}
}

func TestRoundTripAcrossReopenAndFileLocations(t *testing.T) {
	s, root, dev := open(t)
	h := Hub{Name: "Home hub", TrashRetentionDays: 14,
		History: History{On: false, WindowMinutes: 45, MaxVersions: 50, MaxAgeDays: 90, PerPeriod: 1, Period: "day"}}
	d := Device{Name: "Tablet", TrashRetentionDays: ptr(7)}
	if err := s.SetHub(h); err != nil {
		t.Fatal(err)
	}
	if err := s.SetDevice(d); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{filepath.Join(root, ".ids/settings.json"), filepath.Join(dev, "device.json")} {
		if _, err := os.Stat(p); err != nil {
			t.Errorf("missing %s", p)
		}
	}
	s2 := must(Open(root, dev))
	if !reflect.DeepEqual(s2.Hub(), h) || !reflect.DeepEqual(s2.Device(), d) {
		t.Fatalf("got %+v %+v", s2.Hub(), s2.Device())
	}
}

func TestMissingFieldsKeepTheirDefaults(t *testing.T) {
	root, dev := t.TempDir(), t.TempDir()
	os.MkdirAll(filepath.Join(root, ".ids"), 0o755)
	os.WriteFile(filepath.Join(root, ".ids/settings.json"), []byte(`{"version":1,"hub":{"name":"X"}}`), 0o600)
	s := must(Open(root, dev))
	if h := s.Hub(); h.Name != "X" || !h.History.On || h.History.WindowMinutes != 30 || h.TrashRetentionDays != 30 {
		t.Fatalf("got %+v", h)
	}
}

func TestInvalidValuesAreRejectedAndWriteNothing(t *testing.T) {
	s, root, dev := open(t)
	bad := func(f func(*Hub)) Hub { h := DefaultHub(); f(&h); return h }
	for name, h := range map[string]Hub{
		"long name":            bad(func(h *Hub) { h.Name = strings.Repeat("x", 101) }),
		"control char":         bad(func(h *Hub) { h.Name = "a\x00b" }),
		"negative window":      bad(func(h *Hub) { h.History.WindowMinutes = -1 }),
		"huge window":          bad(func(h *Hub) { h.History.WindowMinutes = 99999 }),
		"negative versions":    bad(func(h *Hub) { h.History.MaxVersions = -1 }),
		"negative age":         bad(func(h *Hub) { h.History.MaxAgeDays = -1 }),
		"per period no period": bad(func(h *Hub) { h.History.PerPeriod = 1 }),
		"bad period":           bad(func(h *Hub) { h.History.PerPeriod = 1; h.History.Period = "year" }),
		"negative retention":   bad(func(h *Hub) { h.TrashRetentionDays = -5 }),
		"time check no server": bad(func(h *Hub) { h.TimeCheck = TimeCheck{On: true} }),
		"time server spaces":   bad(func(h *Hub) { h.TimeCheck.Server = "pool ntp org" }),
		"time server url":      bad(func(h *Hub) { h.TimeCheck.Server = "http://x/y" }),
	} {
		if err := s.SetHub(h); !errors.Is(err, ErrInvalid) {
			t.Errorf("%s: %v", name, err)
		}
	}
	if err := s.SetDevice(Device{TrashRetentionDays: ptr(-1)}); !errors.Is(err, ErrInvalid) {
		t.Errorf("device retention: %v", err)
	}
	if err := s.SetDevice(Device{Name: "a\nb"}); !errors.Is(err, ErrInvalid) {
		t.Errorf("device name: %v", err)
	}
	if !reflect.DeepEqual(s.Hub(), DefaultHub()) {
		t.Fatal("a rejected value changed the settings")
	}
	for _, p := range []string{filepath.Join(root, ".ids/settings.json"), filepath.Join(dev, "device.json")} {
		if _, err := os.Stat(p); err == nil {
			t.Errorf("%s written for a rejected value", p)
		}
	}
}

func TestBrokenFilesAreRefusedAndLeftAlone(t *testing.T) {
	for name, content := range map[string]string{
		"garbage":        "not json {",
		"newer build":    `{"version":99,"hub":{"future_field":1}}`,
		"no version":     `{"hub":{"name":"X"}}`,
		"unknown field":  `{"version":1,"hub":{"nmae":"typo"}}`,
		"invalid values": `{"version":1,"hub":{"trash_retention_days":-3}}`,
	} {
		root, dev := t.TempDir(), t.TempDir()
		p := filepath.Join(root, ".ids/settings.json")
		os.MkdirAll(filepath.Dir(p), 0o755)
		os.WriteFile(p, []byte(content), 0o600)
		if _, err := Open(root, dev); err == nil {
			t.Errorf("%s: Open succeeded", name)
		}
		if b, _ := os.ReadFile(p); string(b) != content {
			t.Errorf("%s: file was modified", name)
		}
	}
	root, dev := t.TempDir(), t.TempDir()
	os.MkdirAll(filepath.Join(root, ".ids"), 0o755)
	os.WriteFile(filepath.Join(root, ".ids/settings.json"), []byte(`{"version":99}`), 0o600)
	if _, err := Open(root, dev); !errors.Is(err, ErrNewer) {
		t.Errorf("want ErrNewer, got %v", err)
	}
}

func TestBrokenDeviceFileIsRefused(t *testing.T) {
	root, dev := t.TempDir(), t.TempDir()
	os.WriteFile(filepath.Join(dev, "device.json"), []byte("???"), 0o600)
	if _, err := Open(root, dev); err == nil {
		t.Fatal("Open succeeded")
	}
}

func TestTrashRetentionDeviceBeatsHub(t *testing.T) {
	s, _, _ := open(t)
	day := 24 * time.Hour
	s.SetHub(Hub{TrashRetentionDays: 30, History: History{On: true}})
	if s.TrashRetention() != 30*day {
		t.Fatal("hub default")
	}
	s.SetDevice(Device{TrashRetentionDays: ptr(7)})
	if s.TrashRetention() != 7*day {
		t.Fatal("device override")
	}
	s.SetDevice(Device{TrashRetentionDays: ptr(0)}) // keep forever on this device
	if s.TrashRetention() != 0 {
		t.Fatal("explicit keep-forever must beat the atrium's 30 days")
	}
	s.SetDevice(Device{}) // nothing set: back to the atrium's
	if s.TrashRetention() != 30*day {
		t.Fatal("back to hub default")
	}
}

func TestDeviceReturnsACopy(t *testing.T) {
	s, _, _ := open(t)
	s.SetDevice(Device{TrashRetentionDays: ptr(5)})
	*s.Device().TrashRetentionDays = 999
	if *s.Device().TrashRetentionDays != 5 {
		t.Fatal("caller changed the stored setting")
	}
}

func TestApplyToChangesTheVault(t *testing.T) {
	s, root, _ := open(t)
	v := must(vault.Open(root))

	// History off: no versions.
	h := DefaultHub()
	h.History.On = false
	s.SetHub(h)
	if err := s.ApplyTo(v); err != nil {
		t.Fatal(err)
	}
	off := must(v.CreateNote("off.md", []byte("x")))
	if len(must(v.History(off.ID))) != 0 {
		t.Fatal("history was off but a version was saved")
	}

	// History on, window 0, keep at most 2: every write is a version, pruned to 2.
	h.History = History{On: true, WindowMinutes: 0, MaxVersions: 2}
	s.SetHub(h)
	s.ApplyTo(v)
	g := must(v.CreateNote("on.md", []byte("a")))
	for _, text := range []string{"b", "c", "d"} {
		_, base, _ := v.ReadNote(g.ID)
		must(v.WriteNote(g.ID, []byte(text), base))
	}
	if n := len(must(v.History(g.ID))); n != 2 {
		t.Fatalf("want 2 versions, got %d", n)
	}
}

func TestApplyToPassesPeriodRule(t *testing.T) {
	s, root, _ := open(t)
	v := must(vault.Open(root))
	h := DefaultHub()
	h.History.PerPeriod, h.History.Period = 1, "week"
	s.SetHub(h)
	if err := s.ApplyTo(v); err != nil {
		t.Fatal(err)
	}
}

func TestLeftoverTempFileIsIgnored(t *testing.T) {
	root, dev := t.TempDir(), t.TempDir()
	os.MkdirAll(filepath.Join(root, ".ids"), 0o755)
	os.WriteFile(filepath.Join(root, ".ids/.styli-tmp-123"), []byte("half a write"), 0o600)
	s := must(Open(root, dev))
	if err := s.SetHub(DefaultHub()); err != nil {
		t.Fatal(err)
	}
}

func TestConcurrentReadsAndWrites(t *testing.T) {
	s, _, _ := open(t)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 20; j++ {
				h := DefaultHub()
				h.TrashRetentionDays = i + j
				s.SetHub(h)
				s.Hub()
				s.SetDevice(Device{TrashRetentionDays: ptr(j)})
				s.TrashRetention()
			}
		}(i)
	}
	wg.Wait()
}

func TestDeviceDirIsStablePerVault(t *testing.T) {
	a1, _ := DeviceDir("/x/vault")
	a2, _ := DeviceDir("/x/vault")
	b, _ := DeviceDir("/x/other")
	if a1 != a2 || a1 == b {
		t.Fatalf("%q %q %q", a1, a2, b)
	}
}

func TestTimeServerForms(t *testing.T) {
	s, _, _ := open(t)
	for _, srv := range []string{"pool.ntp.org", "time.local:123", "192.168.1.1", "::1", "[fe80::1]:123"} {
		h := DefaultHub()
		h.TimeCheck = TimeCheck{On: true, Server: srv}
		if err := s.SetHub(h); err != nil {
			t.Errorf("%q: %v", srv, err)
		}
	}
	h := DefaultHub()
	h.TimeCheck = TimeCheck{On: false, Server: ""} // empty is fine while off
	if err := s.SetHub(h); err != nil {
		t.Fatal(err)
	}
}

func TestDeviceFolderIsPrivate(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix permissions")
	}
	root, dev := t.TempDir(), filepath.Join(t.TempDir(), "config", "styli", "abcd")
	s := must(Open(root, dev))
	s.SetDevice(Device{Name: "Laptop"})
	for p, want := range map[string]os.FileMode{dev: 0o700, filepath.Join(dev, "device.json"): 0o600} {
		fi, err := os.Stat(p)
		if err != nil || fi.Mode().Perm() != want {
			t.Errorf("%s: %v %o, want %o", p, err, fi.Mode().Perm(), want)
		}
	}
	os.Chmod(dev, 0o755) // an older build's folder is tightened on the next start
	must(Open(root, dev))
	if fi, _ := os.Stat(dev); fi.Mode().Perm() != 0o700 {
		t.Errorf("not tightened: %o", fi.Mode().Perm())
	}
}
