package maid

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"styli/core/internal/settings"
	"styli/core/internal/vault"
)

func must[T any](v T, err error) T {
	if err != nil {
		panic(err) // a test panic fails that test with the error shown
	}
	return v
}

type env struct {
	v    *vault.Vault
	s    *settings.Store
	m    *Maid
	root string

	mu   sync.Mutex
	logs []string
}

// setup makes a vault, settings with history window 0 (every write is a
// version), the given limits, and a maid whose clock is real time + skew.
func setup(t *testing.T, maxAgeDays, retentionDays int, skew time.Duration) *env {
	t.Helper()
	root := t.TempDir()
	e := &env{root: root, v: must(vault.Open(root)), s: must(settings.Open(root, t.TempDir()))}
	h := settings.DefaultHub()
	h.History = settings.History{On: true, WindowMinutes: 0, MaxAgeDays: maxAgeDays}
	h.TrashRetentionDays = retentionDays
	if err := e.s.SetHub(h); err != nil {
		t.Fatal(err)
	}
	if err := e.s.ApplyTo(e.v); err != nil {
		t.Fatal(err)
	}
	e.m = New(e.v, e.s, func(f string, a ...any) {
		e.mu.Lock()
		e.logs = append(e.logs, fmt.Sprintf(f, a...))
		e.mu.Unlock()
	})
	e.m.now = func() time.Time { return time.Now().Add(skew) }
	return e
}

func (e *env) log() string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return strings.Join(e.logs, "\n")
}

// noteWithVersions makes a note with n versions.
func (e *env) noteWithVersions(t *testing.T, name string, n int) vault.Group {
	t.Helper()
	g := must(e.v.CreateNote(name, []byte("v0")))
	for i := 1; i < n; i++ {
		_, base, _ := e.v.ReadNote(g.ID)
		must(e.v.WriteNote(g.ID, []byte(fmt.Sprintf("v%d", i)), base))
	}
	return g
}

// trashNotes trashes n new notes and returns their IDs.
func (e *env) trashNotes(t *testing.T, n int) []string {
	t.Helper()
	var ids []string
	for i := 0; i < n; i++ {
		g := must(e.v.CreateNote(fmt.Sprintf("t/trash%d.md", i), []byte("sample")))
		if err := e.v.Trash(g.ID); err != nil {
			t.Fatal(err)
		}
		ids = append(ids, g.ID)
	}
	return ids
}

// ageTrash moves a trash entry's deletion time into the past.
func (e *env) ageTrash(t *testing.T, id string, by time.Duration) {
	t.Helper()
	p := filepath.Join(e.root, ".ids/trash", id, "record.json")
	var rec map[string]any
	if err := json.Unmarshal(must(os.ReadFile(p)), &rec); err != nil {
		t.Fatal(err)
	}
	rec["deleted_at"] = time.Now().Add(-by).UTC().Format(time.RFC3339Nano)
	if err := os.WriteFile(p, must(json.Marshal(rec)), 0o600); err != nil {
		t.Fatal(err)
	}
}

func (e *env) versions(t *testing.T, id string) int { return len(must(e.v.History(id))) }
func (e *env) trashLen() int                        { return len(must(e.v.ListTrash())) }

func (e *env) timeCheck(t *testing.T, server string) {
	t.Helper()
	h := e.s.Hub()
	h.TimeCheck = settings.TimeCheck{On: true, Server: server}
	if err := e.s.SetHub(h); err != nil {
		t.Fatal(err)
	}
}

const day = 24 * time.Hour

func TestNormalRunPrunesAndPurges(t *testing.T) {
	e := setup(t, 30, 30, 40*day) // the maid's "now" is 40 days ahead
	g := e.noteWithVersions(t, "a.md", 10)
	live := must(e.v.CreateNote("live.md", []byte("keep me")))
	ids := e.trashNotes(t, 3)

	r := e.m.RunNow(context.Background(), false)
	if r.History.Deleted != 9 || r.Trash.Deleted != 3 || r.NeedsAttention() {
		t.Fatalf("%+v", r)
	}
	if e.versions(t, g.ID) != 1 || e.trashLen() != 0 {
		t.Fatal("not cleaned")
	}
	if b, _, err := e.v.ReadNote(live.ID); err != nil || string(b) != "keep me" {
		t.Fatal("a live note was touched")
	}
	for _, id := range ids {
		if !strings.Contains(e.log(), id) {
			t.Errorf("purged entry %s not logged", id)
		}
	}
	if strings.Contains(e.log(), "sample") {
		t.Error("note text reached the log")
	}
}

