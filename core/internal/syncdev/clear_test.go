package syncdev

import (
	"context"
	"errors"
	"strings"
	"testing"

	"styli/core/internal/vault"
)

// Safe clearing: "Delete from this device only" (see Engine.Clear).

// synced makes an atrium and two devices that both hold one synced note.
func synced(t *testing.T) (*atrium, *device, *device, vault.Group) {
	t.Helper()
	a := newAtrium(t)
	d1, d2 := a.deviceWith(t, nil), a.deviceWith(t, nil)
	g := must(d1.v.CreateNote("n.md", []byte("n\n")))
	d1.sync(t)
	d2.sync(t)
	return a, d1, d2, g
}

func cleared(t *testing.T, d *device, id string) bool {
	t.Helper()
	b := must(d.e.rec.base(id))
	return b != nil && b.Cleared
}

// A cleared copy goes to this device's trash; the atrium and the other device
// keep the note, and later changes there are not pulled here.
func TestClearThisDeviceOnly(t *testing.T) {
	a, d1, d2, g := synced(t)
	if err := d1.e.Clear(context.Background(), g.ID); err != nil {
		t.Fatal(err)
	}
	if live(d1.v, g.ID) || !inTrash(t, d1.v, g.ID) || !cleared(t, d1, g.ID) {
		t.Fatal("not cleared to this device's trash")
	}
	noWaiting(t, d1.sync(t))
	noWaiting(t, d2.sync(t))
	if text(t, a.v, g.ID) != "n\n" || text(t, d2.v, g.ID) != "n\n" {
		t.Fatal("clearing reached another machine")
	}

	appendTo(t, d2.v, g.ID, "from d2\n")
	d2.sync(t)
	renameOnAtrium(t, a.v, g.ID, "renamed.md")
	noWaiting(t, d1.sync(t))
	if live(d1.v, g.ID) {
		t.Fatal("a cleared note was pulled back")
	}
	settle(t, a, d1, d2)
}

// Restoring a cleared copy from this device's trash brings it back in step:
// the atrium's newer version is pulled, or this device's edit is pushed.
func TestClearedCopyComesBack(t *testing.T) {
	t.Run("atrium changed", func(t *testing.T) {
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		appendTo(t, d2.v, g.ID, "newer\n")
		d2.sync(t)
		d1.sync(t)
		must(0, d1.v.Restore(g.ID, ""))
		noWaiting(t, d1.sync(t))
		if text(t, d1.v, g.ID) != "n\nnewer\n" || cleared(t, d1, g.ID) {
			t.Fatalf("not pulled: %q", text(t, d1.v, g.ID))
		}
		settle(t, a, d1, d2)
	})
	t.Run("unchanged everywhere", func(t *testing.T) {
		// Nothing to pull or push, but the mark must still go: a delete
		// everywhere made here afterwards has to reach the atrium.
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		must(0, d1.v.Restore(g.ID, ""))
		noWaiting(t, d1.sync(t))
		if cleared(t, d1, g.ID) {
			t.Fatal("still marked cleared")
		}
		d1.v.Trash(g.ID)
		noWaiting(t, d1.sync(t))
		if live(a.v, g.ID) {
			t.Fatal("the delete everywhere did not reach the atrium")
		}
		settle(t, a, d1, d2)
	})
	t.Run("restored atrium without it", func(t *testing.T) {
		// The atrium came back from a backup older than the note: the copy
		// restored here is sent to it again as new.
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		must(0, d1.v.Restore(g.ID, ""))
		d1.e.rec.saveCursor(Cursor{Epoch: "old", Since: 999})
		a.v.Trash(g.ID)
		a.v.DeleteForever(g.ID)
		noWaiting(t, d1.sync(t))
		if text(t, a.v, g.ID) != "n\n" || !live(d1.v, g.ID) || cleared(t, d1, g.ID) {
			t.Fatal("not sent again")
		}
		settle(t, a, d1, d2)
	})
	t.Run("edited here", func(t *testing.T) {
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		must(0, d1.v.Restore(g.ID, ""))
		appendTo(t, d1.v, g.ID, "mine\n")
		noWaiting(t, d1.sync(t))
		if text(t, a.v, g.ID) != "n\nmine\n" {
			t.Fatal("not pushed")
		}
		settle(t, a, d1, d2)
	})
	t.Run("both changed", func(t *testing.T) {
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		write(t, d2.v, g.ID, "theirs\n")
		d2.sync(t)
		must(0, d1.v.Restore(g.ID, ""))
		write(t, d1.v, g.ID, "mine\n")
		noWaiting(t, d1.sync(t))
		got := text(t, a.v, g.ID)
		if !must(a.v.Get(g.ID)).Conflict || !strings.Contains(got, "mine") || !strings.Contains(got, "theirs") {
			t.Fatalf("not merged: %q", got)
		}
		settle(t, a, d1, d2)
	})
	t.Run("deleted everywhere meanwhile", func(t *testing.T) {
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		d2.v.Trash(g.ID)
		d2.sync(t)
		d1.sync(t)
		must(0, d1.v.Restore(g.ID, ""))
		noWaiting(t, d1.sync(t))
		if live(d1.v, g.ID) || !inTrash(t, d1.v, g.ID) || cleared(t, d1, g.ID) {
			t.Fatal("the delete everywhere did not stand")
		}
		settle(t, a, d1, d2)
	})
	t.Run("emptied from the atrium's trash meanwhile", func(t *testing.T) {
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		d2.v.Trash(g.ID)
		d2.sync(t)
		a.v.DeleteForever(g.ID)
		must(0, d1.v.Restore(g.ID, ""))
		noWaiting(t, d1.sync(t))
		if live(d1.v, g.ID) || !inTrash(t, d1.v, g.ID) || must(d1.e.rec.base(g.ID)).Deleted != forgottenDelete {
			t.Fatal("not treated as a forgotten delete")
		}
		settle(t, a, d1, d2)
	})
	t.Run("emptied, but edited here", func(t *testing.T) {
		a, d1, d2, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		d2.v.Trash(g.ID)
		d2.sync(t)
		a.v.DeleteForever(g.ID)
		must(0, d1.v.Restore(g.ID, ""))
		write(t, d1.v, g.ID, "edited after\n")
		noWaiting(t, d1.sync(t))
		if txt, _ := candidate(t, a); txt != "edited after\n" {
			t.Fatalf("candidate %q", txt)
		}
		settle(t, a, d1, d2)
	})
}

