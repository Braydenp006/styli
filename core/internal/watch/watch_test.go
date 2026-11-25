package watch

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fsnotify/fsnotify"

	"styli/core/internal/store"
	"styli/core/internal/vault"
)

type env struct {
	v       *vault.Vault
	s       *store.Store
	root    string
	changes chan []vault.Change
}

// start runs the watcher and returns once its first scan is done. The watcher
// stops, and is waited for, when the test ends.
func start(t *testing.T, o Options) *env {
	t.Helper()
	oldSettle := vault.Settle
	vault.Settle = 50 * time.Millisecond
	root := t.TempDir()
	v, err := vault.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	s, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	e := &env{v, s, root, make(chan []vault.Change, 20)}
	ready := make(chan struct{})
	o.OnChange = func(c []vault.Change) { e.changes <- c }
	o.Ready = func() { close(ready) }
	o.Logf = t.Logf
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); Run(ctx, v, s, o) }()
	t.Cleanup(func() {
		cancel()
		<-done
		s.Close()
		vault.Settle = oldSettle
	})
	select {
	case <-ready:
	case <-time.After(5 * time.Second):
		t.Fatal("watcher did not start")
	}
	return e
}

func (e *env) waitSearch(t *testing.T, q string) {
	t.Helper()
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if h, _ := e.s.Search(q, 5); len(h) == 1 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("%q never appeared in the index", q)
}

func TestEventsIndexOutsideFiles(t *testing.T) {
	// A one-hour safety-net scan: only file events can find the note in time.
	e := start(t, Options{Debounce: 30 * time.Millisecond, Interval: time.Hour})
	dir := filepath.Join(e.root, "later") // a folder created after start must be watched too
	if err := os.Mkdir(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond)
	if err := os.WriteFile(filepath.Join(dir, "n.md"), []byte("# Needlework\nsample"), 0o644); err != nil {
		t.Fatal(err)
	}
	e.waitSearch(t, "needlework")
}

func TestPeriodicScanWorksWithoutFileEvents(t *testing.T) {
	old := newWatcher
	newWatcher = func() (*fsnotify.Watcher, error) { return nil, errors.New("no inotify here") }
	defer func() { newWatcher = old }()
	e := start(t, Options{Interval: 100 * time.Millisecond})
	if err := os.WriteFile(filepath.Join(e.root, "n.md"), []byte("sample marmalade"), 0o644); err != nil {
		t.Fatal(err)
	}
	e.waitSearch(t, "marmalade")
}

func TestMissingFileIsReportedNotDeleted(t *testing.T) {
	e := start(t, Options{Debounce: 30 * time.Millisecond, Interval: time.Hour})
	g, err := e.v.CreateNote("n.md", []byte("sample"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(e.root, "n.md")); err != nil {
		t.Fatal(err)
	}
	deadline := time.After(8 * time.Second)
	for {
		select {
		case cs := <-e.changes:
			for _, c := range cs {
				if c.Kind == vault.WentMissing && c.ID == g.ID {
					if _, err := e.v.Get(g.ID); err != nil {
						t.Fatal("record was deleted")
					}
					return
				}
			}
		case <-deadline:
			t.Fatal("missing file was never reported")
		}
	}
}

func TestMovedFileKeepsIDInIndex(t *testing.T) {
	e := start(t, Options{Debounce: 30 * time.Millisecond, Interval: time.Hour})
	g, _ := e.v.CreateNote("n.md", []byte("sample quince"))
	time.Sleep(100 * time.Millisecond) // let the vault's own write settle
	e.waitSearchAfterSync(t, g.ID, "quince")
	os.Mkdir(filepath.Join(e.root, "moved"), 0o755)
	if err := os.Rename(filepath.Join(e.root, "n.md"), filepath.Join(e.root, "moved", "n.md")); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if h, _ := e.s.Search("quince", 5); len(h) == 1 && h[0].Path == "moved/n.md" && h[0].ID == g.ID {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("move never reached the index with the same ID")
}

// waitSearchAfterSync indexes a group created through the vault, as the app will.
func (e *env) waitSearchAfterSync(t *testing.T, id, q string) {
	t.Helper()
	if err := e.s.Refresh(e.v, id); err != nil {
		t.Fatal(err)
	}
	e.waitSearch(t, q)
}
