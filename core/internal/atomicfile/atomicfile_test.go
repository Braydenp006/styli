package atomicfile

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteCreatesOverwritesAndRefuses(t *testing.T) {
	dst := filepath.Join(t.TempDir(), "a", "b", "f.txt") // folders are created
	h, err := Write(dst, strings.NewReader("one"), false)
	if err != nil || len(h) != 64 {
		t.Fatalf("%q %v", h, err)
	}
	if _, err := Write(dst, strings.NewReader("two"), false); !errors.Is(err, ErrExists) {
		t.Fatalf("want ErrExists, got %v", err)
	}
	if b, _ := os.ReadFile(dst); string(b) != "one" {
		t.Fatalf("refused write changed the file: %q", b)
	}
	if _, err := Write(dst, strings.NewReader("two"), true); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(dst); string(b) != "two" {
		t.Fatalf("got %q", b)
	}
	ents, _ := os.ReadDir(filepath.Dir(dst))
	if len(ents) != 1 {
		t.Fatalf("temp files left behind: %v", ents)
	}
}

type failingReader struct{}

func (failingReader) Read([]byte) (int, error) { return 0, errors.New("boom") }

func TestFailedWriteKeepsTheOldFileAndLeavesNoTemp(t *testing.T) {
	dst := filepath.Join(t.TempDir(), "f.txt")
	Write(dst, strings.NewReader("keep"), false)
	if _, err := Write(dst, failingReader{}, true); err == nil {
		t.Fatal("want an error")
	}
	if b, _ := os.ReadFile(dst); string(b) != "keep" {
		t.Fatalf("got %q", b)
	}
	if ents, _ := os.ReadDir(filepath.Dir(dst)); len(ents) != 1 {
		t.Fatalf("temp files left behind: %v", ents)
	}
}
