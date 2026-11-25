package syncdev

import (
	"fmt"
	"math/rand/v2"
	"strings"
	"testing"

	"styli/core/internal/vault"
)

// Random edits, moves, offline periods, dropped requests and crashes on three
// devices and the atrium, from a fixed seed so a failure can be replayed
// (go test -run 'TestRandomised/seed_7'). Afterwards everyone comes online and
// syncs, and:
//
//  1. every machine holds the same notes (settle checks it);
//  2. every line a user saved is still in a note, live or in a trash, or in
//     some machine's history (conflict markers keep both sides inside the note);
//  3. no deleted note comes back unflagged: a note trashed and never restored
//     is live nowhere, unless a recovery candidate for it was restored.
func TestRandomised(t *testing.T) {
	seeds := 30
	if testing.Short() {
		seeds = 5
	}
	for seed := uint64(1); seed <= uint64(seeds); seed++ {
		t.Run(fmt.Sprintf("seed_%d", seed), func(t *testing.T) { randomRun(t, seed, 120) })
	}
}

func randomRun(t *testing.T, seed uint64, steps int) {
	rng := rand.New(rand.NewPCG(seed, 99))
	a := newAtrium(t)
	devs := make([]*device, 3)
	for i := range devs {
		devs[i] = a.deviceWith(t, nil)
	}
	vaultOf := func(m int) *vault.Vault { // machine 3 is the atrium
		if m == 3 {
			return a.v
		}
		return devs[m].v
	}
	folders := []string{"", "A", "B"}
	deleted := map[string]bool{} // trashed by a user and not restored since
	var saved []string           // every line a user saved, anywhere
	n := 0
	line := func() string {
		n++
		tok := fmt.Sprintf("seed%d-line%d", seed, n)
		saved = append(saved, tok)
		return tok + "\n"
	}
	notes := func(v *vault.Vault) []vault.Group {
		var out []vault.Group
		for _, g := range must(v.List()) {
			if g.Note != nil {
				out = append(out, g)
			}
		}
		return out
	}

	for step := 0; step < steps; step++ {
		m := rng.IntN(4)
		v := vaultOf(m)
		switch op := rng.IntN(11); {
		case op < 2: // create
			p := fmt.Sprintf("n%d.md", step) // names are unique, so no clashes here
			if f := folders[rng.IntN(len(folders))]; f != "" {
				p = f + "/" + p
			}
			must(v.CreateNote(p, []byte(line())))
		case op < 5: // edit
			if gs := notes(v); len(gs) > 0 {
				g := gs[rng.IntN(len(gs))]
				appendTo(t, v, g.ID, line())
			}
		case op == 5: // move
			if gs := notes(v); len(gs) > 0 {
				g := gs[rng.IntN(len(gs))]
				if err := v.Move(g.ID, folders[rng.IntN(len(folders))]); err != nil {
					t.Fatalf("step %d: move: %v", step, err)
				}
			}
		case op == 9: // delete
			if gs := notes(v); len(gs) > 0 {
				g := gs[rng.IntN(len(gs))]
				if err := v.Trash(g.ID); err != nil {
					t.Fatalf("step %d: trash: %v", step, err)
				}
				deleted[g.ID] = true
			}
		case op == 10 && m == 3 && rng.IntN(2) == 0: // restore a recovery candidate
			if cs := must(a.v.ListCandidates()); len(cs) > 0 {
				c := cs[rng.IntN(len(cs))]
				if _, err := a.v.RestoreCandidate(c.Key, ""); err == nil {
					delete(deleted, c.Group.ID)
				}
			}
		case op == 10: // restore
			if ts := must(v.ListTrash()); len(ts) > 0 {
				id := ts[rng.IntN(len(ts))].Group.ID
				if v.Restore(id, "") == nil {
					delete(deleted, id)
				}
			}
		case op == 6: // a device goes offline or comes back
			if m < 3 {
				devs[m].net.down = !devs[m].net.down
			}
		default: // a device syncs, sometimes with a fault
			if m == 3 {
				continue
			}
			d := devs[m]
			switch rng.IntN(6) {
			case 0:
				d.net.dropReq = 1 + rng.IntN(8)
			case 1:
				d.net.dropReply = 1 + rng.IntN(8)
			case 2:
				d.writes(1 + rng.IntN(8))
			}
			d.trySync()
			d.net.reset()
		}
	}

	settle(t, a, devs...)
	vs := []*vault.Vault{a.v}
	for _, d := range devs {
		vs = append(vs, d.v)
	}
	all := everything(t, vs...)
	for _, tok := range saved {
		if !strings.Contains(all, tok+"\n") {
			t.Fatalf("a line a user saved is gone from every note and every history: %s", tok)
		}
	}
	for id := range deleted {
		for _, v := range vs {
			if _, err := v.Get(id); err == nil {
				t.Fatalf("deleted note %s came back in %s", id, v.Root())
			}
		}
	}
	t.Logf("%d deleted; %d lines saved in %d steps; %d files on the atrium", len(deleted), len(saved), steps, len(tree(t, a.v.Root())))
}
