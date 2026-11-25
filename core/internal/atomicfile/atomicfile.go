// Package atomicfile writes files so a crash leaves the old file or the new
// one, never half of one.
package atomicfile

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync/atomic"
)

// TmpPrefix starts the name of every temp file this package leaves while
// writing. Code that scans folders skips names starting with ".".
const TmpPrefix = ".styli-tmp-"

// ErrExists means dst exists and overwrite was not allowed.
var ErrExists = errors.New("already exists")

var beforeWrite atomic.Pointer[func(dst string)]

// SetBeforeWrite sets f (nil clears it) to be called with the destination
// before every Write. It exists for tests that simulate a crash by panicking
// in f: nothing has been written yet when it runs.
func SetBeforeWrite(f func(dst string)) {
	if f == nil {
		beforeWrite.Store(nil)
		return
	}
	beforeWrite.Store(&f)
}

// Write writes r to dst via a temp file in the same folder, then renames it
// into place, creating folders as needed. It returns the hex sha256 of what it
// wrote. Without overwrite it fails with ErrExists if dst exists.
// shortcut: the exists check and the rename are not one atomic step, so two
// processes writing the same new file at once could both succeed.
func Write(dst string, r io.Reader, overwrite bool) (string, error) {
	if f := beforeWrite.Load(); f != nil {
		(*f)(dst)
	}
	dir := filepath.Dir(dst)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	if !overwrite {
		if _, err := os.Lstat(dst); err == nil {
			return "", ErrExists
		}
	}
	f, err := os.CreateTemp(dir, TmpPrefix+"*")
	if err != nil {
		return "", err
	}
	h := sha256.New()
	_, err = io.Copy(io.MultiWriter(f, h), r)
	if err == nil {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(f.Name(), dst)
	}
	if err != nil {
		os.Remove(f.Name())
		return "", err
	}
	if d, err := os.Open(dir); err == nil { // make the rename durable; fails harmlessly on Windows
		d.Sync()
		d.Close()
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
