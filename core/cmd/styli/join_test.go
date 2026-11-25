package main

import (
	"bytes"
	"context"
	"net"
	"strings"
	"testing"
	"time"

	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/discovery"
	"styli/core/internal/pairing"
	"styli/core/internal/settings"
	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

func testAtrium(t *testing.T) (*pairing.Atrium, *devices.List, *devicekey.Key, string) {
	t.Helper()
	root := t.TempDir()
	v, _ := vault.Open(root)
	devs, _ := devices.Open(root)
	key, _ := devicekey.Load(t.TempDir())
	p := pairing.NewAtrium(key.ID, devs)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := syncsrv.NewServer(syncsrv.Deps{Vault: v, Logf: t.Logf, Pairing: p}, key, devs)
	go s.Serve(ln)
	t.Cleanup(func() { s.Shutdown(context.Background()) })
	return p, devs, key, ln.Addr().String()
}

func TestJoinCommand(t *testing.T) {
	p, devs, key, addr := testAtrium(t)
	words := p.Open().Words
	go func() { // the person at the atrium approves when the emoji show
		for range 200 {
			if p.Status().Phase == pairing.Comparing {
				p.Approve("")
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()

	vaultDir, cfg := t.TempDir(), t.TempDir()
	// A typo first (not used up), then the words with odd spacing, then "y".
	stdin = strings.NewReader("copper otter\n" + strings.ToUpper(strings.ReplaceAll(words, "-", "  ")) + "\ny\n")
	t.Cleanup(func() { stdin = nil })
	var out, errb bytes.Buffer
	code := run([]string{"join", "--vault", vaultDir, "--config", cfg, "--atrium", addr, "--name", "Laptop"}, &out, &errb)
	if code != 0 {
		t.Fatalf("code %d\nstdout: %s\nstderr: %s", code, out.String(), errb.String())
	}
	for _, want := range []string{"aren't four of the magic words", "six symbols", "Paired."} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("stdout lacks %q:\n%s", want, out.String())
		}
	}
	set, _ := settings.Open(vaultDir, cfg)
	a := set.Device().Atrium
	if a == nil || a.ID != key.ID || a.Addr != addr {
		t.Fatalf("saved atrium: %+v", a)
	}
	dev, _ := devicekey.Load(cfg)
	if !devs.Trusted(dev.ID) || devs.List()[0].Name != "Laptop" {
		t.Fatal("atrium did not save the device")
	}

	// Joining again is refused: it is already paired.
	stdin = strings.NewReader("")
	out.Reset()
	errb.Reset()
	if code := run([]string{"join", "--vault", vaultDir, "--config", cfg, "--atrium", addr}, &out, &errb); code != 1 || !strings.Contains(errb.String(), "already paired") {
		t.Fatalf("second join: %d %s", code, errb.String())
	}
}

func TestJoinCommandErrors(t *testing.T) {
	_, _, _, addr := testAtrium(t)
	for name, c := range map[string]struct {
		args  []string
		input string
		code  int
		err   string
	}{
		"no vault":    {[]string{"join", "--atrium", addr}, "", 2, "--vault is required"},
		"bad vault":   {[]string{"join", "--vault", "/not/here", "--atrium", addr}, "", 1, "does not exist"},
		"no window":   {[]string{"join", "--vault", t.TempDir(), "--config", t.TempDir(), "--atrium", addr}, "amber anchor anvil apple\n", 1, "no pairing open"},
		"no words":    {[]string{"join", "--vault", t.TempDir(), "--config", t.TempDir(), "--atrium", addr}, "\n", 1, "not four magic words"},
		"unreachable": {[]string{"join", "--vault", t.TempDir(), "--config", t.TempDir(), "--atrium", "127.0.0.1:1"}, "amber anchor anvil apple\n", 1, "cannot reach"},
	} {
		stdin = strings.NewReader(c.input)
		var out, errb bytes.Buffer
		if code := run(c.args, &out, &errb); code != c.code || !strings.Contains(errb.String(), c.err) {
			t.Errorf("%s: code %d, stderr %q", name, code, errb.String())
		}
	}
	stdin = nil
}

func stubBrowse(t *testing.T, found ...discovery.Found) {
	t.Helper()
	old := browse
	browse = func(context.Context, time.Duration) ([]discovery.Found, error) { return found, nil }
	t.Cleanup(func() { browse = old })
}

// approveWhenComparing is the person at the atrium, approving the emoji.
func approveWhenComparing(p *pairing.Atrium) {
	go func() {
		for range 200 {
			if p.Status().Phase == pairing.Comparing {
				p.Approve("")
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()
}

func advert(key *devicekey.Key, addrs ...string) discovery.Found {
	return discovery.Found{ID: key.ID, Name: "styli-" + key.ID[:8], Addrs: addrs}
}

// With no --atrium, the one atrium found on the network is used.
func TestJoinFindsTheAtrium(t *testing.T) {
	p, devs, key, addr := testAtrium(t)
	words := p.Open().Words
	approveWhenComparing(p)
	stubBrowse(t, advert(key, addr))

	vaultDir, cfg := t.TempDir(), t.TempDir()
	stdin = strings.NewReader(words + "\ny\n")
	t.Cleanup(func() { stdin = nil })
	var out, errb bytes.Buffer
	if code := run([]string{"join", "--vault", vaultDir, "--config", cfg, "--name", "Laptop"}, &out, &errb); code != 0 {
		t.Fatalf("code %d\nstdout: %s\nstderr: %s", code, out.String(), errb.String())
	}
	if !strings.Contains(out.String(), "Found the atrium styli-"+key.ID[:8]) {
		t.Errorf("stdout:\n%s", out.String())
	}
	a := must(settings.Open(vaultDir, cfg)).Device().Atrium
	if a == nil || a.ID != key.ID || a.Addr != addr || len(a.Addrs) != 0 {
		t.Fatalf("saved atrium: %+v", a)
	}
	if dev, _ := devicekey.Load(cfg); !devs.Trusted(dev.ID) {
		t.Fatal("atrium did not save the device")
	}
}

func TestJoinWhenNoneOrSeveralAreFound(t *testing.T) {
	_, _, k1, a1 := testAtrium(t)
	_, _, k2, a2 := testAtrium(t)
	for name, c := range map[string]struct {
		found []discovery.Found
		err   []string
	}{
		"none":    {nil, []string{"no atrium was found", "--atrium"}},
		"several": {[]discovery.Found{advert(k1, a1), advert(k2, a2)}, []string{"more than one atrium", "styli-" + k1.ID[:8], "styli-" + k2.ID[:8], a1, a2}},
	} {
		stubBrowse(t, c.found...)
		vaultDir, cfg := t.TempDir(), t.TempDir()
		stdin = strings.NewReader("amber anchor anvil apple\n")
		var out, errb bytes.Buffer
		if code := run([]string{"join", "--vault", vaultDir, "--config", cfg}, &out, &errb); code != 1 {
			t.Errorf("%s: code %d", name, code)
		}
		for _, want := range c.err {
			if !strings.Contains(errb.String(), want) {
				t.Errorf("%s: stderr lacks %q:\n%s", name, want, errb.String())
			}
		}
		if must(settings.Open(vaultDir, cfg)).Device().Atrium != nil {
			t.Errorf("%s: something was saved", name)
		}
	}
	stdin = nil
}

// An advert is only a pointer. One that points at another machine, with the
// real atrium's ID on it, cannot pair this device: that machine does not know
// the words the person typed.
func TestJoinIsNotFooledByAnAdvert(t *testing.T) {
	real, _, realKey, _ := testAtrium(t)
	fake, _, _, fakeAddr := testAtrium(t)
	words := real.Open().Words
	fake.Open() // the impostor has a pairing open too, with other words
	stubBrowse(t, advert(realKey, fakeAddr))

	vaultDir, cfg := t.TempDir(), t.TempDir()
	stdin = strings.NewReader(words + "\ny\n")
	t.Cleanup(func() { stdin = nil })
	var out, errb bytes.Buffer
	if code := run([]string{"join", "--vault", vaultDir, "--config", cfg}, &out, &errb); code != 1 || !strings.Contains(errb.String(), "words did not match") {
		t.Fatalf("code %d, stderr %q", code, errb.String())
	}
	if must(settings.Open(vaultDir, cfg)).Device().Atrium != nil {
		t.Fatal("paired with the impostor")
	}
}

// --atrium more than once: the first that answers is used for pairing, and all
// are kept for later (a name that only works away from home, say).
func TestJoinKeepsEveryAddressGiven(t *testing.T) {
	p, _, key, addr := testAtrium(t)
	words := p.Open().Words
	approveWhenComparing(p)

	vaultDir, cfg := t.TempDir(), t.TempDir()
	stdin = strings.NewReader(words + "\ny\n")
	t.Cleanup(func() { stdin = nil })
	var out, errb bytes.Buffer
	args := []string{"join", "--vault", vaultDir, "--config", cfg, "--atrium", "127.0.0.1:1", "--atrium", addr}
	if code := run(args, &out, &errb); code != 0 {
		t.Fatalf("code %d\nstdout: %s\nstderr: %s", code, out.String(), errb.String())
	}
	a := must(settings.Open(vaultDir, cfg)).Device().Atrium
	if a == nil || a.ID != key.ID || a.Addr != addr || len(a.Addrs) != 2 || a.Addrs[0] != "127.0.0.1:1" || a.Addrs[1] != addr {
		t.Fatalf("saved atrium: %+v", a)
	}
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}
