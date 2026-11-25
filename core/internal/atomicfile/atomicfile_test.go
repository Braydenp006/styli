package atomicfile

import (
	"errors"
	"io"
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

func TestBeforeWriteRunsFirstAndCanStopTheWrite(t *testing.T) {
	dst := filepath.Join(t.TempDir(), "sub", "f")
	var got string
	SetBeforeWrite(func(d string) { got = d; panic("crash") })
	defer SetBeforeWrite(nil)
	func() {
		defer func() { recover() }()
		Write(dst, strings.NewReader("x"), true)
	}()
	if got != dst {
		t.Fatalf("hook saw %q", got)
	}
	if _, err := os.Stat(filepath.Dir(dst)); err == nil {
		t.Fatal("something was written before the hook ran")
	}
	SetBeforeWrite(nil)
	if _, err := Write(dst, strings.NewReader("x"), true); err != nil {
		t.Fatal(err)
	}
}

func TestAppendContinuesAPrefixAndRefusesAWrongLength(t *testing.T) {
	dst := filepath.Join(t.TempDir(), "a", "f.part")
	if n, err := Append(dst, 0, strings.NewReader("abc")); err != nil || n != 3 {
		t.Fatalf("%d %v", n, err)
	}
	if n, err := Append(dst, 3, strings.NewReader("def")); err != nil || n != 6 {
		t.Fatalf("%d %v", n, err)
	}
	if _, err := Append(dst, 4, strings.NewReader("x")); !errors.Is(err, ErrOffset) {
		t.Fatalf("wrong offset: %v", err)
	}
	if b, _ := os.ReadFile(dst); string(b) != "abcdef" {
		t.Fatalf("a refused append changed the file: %q", b)
	}
	// A copy that fails part way keeps what arrived, and says how much.
	n, err := Append(dst, 6, io.MultiReader(strings.NewReader("gh"), failingReader{}))
	if err == nil || n != 8 {
		t.Fatalf("%d %v", n, err)
	}
	if b, _ := os.ReadFile(dst); string(b) != "abcdefgh" {
		t.Fatalf("%q", b)
	}
	// Offset 0 starts again.
	if n, err := Append(dst, 0, strings.NewReader("z")); err != nil || n != 1 {
		t.Fatalf("%d %v", n, err)
	}
	if b, _ := os.ReadFile(dst); string(b) != "z" {
		t.Fatalf("%q", b)
	}
}