func TestClockJumpedYearsAheadHoldsBackBothJobs(t *testing.T) {
	e := setup(t, 30, 30, 73*365*day) // about 2099
	g := e.noteWithVersions(t, "a.md", 60)
	e.trashNotes(t, 6)

	r := e.m.RunNow(context.Background(), false)
	if !r.History.HeldBack || !r.Trash.HeldBack || r.History.Deleted != 0 || r.Trash.Deleted != 0 {
		t.Fatalf("%+v", r)
	}
	if e.versions(t, g.ID) != 60 || e.trashLen() != 6 {
		t.Fatal("something was deleted while held back")
	}
	st := e.m.Status()
	if st.Last == nil || !st.Last.NeedsAttention() || st.Running {
		t.Fatalf("status %+v", st)
	}
	if !strings.Contains(e.log(), "held back") {
		t.Fatal("hold not logged")
	}
	if r := e.m.RunNow(context.Background(), false); r.History.Deleted+r.Trash.Deleted != 0 {
		t.Fatal("the maid retried a held-back job on its own")
	}

	// The user looked at the dashboard and approved.
	r = e.m.RunNow(context.Background(), true)
	if r.History.Deleted != 59 || r.Trash.Deleted != 6 {
		t.Fatalf("forced run: %+v", r)
	}
}

func TestClockBehindDeletesNothing(t *testing.T) {
	e := setup(t, 30, 30, -36*365*day) // about 1990
	g := e.noteWithVersions(t, "a.md", 10)
	e.trashNotes(t, 3)
	r := e.m.RunNow(context.Background(), false)
	if r.History.WouldDelete+r.Trash.WouldDelete != 0 || r.NeedsAttention() {
		t.Fatalf("%+v", r)
	}
	if e.versions(t, g.ID) != 10 || e.trashLen() != 3 {
		t.Fatal("deleted something")
	}
}

func TestHoldInOneJobDoesNotStopTheOther(t *testing.T) {
	e := setup(t, 30, 30, 40*day)
	g := e.noteWithVersions(t, "a.md", 10)
	e.trashNotes(t, 6)
	r := e.m.RunNow(context.Background(), false)
	if !r.Trash.HeldBack || r.History.Deleted != 9 {
		t.Fatalf("%+v", r)
	}
	if e.versions(t, g.ID) != 1 || e.trashLen() != 6 {
		t.Fatal("wrong job ran")
	}
}

func TestSmallShareOfABigTrashIsNotHeldBack(t *testing.T) {
	e := setup(t, 0, 30, 0)
	ids := e.trashNotes(t, 24)
	for _, id := range ids[:6] { // 6 of 24 = 25%: at the limit, not over it
		e.ageTrash(t, id, 40*day)
	}
	r := e.m.RunNow(context.Background(), false)
	if r.Trash.HeldBack || r.Trash.Deleted != 6 || e.trashLen() != 18 {
		t.Fatalf("%+v", r)
	}
}

func TestRetentionForeverAndDeviceOverride(t *testing.T) {
	e := setup(t, 0, 0, 40*day) // hub: keep trash forever
	e.trashNotes(t, 2)
	if r := e.m.RunNow(context.Background(), false); r.Trash.Skipped == "" || e.trashLen() != 2 {
		t.Fatalf("%+v", r)
	}
	hundred := 100
	e.s.SetDevice(settings.Device{TrashRetentionDays: &hundred}) // device keeps 100 days
	if r := e.m.RunNow(context.Background(), false); r.Trash.Deleted != 0 || e.trashLen() != 2 {
		t.Fatalf("%+v", r)
	}
	ten := 10
	e.s.SetDevice(settings.Device{TrashRetentionDays: &ten})
	if r := e.m.RunNow(context.Background(), false); r.Trash.Deleted != 2 {
		t.Fatalf("%+v", r)
	}
}

func TestAFailingJobIsReportedAndTheOtherStillRuns(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("permissions do not apply to root")
	}
	e := setup(t, 30, 30, 40*day)
	g := e.noteWithVersions(t, "a.md", 3)
	e.trashNotes(t, 2)
	dir := filepath.Join(e.root, ".ids/history", g.ID)
	os.Chmod(dir, 0)
	defer os.Chmod(dir, 0o755)
	r := e.m.RunNow(context.Background(), false)
	if r.History.Err == "" || r.Trash.Deleted != 2 || !r.NeedsAttention() {
		t.Fatalf("%+v", r)
	}
}

