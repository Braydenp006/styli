package syncdev

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// bigFile is n bytes of sample data that is the same on every run.
func bigFile(n int) []byte {
	var b bytes.Buffer
	for i := 0; b.Len() < n; i++ {
		fmt.Fprintf(&b, "sample line %d of a large attached file\n", i)
	}
	return b.Bytes()[:n]
}

// bigWorld has a note with a large attached file on the atrium and on d1; d2
// has not synced yet. It returns the file's hash and bytes.
func bigWorld(t *testing.T) (w *world, hash string, data []byte) {
	t.Helper()
	w = newWorld(t)
	data = bigFile(1 << 20)
	g := must(w.d1.v.CreateNote("Big.md", []byte("big\n")))
	must(w.d1.v.AddFile(g.ID, "scan.bin", bytes.NewReader(data)))
	w.d1.sync(t)
	sum := sha256.Sum256(data)
	return w, hex.EncodeToString(sum[:]), data
}

func partFile(d *device, hash string) string {
	return filepath.Join(d.root, ".ids", "sync", "blobs", hash+".part")
}

// cutOnce makes d2's sync lose its connection 300,000 bytes into the file.
func cutOnce(t *testing.T, w *world, hash string) {
	t.Helper()
	w.d2.net.cutFiles = 300000
	if _, err := w.d2.trySync(); err != nil {
		t.Logf("cut, as planned: %v", err)
	}
	w.d2.net.cutFiles = 0
	if n := must(w.d2.v.BlobPartSize(hash)); n != 300000 {
		t.Fatalf("part is %d bytes, want 300000", n)
	}
	if _, ok := tree(t, w.d2.root)["scan.bin"]; ok {
		t.Fatal("a half-downloaded file was written into the vault")
	}
}

func finished(t *testing.T, w *world, hash string, data []byte) {
	t.Helper()
	noWaiting(t, w.d2.sync(t))
	settle(t, w.a, w.d1, w.d2)
	if got, _ := os.ReadFile(filepath.Join(w.d2.root, "scan.bin")); !bytes.Equal(got, data) {
		t.Fatalf("scan.bin is %d bytes, want %d, or differs", len(got), len(data))
	}
	if _, err := os.Stat(partFile(w.d2, hash)); !os.IsNotExist(err) {
		t.Fatalf("part file left behind: %v", err)
	}
}

// A download that is cut carries on from the bytes already staged.
func TestDownloadResumesWhereItStopped(t *testing.T) {
	w, hash, data := bigWorld(t)
	cutOnce(t, w, hash)
	w.d2.net.ranges = nil
	finished(t, w, hash, data)
	if r := w.d2.net.ranges; len(r) != 1 || r[0] != "bytes=300000-" {
		t.Fatalf("ranges asked for: %v", r)
	}
}

// An atrium that sends the whole file anyway does no harm.
func TestResumeAgainstAnAtriumThatIgnoresRange(t *testing.T) {
	w, hash, data := bigWorld(t)
	cutOnce(t, w, hash)
	w.d2.net.noRange = true
	finished(t, w, hash, data)
}

// A part that is damaged is thrown away when the hash check fails, and the next
// pass fetches the file again from the start.
func TestResumeWithADamagedPart(t *testing.T) {
	w, hash, data := bigWorld(t)
	cutOnce(t, w, hash)
	bad := bytes.Repeat([]byte{'x'}, 300000)
	if err := os.WriteFile(partFile(w.d2, hash), bad, 0o600); err != nil {
		t.Fatal(err)
	}
	if s := w.d2.sync(t); len(s.Waiting) != 1 || !strings.Contains(s.Waiting[0].Reason, "damaged") {
		t.Fatalf("want one waiting note, damaged: %+v", s.Waiting)
	}
	if n := must(w.d2.v.BlobPartSize(hash)); n != 0 {
		t.Fatalf("the damaged part was kept: %d bytes", n)
	}
	w.d2.net.ranges = nil
	finished(t, w, hash, data)
	if len(w.d2.net.ranges) != 0 {
		t.Fatalf("the second try asked for a range: %v", w.d2.net.ranges)
	}
}

// A part as long as the whole file (a crash after the last byte, before the
// check) gets 416 from the atrium; the device starts again.
func TestResumeWithAPartAsLongAsTheFile(t *testing.T) {
	w, hash, data := bigWorld(t)
	cutOnce(t, w, hash)
	if err := os.WriteFile(partFile(w.d2, hash), data, 0o600); err != nil {
		t.Fatal(err)
	}
	finished(t, w, hash, data)
}

