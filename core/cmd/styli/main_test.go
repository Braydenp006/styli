package main

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"

	"styli/core/internal/ink"
	"styli/core/internal/ink/inkpb"
)

func TestVersionSet(t *testing.T) {
	if Version == "" {
		t.Fatal("Version must not be empty")
	}
}

func TestRunCommands(t *testing.T) {
	for name, c := range map[string]struct {
		args   []string
		code   int
		stdout string
		stderr string
	}{
		"version":                 {[]string{"version"}, 0, "styli " + Version, ""},
		"no command":              {nil, 2, "", "usage"},
		"unknown":                 {[]string{"frobnicate"}, 2, "", "unknown command"},
		"serve no vault":          {[]string{"serve"}, 2, "", "--vault is required"},
		"serve bad flag":          {[]string{"serve", "--nope"}, 2, "", "flag provided but not defined"},
		"serve missing vault dir": {[]string{"serve", "--vault", "/definitely/not/here"}, 1, "", "does not exist"},
		"serve bad address":       {[]string{"serve", "--vault", t.TempDir(), "--addr", "example.com:7878"}, 1, "", "bad address"},
	} {
		var out, errb bytes.Buffer
		if code := run(c.args, &out, &errb); code != c.code ||
			!strings.Contains(out.String(), c.stdout) || !strings.Contains(errb.String(), c.stderr) {
			t.Errorf("%s: code %d, stdout %q, stderr %q", name, code, out.String(), errb.String())
		}
	}
}

func TestInkDump(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p.stylog")
	if err := ink.Append(p, &inkpb.Event{Device: "d", Seq: 1, Kind: &inkpb.Event_PageSet{PageSet: &inkpb.PageSet{Width: 1, Height: 2}}}); err != nil {
		t.Fatal(err)
	}
	var out, errb bytes.Buffer
	if code := run([]string{"ink", "dump", p}, &out, &errb); code != 0 || !strings.Contains(out.String(), "page-set") {
		t.Fatalf("%d %q %q", code, out.String(), errb.String())
	}
	if code := run([]string{"ink", "dump"}, &out, &errb); code != 2 {
		t.Fatal(code)
	}
}
