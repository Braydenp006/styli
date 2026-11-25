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
		"no atrium":   {[]string{"join", "--vault", t.TempDir()}, "", 2, "--atrium are required"},
		"no vault":    {[]string{"join", "--atrium", addr}, "", 2, "--vault and"},
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