// A new version of the file while a part of the old one is waiting never mixes
// the two: the parts are named by hash.
func TestResumeAfterTheFileChanged(t *testing.T) {
	w, hash, _ := bigWorld(t)
	cutOnce(t, w, hash)
	newer := append(bigFile(1<<20), []byte("changed")...)
	// Edited in another app, as a person would, then picked up by the scan.
	if err := os.WriteFile(filepath.Join(w.d1.root, "scan.bin"), newer, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := w.d1.v.Rescan(); err != nil {
		t.Fatal(err)
	}
	w.d1.sync(t)
	noWaiting(t, w.d2.sync(t))
	settle(t, w.a, w.d1, w.d2)
	if got, _ := os.ReadFile(filepath.Join(w.d2.root, "scan.bin")); !bytes.Equal(got, newer) {
		t.Fatalf("scan.bin is not the newer version (%d bytes)", len(got))
	}
}

// ---- uploads ----

type lostConn struct{}

func (lostConn) Read([]byte) (int, error) { return 0, errDropped }

// bigUpload is a device with a new note and a large attached file nobody else
// has, with uploads resumable from 64 KiB.
func bigUpload(t *testing.T) (w *world, hash string, data []byte) {
	t.Helper()
	old := ResumeAbove
	ResumeAbove = 64 << 10
	t.Cleanup(func() { ResumeAbove = old })
	w = newWorld(t)
	data = bigFile(1 << 20)
	g := must(w.d1.v.CreateNote("Big.md", []byte("big\n")))
	must(w.d1.v.AddFile(g.ID, "scan.bin", bytes.NewReader(data)))
	sum := sha256.Sum256(data)
	return w, hex.EncodeToString(sum[:]), data
}

// stagedOnAtrium waits until the atrium has stopped writing the part (it may
// still be reading a request the device cut off) and returns its size.
func stagedOnAtrium(t *testing.T, w *world, hash string) int64 {
	t.Helper()
	last, same := int64(-1), 0
	for i := 0; i < 200 && same < 4; i++ {
		n := must(w.a.v.BlobPartSize(hash))
		if n == last {
			same++
		} else {
			last, same = n, 0
		}
		time.Sleep(10 * time.Millisecond)
	}
	return last
}

// fileUploads is the uploads that were the large file's (the note's own small
// text goes up whole, in one request, as always).
func fileUploads(d *device) []string {
	var out []string
	for _, p := range d.net.puts {
		if p != "whole" {
			out = append(out, p)
		}
	}
	return out
}

func atriumHas(t *testing.T, w *world, data []byte) {
	t.Helper()
	settle(t, w.a, w.d1, w.d2)
	if got, _ := os.ReadFile(filepath.Join(w.a.v.Root(), "scan.bin")); !bytes.Equal(got, data) {
		t.Fatalf("the atrium's scan.bin is %d bytes, want %d, or differs", len(got), len(data))
	}
}

// An upload that is cut off carries on from the bytes the atrium already has.
func TestUploadResumesWhereItStopped(t *testing.T) {
	w, hash, data := bigUpload(t)
	w.d1.net.cutUpload = 300000
	if _, err := w.d1.trySync(); err == nil {
		t.Fatal("the cut upload reported success")
	}
	w.d1.net.cutUpload = 0
	have := stagedOnAtrium(t, w, hash)
	if have <= 0 || have >= int64(len(data)) {
		t.Fatalf("the atrium holds %d bytes of %d", have, len(data))
	}
	w.d1.net.puts = nil
	noWaiting(t, w.d1.sync(t))
	want := fmt.Sprintf("bytes %d-%d/%d", have, len(data)-1, len(data))
	if p := fileUploads(w.d1); len(p) != 1 || p[0] != want {
		t.Fatalf("uploads: %v, want [%s]", p, want)
	}
	atriumHas(t, w, data)
}

// A blob the atrium already holds is not sent again.
func TestUploadSkipsABlobTheAtriumHolds(t *testing.T) {
	w, hash, data := bigUpload(t)
	if err := w.a.v.StageBlob(hash, bytes.NewReader(data), 1<<30); err != nil {
		t.Fatal(err)
	}
	noWaiting(t, w.d1.sync(t))
	if p := fileUploads(w.d1); len(p) != 0 {
		t.Fatalf("uploaded again: %v", p)
	}
	atriumHas(t, w, data)
}

// A part on the atrium that is not the start of this file is thrown away when
// the hash check fails, and the next pass sends the file from the start.
func TestUploadOntoAWrongPart(t *testing.T) {
	w, hash, data := bigUpload(t)
	junk := io.MultiReader(bytes.NewReader(bytes.Repeat([]byte{'x'}, 100000)), lostConn{})
	if err := w.a.v.StageBlobAt(hash, 0, junk, 1<<30); err == nil {
		t.Fatal("want the junk part to be cut")
	}
	if s := w.d1.sync(t); len(s.Waiting) != 1 {
		t.Fatalf("want one note waiting: %+v", s.Waiting)
	}
	if n := must(w.a.v.BlobPartSize(hash)); n != 0 {
		t.Fatalf("the wrong part was kept: %d bytes", n)
	}
	noWaiting(t, w.d1.sync(t))
	atriumHas(t, w, data)
}

// A part as long as the file, or longer, cannot be carried on: the device sends
// the whole file.
func TestUploadOntoAPartAsLongAsTheFile(t *testing.T) {
	w, hash, data := bigUpload(t)
	junk := io.MultiReader(bytes.NewReader(bytes.Repeat([]byte{'x'}, 2<<20)), lostConn{})
	if err := w.a.v.StageBlobAt(hash, 0, junk, 1<<30); err == nil {
		t.Fatal("want the junk part to be cut")
	}
	noWaiting(t, w.d1.sync(t))
	if want, p := fmt.Sprintf("bytes 0-%d/%d", len(data)-1, len(data)), fileUploads(w.d1); len(p) != 1 || p[0] != want {
		t.Fatalf("uploads: %v, want [%s]", p, want)
	}
	atriumHas(t, w, data)
}

// Small files still go up in one request, with no question first.
func TestSmallUploadsAreOneRequest(t *testing.T) {
	w := newWorld(t)
	g := must(w.d1.v.CreateNote("S.md", []byte("small\n")))
	must(w.d1.v.AddFile(g.ID, "pic.png", strings.NewReader("PNG")))
	noWaiting(t, w.d1.sync(t))
	for _, p := range w.d1.net.puts {
		if p != "whole" {
			t.Fatalf("a small upload used %q", p)
		}
	}
}