func TestPreviewCountsWhatARunDeletesAndDeletesNothing(t *testing.T) {
	e := setup(t, 30, 30, 40*day)
	g := e.noteWithVersions(t, "a.md", 10)
	e.trashNotes(t, 3)
	calls := 0
	e.m.checkClock = func(context.Context, string) (time.Duration, error) { calls++; return 0, nil }
	e.timeCheck(t, "time.local")

	p := e.m.Preview(context.Background())
	if p.History.WouldDelete != 9 || p.Trash.WouldDelete != 3 || p.History.Deleted+p.Trash.Deleted != 0 {
		t.Fatalf("%+v", p)
	}
	if e.versions(t, g.ID) != 10 || e.trashLen() != 3 || calls != 0 {
		t.Fatal("preview deleted something or asked the time server")
	}
	r := e.m.RunNow(context.Background(), false)
	if r.History.Deleted != p.History.WouldDelete || r.Trash.Deleted != p.Trash.WouldDelete || calls != 1 {
		t.Fatalf("run %+v differs from preview %+v", r, p)
	}
}

func TestTimeCheckOffMakesNoCall(t *testing.T) {
	e := setup(t, 30, 30, 0)
	e.m.checkClock = func(context.Context, string) (time.Duration, error) {
		t.Fatal("asked a time server while the check is off")
		return 0, nil
	}
	if r := e.m.RunNow(context.Background(), false); r.TimeCheck != "off" {
		t.Fatalf("%+v", r)
	}
}

func TestRunsDoNotOverlap(t *testing.T) {
	e := setup(t, 30, 30, 40*day)
	e.noteWithVersions(t, "a.md", 5)
	var inside, most int32
	e.m.checkClock = func(context.Context, string) (time.Duration, error) {
		n := atomic.AddInt32(&inside, 1)
		for {
			m := atomic.LoadInt32(&most)
			if n <= m || atomic.CompareAndSwapInt32(&most, m, n) {
				break
			}
		}
		time.Sleep(20 * time.Millisecond)
		atomic.AddInt32(&inside, -1)
		return 0, nil
	}
	e.timeCheck(t, "time.local")
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); e.m.RunNow(context.Background(), false) }()
	}
	wg.Wait()
	if most != 1 {
		t.Fatalf("%d runs overlapped", most)
	}
}

func TestLoopWaitsRepeatsAndStops(t *testing.T) {
	e := setup(t, 30, 30, 0)
	var runs int32
	e.m.checkClock = func(context.Context, string) (time.Duration, error) { atomic.AddInt32(&runs, 1); return 0, nil }
	e.timeCheck(t, "time.local")
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { e.m.Run(ctx, 50*time.Millisecond, 30*time.Millisecond); close(done) }()
	time.Sleep(20 * time.Millisecond)
	if atomic.LoadInt32(&runs) != 0 {
		t.Fatal("ran before the start delay")
	}
	time.Sleep(150 * time.Millisecond)
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Run did not stop")
	}
	if n := atomic.LoadInt32(&runs); n < 2 {
		t.Fatalf("ran %d times", n)
	}
}

// --- the time check, against a fake time server on a local UDP port ---

func ntpServer(t *testing.T, reply func(req []byte) []byte) string {
	t.Helper()
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Skip("no UDP:", err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 512)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			if resp := reply(append([]byte(nil), buf[:n]...)); resp != nil {
				pc.WriteTo(resp, addr)
			}
		}
	}()
	return pc.LocalAddr().String()
}

// serverAt answers like a healthy NTP server whose clock reads now()+skew.
func serverAt(skew time.Duration) func([]byte) []byte {
	return func(req []byte) []byte {
		resp := make([]byte, 48)
		resp[0], resp[1] = 0x24, 2 // version 4, mode 4 (server); stratum 2
		copy(resp[24:32], req[40:48])
		ts := toNTP(time.Now().Add(skew))
		binary.BigEndian.PutUint64(resp[32:], ts)
		binary.BigEndian.PutUint64(resp[40:], ts)
		return resp
	}
}

func checkWith(e *env, timeout time.Duration) {
	e.m.checkClock = func(ctx context.Context, server string) (time.Duration, error) {
		return clockOffset(ctx, server, e.m.now, timeout)
	}
}

func TestTimeCheckPassesAndTheMaidRuns(t *testing.T) {
	e := setup(t, 0, 30, 0)
	id := e.trashNotes(t, 1)[0]
	e.ageTrash(t, id, 40*day)
	e.timeCheck(t, ntpServer(t, serverAt(0)))
	r := e.m.RunNow(context.Background(), false)
	if r.TimeCheck != "ok" || r.Trash.Deleted != 1 {
		t.Fatalf("%+v", r)
	}
}

func TestWrongClockDeletesNothingEvenWhenForced(t *testing.T) {
	for _, skew := range []time.Duration{time.Hour, -10 * time.Minute, 73 * 365 * day} {
		e := setup(t, 30, 30, 0)
		g := e.noteWithVersions(t, "a.md", 60)
		ids := e.trashNotes(t, 6)
		for _, id := range ids {
			e.ageTrash(t, id, 40*day)
		}
		e.timeCheck(t, ntpServer(t, serverAt(skew)))
		r := e.m.RunNow(context.Background(), true)
		if r.TimeCheck != "wrong" || !r.NeedsAttention() || r.Trash.Deleted+r.History.Deleted != 0 {
			t.Fatalf("skew %v: %+v", skew, r)
		}
		if e.versions(t, g.ID) != 60 || e.trashLen() != 6 {
			t.Fatalf("skew %v: deleted something", skew)
		}
	}
}

