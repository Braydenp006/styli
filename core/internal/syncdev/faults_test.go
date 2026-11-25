package syncdev

import (
	"bytes"
	"fmt"
	"strings"
	"testing"

	"styli/core/internal/vault"
)

// world is an atrium and two devices that merge with the stand-in merge.
type world struct {
	t      *testing.T
	a      *atrium
	d1, d2 *device
	id     string // the note most scenarios are about
}

func newWorld(t *testing.T) *world {
	t.Helper()
	w := &world{t: t, a: newAtrium(t)}
	w.d1 = w.a.deviceWith(t, nil)
	w.d2 = w.a.deviceWith(t, nil)
	return w
}

func (w *world) devices() []*device { return []*device{w.d1, w.d2} }

func (w *world) syncAll() {
	for _, d := range w.devices() {
		d.sync(w.t)
	}
}

func appendTo(t *testing.T, v *vault.Vault, id, more string) {
	t.Helper()
	write(t, v, id, text(t, v, id)+more)
}

// A scenario is something that happens while the devices are apart, and what
// every machine must hold once they have all synced.
type scenario struct {
	name  string
	setup func(w *world) // runs, then everyone syncs, with no faults
	act   func(w *world) // the changes made while apart
	want  func(t *testing.T, files map[string]string)
	kept  string // text that must survive in a recovery candidate on the atrium
}

func has(t *testing.T, files map[string]string, p, want string) {
	t.Helper()
	if got, ok := files[p]; !ok || got != want {
		t.Fatalf("%s is %q (present: %v), want %q; files: %v", p, got, ok, want, files)
	}
}

func contains(t *testing.T, files map[string]string, p string, wants ...string) {
	t.Helper()
	for _, want := range wants {
		if !strings.Contains(files[p], want) {
			t.Fatalf("%s lacks %q; files: %v", p, want, files)
		}
	}
}

func plan(w *world) {
	w.id = must(w.d1.v.CreateNote("Plan.md", []byte("line one\n"))).ID
}

var scenarios = []scenario{
	{
		// Everything goes through the resumable paths: ResumeAbove is lowered so
		// even the note's text asks the atrium how much it holds first.
		name: "new note with a large attached file",
		act: func(w *world) {
			old := ResumeAbove
			ResumeAbove = 1
			w.t.Cleanup(func() { ResumeAbove = old })
			g := must(w.d1.v.CreateNote("Scan.md", []byte("scanned\n")))
			must(w.d1.v.AddFile(g.ID, "scan.bin", bytes.NewReader(bigFile(200000))))
		},
		want: func(t *testing.T, f map[string]string) {
			has(t, f, "Scan.md", "scanned\n")
			has(t, f, "scan.bin", string(bigFile(200000)))
		},
	},
	{
		name: "new note with an attached file",
		act: func(w *world) {
			g := must(w.d1.v.CreateNote("Trip.md", []byte("packing list\n")))
			must(w.d1.v.AddFile(g.ID, "map.png", strings.NewReader("PNGDATA")))
		},
		want: func(t *testing.T, f map[string]string) {
			has(t, f, "Trip.md", "packing list\n")
			has(t, f, "map.png", "PNGDATA")
		},
	},
	{
		name:  "edit and move on one device",
		setup: plan,
		act: func(w *world) {
			appendTo(w.t, w.d2.v, w.id, "two\n")
			if err := w.d2.v.Move(w.id, "Work"); err != nil {
				w.t.Fatal(err)
			}
		},
		want: func(t *testing.T, f map[string]string) {
			has(t, f, "Work/Plan.md", "line one\ntwo\n")
			if _, ok := f["Plan.md"]; ok {
				t.Fatal("the old path is still there")
			}
		},
	},
	{
		name:  "edited on both devices",
		setup: plan,
		act: func(w *world) {
			appendTo(w.t, w.d1.v, w.id, "from d1\n")
			appendTo(w.t, w.d2.v, w.id, "from d2\n")
		},
		want: func(t *testing.T, f map[string]string) { contains(t, f, "Plan.md", "line one", "from d1", "from d2") },
	},
	{
		name: "same name on the atrium and a device",
		act: func(w *world) {
			must(w.a.v.CreateNote("Untitled.md", []byte("theirs")))
			must(w.d1.v.CreateNote("Untitled.md", []byte("mine")))
		},
		want: func(t *testing.T, f map[string]string) {
			has(t, f, "Untitled.md", "theirs")
			has(t, f, "Untitled 2.md", "mine")
		},
	},
	{
		name: "the same note made on both devices before they synced",
		act: func(w *world) {
			for _, d := range w.devices() {
				g := must(d.v.CreateNote("Same.md", []byte("same\n")))
				must(d.v.AddFile(g.ID, "pic.png", strings.NewReader("PNG")))
			}
		},
		want: func(t *testing.T, f map[string]string) {
			has(t, f, "Same.md", "same\n")
			if len(f) != 2 {
				t.Fatalf("want one note and its file: %v", f)
			}
		},
	},
	{
		name:  "deleted on one device",
		setup: plan,
		act:   func(w *world) { trashOrFail(w.t, w.d1.v, w.id) },
		want:  gonePlan,
	},
	{
		name:  "deleted on the atrium",
		setup: plan,
		act:   func(w *world) { trashOrFail(w.t, w.a.v, w.id) },
		want:  gonePlan,
	},
	{
		name:  "deleted on both devices",
		setup: plan,
		act: func(w *world) {
			trashOrFail(w.t, w.d1.v, w.id)
			trashOrFail(w.t, w.d2.v, w.id)
		},
		want: gonePlan,
	},
	{
		name:  "edited on a device after the delete",
		setup: plan,
		act: func(w *world) {
			trashOrFail(w.t, w.d1.v, w.id)
			appendTo(w.t, w.d2.v, w.id, "edited offline\n")
		},
		want: gonePlan,
		kept: "edited offline",
	},
	{
		name:  "deleted on a device while edited on the atrium",
		setup: plan,
		act: func(w *world) {
			appendTo(w.t, w.a.v, w.id, "edited there\n")
			trashOrFail(w.t, w.d1.v, w.id)
		},
		want: gonePlan,
		kept: "edited there",
	},
	{
		name: "restored on a device after the delete",
		setup: func(w *world) {
			plan(w)
			w.syncAll()
			trashOrFail(w.t, w.d1.v, w.id)
		},
		act: func(w *world) {
			if err := w.d2.v.Restore(w.id, ""); err != nil {
				w.t.Fatal(err)
			}
		},
		want: func(t *testing.T, f map[string]string) { has(t, f, "Plan.md", "line one\n") },
	},
	{
		name: "names swapped on the atrium",
		setup: func(w *world) {
			w.id = must(w.d1.v.CreateNote("a.md", []byte("A"))).ID
			must(w.d1.v.CreateNote("b.md", []byte("B")))
		},
		act: func(w *world) {
			var other string
			for _, g := range must(w.a.v.List()) {
				if g.ID != w.id {
					other = g.ID
				}
			}
			renameOnAtrium(w.t, w.a.v, w.id, "spare.md")
			renameOnAtrium(w.t, w.a.v, other, "a.md")
			renameOnAtrium(w.t, w.a.v, w.id, "b.md")
		},
		want: func(t *testing.T, f map[string]string) {
			has(t, f, "a.md", "B")
			has(t, f, "b.md", "A")
		},
	},
}

