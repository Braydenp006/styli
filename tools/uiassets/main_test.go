package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const sample = `{"color":{"tokens":[{"name":"bg","value":{"light":"#FFFFFF","dark":"#000000"}},{"name":"ink","value":{"light":"#111111","dark":"#EEEEEE"}}]},
"type":{"families":{"display":"Serif","sans":"Sans, system-ui","mono":"Mono"}},
"spacing":{"tokens":[{"name":"space-1","value":"4px"}]},"radius":{"tokens":[{"name":"radius-md","value":"10px"}]},
"shadow":{"tokens":[{"name":"shadow-card","value":{"light":"0 1px 2px #0001","dark":"0 1px 2px #0006"}}]}}`

func design(t *testing.T, tokensJSON string) string {
	t.Helper()
	d := t.TempDir()
	os.MkdirAll(filepath.Join(d, "components"), 0o755)
	os.MkdirAll(filepath.Join(d, "logos"), 0o755)
	os.WriteFile(filepath.Join(d, "tokens.json"), []byte(tokensJSON), 0o644)
	os.WriteFile(filepath.Join(d, "components/styli.css"), []byte(".sty-btn{color:var(--ink)}"), 0o644)
	for _, l := range []string{"styli-mark-light-bg.svg", "styli-mark-dark-bg.svg"} {
		os.WriteFile(filepath.Join(d, "logos", l), []byte("<svg/>"), 0o644)
	}
	return d
}

func TestGeneratesLightDarkAndCopies(t *testing.T) {
	out := t.TempDir()
	if err := run(design(t, sample), out); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(filepath.Join(out, "css/tokens.css"))
	css := string(b)
	light, dark, _ := strings.Cut(css, "@media (prefers-color-scheme: dark)")
	for _, want := range []string{"--bg: #FFFFFF;", "--ink: #111111;", "--font-sans: Sans, system-ui;", "--space-1: 4px;", "--radius-md: 10px;", "--shadow-card: 0 1px 2px #0001;"} {
		if !strings.Contains(light, want) {
			t.Errorf("light lacks %q", want)
		}
	}
	for _, want := range []string{"--bg: #000000;", "--ink: #EEEEEE;", "--shadow-card: 0 1px 2px #0006;"} {
		if !strings.Contains(dark, want) {
			t.Errorf("dark lacks %q", want)
		}
	}
	if strings.Contains(dark, "--space-1") {
		t.Error("dark block should only hold what changes")
	}
	for _, f := range []string{"css/components.css", "img/styli-mark-light-bg.svg", "img/styli-mark-dark-bg.svg"} {
		if _, err := os.Stat(filepath.Join(out, f)); err != nil {
			t.Errorf("missing %s", f)
		}
	}
}

func TestRefusesValuesThatWouldBreakTheStylesheet(t *testing.T) {
	bad := strings.Replace(sample, `"#FFFFFF"`, `"#FFF;} body{display:none"`, 1)
	if err := run(design(t, bad), t.TempDir()); err == nil {
		t.Fatal("accepted a value containing ; and }")
	}
	if err := run(design(t, `{"type":{"families":{}}}`), t.TempDir()); err == nil {
		t.Fatal("accepted a design with no font families")
	}
	if err := run(t.TempDir(), t.TempDir()); err == nil {
		t.Fatal("accepted an empty design folder")
	}
}

func TestTheRealDesignGenerates(t *testing.T) {
	real := "../../design"
	if _, err := os.Stat(filepath.Join(real, "tokens.json")); err != nil {
		t.Skip("design/ is not in this checkout")
	}
	if err := run(real, t.TempDir()); err != nil {
		t.Fatal(err)
	}
}
