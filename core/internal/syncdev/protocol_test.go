package syncdev

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"styli/core/internal/syncsrv"
)

// asBuild sets the protocol this device speaks for the duration of a test.
func asBuild(t *testing.T, p int) {
	t.Helper()
	old := protocolHere
	protocolHere = p
	t.Cleanup(func() { protocolHere = old })
}

// An atrium that is newer than the device syncs with it (older devices keep
// working with a newer atrium).
func TestAnAtriumNewerThanTheDeviceSyncs(t *testing.T) {
	a := newAtriumProtocol(t, syncsrv.Protocol+1)
	d := a.deviceWith(t, nil)
	g := must(d.v.CreateNote("Old.md", []byte("from an older device\n")))
	noWaiting(t, d.sync(t))
	if _, err := a.v.Get(g.ID); err != nil {
		t.Fatalf("the note did not reach the newer atrium: %v", err)
	}
}

// A device newer than the atrium sends nothing, and says why. Once the atrium
// is updated the same device syncs, with nothing lost.
func TestADeviceNewerThanTheAtriumPausesAndResumes(t *testing.T) {
	a := newAtrium(t) // protocol syncsrv.Protocol
	d := a.deviceWith(t, nil)
	asBuild(t, syncsrv.Protocol+1)
	g := must(d.v.CreateNote("New.md", []byte("written by a newer build\n")))

	before := d.net.requests()
	err := d.e.Sync(context.Background())
	if !errors.Is(err, ErrNewerDevice) || !strings.Contains(err.Error(), "Update the atrium") {
		t.Fatalf("want a pause that says to update the atrium, got %v", err)
	}
	if n := d.net.requests() - before; n != 1 {
		t.Fatalf("the paused pass made %d requests, want only the hello", n)
	}
	if _, err := a.v.Get(g.ID); err == nil {
		t.Fatal("a paused device sent a note")
	}
	if got, _ := os.ReadFile(filepath.Join(d.root, "New.md")); string(got) != "written by a newer build\n" {
		t.Fatal("the paused device lost its note")
	}

	asBuild(t, syncsrv.Protocol) // the atrium is updated: the same device now syncs
	noWaiting(t, d.sync(t))
	if _, err := a.v.Get(g.ID); err != nil {
		t.Fatalf("after the update the note did not reach the atrium: %v", err)
	}
}

// Clearing a copy from a newer device is refused before anything is removed.
func TestClearIsRefusedByANewerDevice(t *testing.T) {
	a := newAtrium(t)
	d := a.deviceWith(t, nil)
	g := must(d.v.CreateNote("Kept.md", []byte("synced first\n")))
	noWaiting(t, d.sync(t))

	asBuild(t, syncsrv.Protocol+1)
	if err := d.e.Clear(context.Background(), g.ID); !errors.Is(err, ErrNewerDevice) {
		t.Fatalf("clear by a newer device: %v", err)
	}
	if _, err := d.v.Get(g.ID); err != nil {
		t.Fatal("the refused clear removed the note")
	}
}

// The rule itself, for the cases the passes above do not reach.
func TestCheckProtocol(t *testing.T) {
	asBuild(t, 3)
	for atrium, wantErr := range map[int]bool{0: true, 2: true, 3: false, 4: false, 99: false} {
		if err := checkProtocol(atrium); (err != nil) != wantErr {
			t.Errorf("device 3, atrium %d: err %v", atrium, err)
		}
	}
}