func trashOrFail(t *testing.T, v *vault.Vault, id string) {
	t.Helper()
	if err := v.Trash(id); err != nil {
		t.Fatal(err)
	}
}

func gonePlan(t *testing.T, f map[string]string) {
	if _, ok := f["Plan.md"]; ok {
		t.Fatalf("a deleted note is still there: %v", f)
	}
}

type faultKind string

const (
	dropRequest faultKind = "request dropped"
	dropReply   faultKind = "reply lost"
	cutReply    faultKind = "reply cut off"
	crash       faultKind = "crash before write"
)

// run plays the scenario once. Every device syncs once, in order, and the
// target's pass gets the fault (k-th request or write; 0 = none). Then
// everyone syncs, with no faults, until nothing changes. It returns how many
// requests (or, for crash, device writes) the target's first pass made.
func (sc scenario) run(t *testing.T, target int, kind faultKind, k int) int {
	t.Helper()
	w := newWorld(t)
	if sc.setup != nil {
		sc.setup(w)
		w.syncAll()
	}
	sc.act(w)

	count := 0
	for i, d := range w.devices() {
		if i != target {
			d.sync(t)
			continue
		}
		var writes interface{ Load() int64 }
		switch kind {
		case dropRequest:
			d.net.dropReq = k
		case dropReply:
			d.net.dropReply = k
		case cutReply:
			d.net.cutReply = k
		case crash:
			writes = d.writes(k)
		}
		d.trySync()
		count = d.net.requests()
		d.net.reset()
		if writes != nil {
			count = int(writes.Load())
		}
	}
	settle(t, w.a, w.devices()...)
	sc.want(t, tree(t, w.a.v.Root()))
	if sc.kept != "" {
		if txt, _ := candidate(t, w.a); !strings.Contains(txt, sc.kept) {
			t.Fatalf("candidate %q lacks %q", txt, sc.kept)
		}
	}
	return count
}

// For every request a device's first pass makes, drop it, lose its reply, or
// cut the reply off part way through its body (a download that stops):
// the devices must still end up with the same, right files.
func TestEveryDroppedRequestAndLostReply(t *testing.T) {
	for _, sc := range scenarios {
		t.Run(sc.name, func(t *testing.T) {
			for target := 0; target < 2; target++ {
				// With no fault, run counts the requests the pass makes.
				n := sc.run(t, target, dropRequest, 0)
				if n == 0 {
					t.Fatalf("device %d made no requests", target+1)
				}
				for _, kind := range []faultKind{dropRequest, dropReply, cutReply} {
					for k := 1; k <= n; k++ {
						t.Run(fmt.Sprintf("device %d, %s, request %d of %d", target+1, kind, k, n), func(t *testing.T) {
							sc.run(t, target, kind, k)
						})
					}
				}
			}
		})
	}
}

// For every file a device's first pass writes, crash just before it: the
// device restarts and the devices still end up right.
func TestEveryCrashBeforeAWrite(t *testing.T) {
	for _, sc := range scenarios {
		t.Run(sc.name, func(t *testing.T) {
			for target := 0; target < 2; target++ {
				n := sc.run(t, target, crash, 0)
				for k := 1; k <= n; k++ {
					t.Run(fmt.Sprintf("device %d, crash before write %d of %d", target+1, k, n), func(t *testing.T) {
						sc.run(t, target, crash, k)
					})
				}
			}
		})
	}
}