func TestSmallClockDifferenceIsFine(t *testing.T) {
	e := setup(t, 30, 30, 0)
	e.timeCheck(t, ntpServer(t, serverAt(4*time.Minute)))
	if r := e.m.RunNow(context.Background(), false); r.TimeCheck != "ok" || r.ClockOffset < 3*time.Minute {
		t.Fatalf("%+v", r)
	}
}

func TestUnreachableTimeServerFallsBackToTheBreaker(t *testing.T) {
	e := setup(t, 0, 30, 0)
	checkWith(e, 300*time.Millisecond)
	ids := e.trashNotes(t, 7)
	for _, id := range ids[:1] {
		e.ageTrash(t, id, 40*day)
	}
	pc := must(net.ListenPacket("udp", "127.0.0.1:0"))
	addr := pc.LocalAddr().String()
	pc.Close() // nothing listens there now
	e.timeCheck(t, addr)
	r := e.m.RunNow(context.Background(), false)
	if r.TimeCheck != "unreachable" || r.Trash.Deleted != 1 {
		t.Fatalf("%+v", r)
	}
}

func TestBadTimeServerRepliesAreRejected(t *testing.T) {
	good := serverAt(0)
	for name, reply := range map[string]func([]byte) []byte{
		"short":         func(req []byte) []byte { return good(req)[:20] },
		"client mode":   func(req []byte) []byte { r := good(req); r[0] = 0x23; return r },
		"stratum 0":     func(req []byte) []byte { r := good(req); r[1] = 0; return r },
		"unsynced":      func(req []byte) []byte { r := good(req); r[0] |= 3 << 6; return r },
		"wrong origin":  func(req []byte) []byte { r := good(req); r[30]++; return r },
		"no time":       func(req []byte) []byte { r := good(req); copy(r[40:48], make([]byte, 8)); return r },
		"silent server": func([]byte) []byte { return nil },
	} {
		addr := ntpServer(t, reply)
		if _, err := clockOffset(context.Background(), addr, time.Now, 300*time.Millisecond); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	addr := ntpServer(t, good)
	off, err := clockOffset(context.Background(), addr, time.Now, time.Second)
	if err != nil || off > time.Second || off < -time.Second {
		t.Fatalf("good server: %v %v", off, err)
	}
	if _, err := clockOffset(context.Background(), "host.invalid", time.Now, 300*time.Millisecond); err == nil {
		t.Error("unresolvable server accepted")
	}
}

func TestNTPTimestampsRoundTripIncludingAfter2036(t *testing.T) {
	for _, s := range []string{"1990-05-01T12:00:00.5Z", "2026-11-24T08:30:00.123456789Z", "2036-02-07T06:28:15Z", "2036-02-07T06:28:17Z", "2050-01-01T00:00:00.25Z"} {
		want := must(time.Parse(time.RFC3339Nano, s))
		got := fromNTP(toNTP(want))
		if d := got.Sub(want); d > time.Microsecond || d < -time.Microsecond {
			t.Errorf("%s: got %s", s, got.UTC())
		}
	}
}

func TestTrippedBoundaries(t *testing.T) {
	for _, c := range []struct {
		n, total, min int
		want          bool
	}{
		{4, 4, 5, false},  // under the minimum
		{5, 5, 5, true},   // at the minimum, 100%
		{5, 20, 5, false}, // exactly 25%
		{6, 20, 5, true},  // over 25%
		{50, 400, 50, false},
		{51, 200, 50, true},
	} {
		if got := tripped(c.n, c.total, c.min); got != c.want {
			t.Errorf("%+v: got %v", c, got)
		}
	}
}

func TestCancelledRunDeletesNothing(t *testing.T) {
	e := setup(t, 30, 30, 40*day)
	g := e.noteWithVersions(t, "a.md", 10)
	e.trashNotes(t, 3)
	ctx, cancel := context.WithCancel(context.Background())
	// The time check is cut short by shutdown: that must not look like
	// "unreachable, carry on".
	e.m.checkClock = func(ctx context.Context, _ string) (time.Duration, error) {
		cancel()
		return 0, ctx.Err()
	}
	e.timeCheck(t, "time.local")
	r := e.m.RunNow(ctx, true)
	if r.History.Deleted+r.Trash.Deleted != 0 || e.versions(t, g.ID) != 10 || e.trashLen() != 3 {
		t.Fatalf("%+v", r)
	}
}
