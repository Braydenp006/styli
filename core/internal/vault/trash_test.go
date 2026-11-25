package vault

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func exists(root, rel string) bool {
	_, err := os.Lstat(filepath.Join(root, filepath.FromSlash(rel)))
	return err == nil
}

func group(t *testing.T, v *Vault) Group {
	t.Helper()
	g := must(v.CreateNote("a/n.md", []byte("# Note\nsample")))
	return must(v.AddFile(g.ID, "src.pdf", strings.NewReader("%PDF-sample")))
}

func TestTrashMovesWholeGroupAndRestoreBringsItBack(t *testing.T) {
	v, root := open(t)
	g := group(t, v)
	if err := v.Trash(g.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Get(g.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("still live: %v", err)
	}
	if len(must(v.List())) != 0 {
		t.Fatal("trashed group still listed")
	}
	if exists(root, "a/n.md") || exists(root, "a/src.pdf") {
		t.Fatal("files left in the vault")
	}
	tr := must(v.ListTrash())
	if len(tr) != 1 || tr[0].Group.ID != g.ID || tr[0].Folder != "a" || tr[0].DeletedAt.IsZero() {
		t.Fatalf("trash: %+v", tr)
	}
	if err := v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	b, _, err := v.ReadNote(g.ID)
	if err != nil || string(b) != "# Note\nsample" {
		t.Fatalf("restored note: %q %v", b, err)
	}
	if pdf, _ := os.ReadFile(filepath.Join(root, "a/src.pdf")); string(pdf) != "%PDF-sample" {
		t.Fatal("pdf content changed")
	}
	if len(must(v.ListTrash())) != 0 {
		t.Fatal("restored entry still in trash")
	}
}

func TestRestoreToAnotherFolder(t *testing.T) {
	v, root := open(t)
	g := group(t, v)
	v.Trash(g.ID)
	if err := v.Restore(g.ID, "elsewhere/deep"); err != nil {
		t.Fatal(err)
	}
	got := must(v.Get(g.ID))
	if got.Note.Path != "elsewhere/deep/n.md" || got.Files[0].Path != "elsewhere/deep/src.pdf" || !exists(root, "elsewhere/deep/src.pdf") {
		t.Fatalf("%+v", got)
	}
}

func TestScanAfterTrashSeesNothing(t *testing.T) {
	v, _ := open(t)
	g := group(t, v)
	v.Trash(g.ID)
	if c := rescan(t, v); len(c) != 0 {
		t.Fatalf("got %+v", c)
	}
	if m := must(v.Missing()); len(m) != 0 {
		t.Fatalf("missing: %+v", m)
	}
}

func TestRestoreCollisionMovesNothing(t *testing.T) {
	v, root := open(t)
	g := group(t, v)
	v.Trash(g.ID)
	must(v.ImportFile("a/src.pdf", strings.NewReader("someone else's")))
	if err := v.Restore(g.ID, ""); !errors.Is(err, ErrExists) {
		t.Fatalf("want ErrExists, got %v", err)
	}
	if exists(root, "a/n.md") {
		t.Fatal("note restored although the restore was refused")
	}
	if len(must(v.ListTrash())) != 1 {
		t.Fatal("entry left the trash")
	}
}

func TestTrashGroupWithMissingFile(t *testing.T) {
	v, root := open(t)
	g := group(t, v)
	os.Remove(filepath.Join(root, "a/src.pdf"))
	if err := v.Trash(g.ID); err != nil {
		t.Fatal(err)
	}
	if tr := must(v.ListTrash()); len(tr) != 1 || len(tr[0].Group.Files) != 1 {
		t.Fatalf("record lost the missing file: %+v", tr)
	}
	if err := v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	m := must(v.Missing())
	if len(m) != 1 || m[0].Paths[0] != "a/src.pdf" || !exists(root, "a/n.md") {
		t.Fatalf("missing: %+v", m)
	}
}

func TestTrashPDFFirstGroup(t *testing.T) {
	v, root := open(t)
	g := must(v.ImportFile("p/slides.pdf", strings.NewReader("pdf")))
	if err := v.Trash(g.ID); err != nil {
		t.Fatal(err)
	}
	if exists(root, "p/slides.pdf") {
		t.Fatal("pdf left behind")
	}
	if err := v.Restore(g.ID, ""); err != nil || !exists(root, "p/slides.pdf") {
		t.Fatalf("restore: %v", err)
	}
}

func TestInterruptedTrashIsRestorableAndRetryable(t *testing.T) {
	v, root := open(t)
	g := group(t, v)
	// Crash after the PDF moved into trash but before the live record went.
	fdir := filepath.Join(v.trashPath(g.ID), "files")
	os.MkdirAll(fdir, 0o755)
	os.Rename(filepath.Join(root, "a/src.pdf"), filepath.Join(fdir, "src.pdf"))
	if err := v.Trash(g.ID); err != nil { // the retry finishes the job
		t.Fatal(err)
	}
	if err := v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	if !exists(root, "a/n.md") || !exists(root, "a/src.pdf") {
		t.Fatal("something was lost across the interrupted trash")
	}
}

func TestRestoreWorksWhenLiveRecordStillExists(t *testing.T) {
	v, root := open(t)
	g := group(t, v)
	v.Trash(g.ID)
	rec, _ := v.readTrash(g.ID)
	v.save(rec.Group) // the "crashed before removing the live record" state
	if err := v.Restore(g.ID, ""); err != nil {
		t.Fatal(err)
	}
	if _, _, err := v.ReadNote(g.ID); err != nil || !exists(root, "a/src.pdf") {
		t.Fatalf("restore: %v", err)
	}
}

func age(t *testing.T, v *Vault, id string, by time.Duration) {
	t.Helper()
	p := filepath.Join(v.trashPath(id), "record.json")
	var m map[string]any
	if err := json.Unmarshal(must(os.ReadFile(p)), &m); err != nil {
		t.Fatal(err)
	}
	m["deleted_at"] = time.Now().Add(-by).UTC().Format(time.RFC3339Nano)
	if err := os.WriteFile(p, must(json.Marshal(m)), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestPurgeRemovesOnlyOldEntries(t *testing.T) {
	v, root := open(t)
	old := group(t, v)
	fresh := must(v.CreateNote("b/keep.md", []byte("fresh")))
	live := must(v.CreateNote("c/live.md", []byte("live")))
	v.Trash(old.ID)
	v.Trash(fresh.ID)
	age(t, v, old.ID, 40*24*time.Hour)

	n, err := v.Purge(time.Now().Add(-30 * 24 * time.Hour))
	if err != nil || n != 1 {
		t.Fatalf("purged %d, %v", n, err)
	}
	tr := must(v.ListTrash())
	if len(tr) != 1 || tr[0].Group.ID != fresh.ID {
		t.Fatalf("trash: %+v", tr)
	}
	if _, err := os.Stat(v.trashPath(old.ID)); !os.IsNotExist(err) {
		t.Fatal("purged entry still on disk")
	}
	if b, _, err := v.ReadNote(live.ID); err != nil || string(b) != "live" || !exists(root, "c/live.md") {
		t.Fatal("purge touched the live vault")
	}
	if n, _ := v.Purge(time.Now().Add(-30 * 24 * time.Hour)); n != 0 {
		t.Fatalf("second purge removed %d", n)
	}
}

func TestPurgeSkipsEntryThatIsAlsoLive(t *testing.T) {
	v, root := open(t)
	g := group(t, v)
	v.Trash(g.ID)
	rec, _ := v.readTrash(g.ID)
	v.save(rec.Group) // interrupted Trash: live record plus trash entry
	age(t, v, g.ID, 90*24*time.Hour)
	if n, err := v.Purge(time.Now()); n != 0 || err != nil {
		t.Fatalf("purged %d, %v", n, err)
	}
	if err := v.Restore(g.ID, ""); err != nil || !exists(root, "a/n.md") {
		t.Fatalf("data was purged: %v", err)
	}
}

func TestTrashBadIDsAndStrayFolders(t *testing.T) {
	v, root := open(t)
	for _, id := range []string{"", "../x", "trash", strings.Repeat("a", 26)} {
		if err := v.Trash(id); !errors.Is(err, ErrNotFound) {
			t.Errorf("Trash %q: %v", id, err)
		}
		if err := v.Restore(id, ""); !errors.Is(err, ErrNotFound) {
			t.Errorf("Restore %q: %v", id, err)
		}
	}
	os.MkdirAll(filepath.Join(root, ".ids/trash/not-an-id/files"), 0o755)
	os.MkdirAll(filepath.Join(root, ".ids/trash", strings.Repeat("b", 26)), 0o755) // no record
	if tr, err := v.ListTrash(); err != nil || len(tr) != 0 {
		t.Fatalf("%+v %v", tr, err)
	}
	if n, err := v.Purge(time.Now()); err != nil || n != 0 {
		t.Fatalf("%d %v", n, err)
	}
	if !exists(root, ".ids/trash/not-an-id/files") {
		t.Fatal("purge deleted a folder that is not a trash entry")
	}
}
