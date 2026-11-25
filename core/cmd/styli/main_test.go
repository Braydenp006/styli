package main

import (
	"bytes"
	"strings"
	"testing"
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
		"serve public address":    {[]string{"serve", "--vault", t.TempDir(), "--addr", "0.0.0.0:7878"}, 1, "", "refusing to listen"},
	} {
		var out, errb bytes.Buffer
		if code := run(c.args, &out, &errb); code != c.code ||
			!strings.Contains(out.String(), c.stdout) || !strings.Contains(errb.String(), c.stderr) {
			t.Errorf("%s: code %d, stdout %q, stderr %q", name, code, out.String(), errb.String())
		}
	}
}