// Clear refuses, and changes nothing anywhere, unless this device's copy is
// what both last agreed on and the atrium confirms it holds exactly that.
func TestClearRefuses(t *testing.T) {
	cases := []struct {
		name   string
		do     func(a *atrium, d *device, g vault.Group)
		want   error
		atrium bool // the change is on the atrium
	}{
		{"edited here, not pushed", func(a *atrium, d *device, g vault.Group) { appendTo(t, d.v, g.ID, "x\n") }, ErrUnsynced, false},
		{"edited here outside the app", func(a *atrium, d *device, g vault.Group) { onDisk(t, d.v, g.ID, "x\n") }, ErrUnsynced, false},
		{"moved here, not pushed", func(a *atrium, d *device, g vault.Group) { must(0, d.v.Move(g.ID, "F")) }, ErrUnsynced, false},
		{"newer on the atrium", func(a *atrium, d *device, g vault.Group) { appendTo(t, a.v, g.ID, "x\n") }, ErrAtriumDiffers, true},
		{"edited outside the app on the atrium", func(a *atrium, d *device, g vault.Group) { onDisk(t, a.v, g.ID, "x\n") }, ErrAtriumDiffers, true},
		{"deleted on the atrium", func(a *atrium, d *device, g vault.Group) { a.v.Trash(g.ID) }, ErrAtriumDiffers, true},
		{"emptied on the atrium", func(a *atrium, d *device, g vault.Group) { a.v.Trash(g.ID); a.v.DeleteForever(g.ID) }, ErrAtriumDiffers, true},
		{"offline", func(a *atrium, d *device, g vault.Group) { d.net.down = true }, ErrUnreachable, false},
		{"request dropped", func(a *atrium, d *device, g vault.Group) { d.net.reset(); d.net.dropReq = 1 }, ErrUnreachable, false},
		{"reply lost", func(a *atrium, d *device, g vault.Group) { d.net.reset(); d.net.dropReply = 1 }, ErrUnreachable, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			a, d1, _, g := synced(t)
			before := tree(t, a.v.Root())
			c.do(a, d1, g)
			mine := tree(t, d1.root)
			if err := d1.e.Clear(context.Background(), g.ID); !errors.Is(err, c.want) {
				t.Fatalf("got %v, want %v", err, c.want)
			}
			if !live(d1.v, g.ID) || cleared(t, d1, g.ID) || !equal(tree(t, d1.root), mine) {
				t.Fatal("refused, but the copy here changed")
			}
			if !c.atrium && !equal(tree(t, a.v.Root()), before) {
				t.Fatal("the atrium changed")
			}
		})
	}
	t.Run("edited here while the atrium was asked", func(t *testing.T) {
		a, d1, d2, g := synced(t)
		d1.net.onRequest = func() { d1.net.onRequest = nil; appendTo(t, d1.v, g.ID, "typed meanwhile\n") }
		if err := d1.e.Clear(context.Background(), g.ID); !errors.Is(err, ErrUnsynced) {
			t.Fatalf("%v", err)
		}
		if !live(d1.v, g.ID) || cleared(t, d1, g.ID) {
			t.Fatal("the copy edited meanwhile was cleared, or left marked")
		}
		noWaiting(t, d1.sync(t))
		if text(t, a.v, g.ID) != "n\ntyped meanwhile\n" {
			t.Fatal("the edit made meanwhile did not sync")
		}
		settle(t, a, d1, d2)
	})
	t.Run("never synced", func(t *testing.T) {
		a := newAtrium(t)
		d := a.device(t)
		g := must(d.v.CreateNote("new.md", []byte("new")))
		if err := d.e.Clear(context.Background(), g.ID); !errors.Is(err, ErrUnsynced) || !live(d.v, g.ID) {
			t.Fatalf("%v", err)
		}
	})
	t.Run("already cleared", func(t *testing.T) {
		_, d1, _, g := synced(t)
		must(0, d1.e.Clear(context.Background(), g.ID))
		if err := d1.e.Clear(context.Background(), g.ID); !errors.Is(err, vault.ErrNotFound) {
			t.Fatalf("%v", err)
		}
	})
}

// A crash between marking the base and moving the copy, then a delete
// everywhere before any sync: the delete must still reach the atrium.
func TestDeleteAfterInterruptedClear(t *testing.T) {
	a, d1, d2, g := synced(t)
	d1.writes(2) // the base mark is write 1; stop before the copy moves
	if crashedNow, _ := d1.tryClear(g.ID); !crashedNow || !live(d1.v, g.ID) || !cleared(t, d1, g.ID) {
		t.Fatal("the crash did not land between the mark and the move")
	}
	must(0, d1.v.Trash(g.ID))
	noWaiting(t, d1.sync(t))
	if live(a.v, g.ID) || !inTrash(t, a.v, g.ID) {
		t.Fatal("the delete everywhere was taken for a clear")
	}
	settle(t, a, d1, d2)
}

// A crash before any file write Clear makes, then a restart and sync: the
// note is either still here and syncing, or cleared, and the atrium always
// holds it unchanged. An atrium edit made meanwhile reaches a copy left here.
func TestClearCrashes(t *testing.T) {
	for k := 1; ; k++ {
		a, d1, d2, g := synced(t)
		d1.writes(k)
		crashedNow, err := d1.tryClear(g.ID)
		if !crashedNow {
			if err != nil {
				t.Fatalf("crash point %d: %v", k, err)
			}
			if k == 1 {
				t.Fatal("Clear wrote nothing")
			}
			t.Logf("%d crash points", k-1)
			return // past the last write
		}
		appendTo(t, d2.v, g.ID, "after\n")
		d2.sync(t)
		noWaiting(t, d1.sync(t))
		if text(t, a.v, g.ID) != "n\nafter\n" {
			t.Fatalf("crash point %d: the atrium changed", k)
		}
		if live(d1.v, g.ID) {
			if cleared(t, d1, g.ID) || text(t, d1.v, g.ID) != "n\nafter\n" {
				t.Fatalf("crash point %d: the copy left here is not in step", k)
			}
		} else if !cleared(t, d1, g.ID) || !inTrash(t, d1.v, g.ID) {
			t.Fatalf("crash point %d: gone here without being cleared", k)
		}
		settle(t, a, d1, d2)
	}
}
