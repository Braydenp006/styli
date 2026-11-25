package app

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/discovery"
	"styli/core/internal/settings"
	"styli/core/internal/vault"
)

func TestCheckAddrOnlyAllowsLoopback(t *testing.T) {
	for addr, ok := range map[string]bool{
		"127.0.0.1:7878": true, "localhost:7878": true, "[::1]:7878": true, "127.0.0.2:80": true, "127.0.0.1:0": true,
		"0.0.0.0:7878": false, ":7878": false, "[::]:7878": false, "192.168.1.5:7878": false, "10.0.0.1:1": false,
		"example.com:80": false, "8.8.8.8:53": false, "localhost": false, "": false, "nonsense": false, "127.0.0.1": false,
	} {
		if err := CheckAddr(addr); (err == nil) != ok {
			t.Errorf("%q: %v", addr, err)
		}
	}
}

func TestRefusedBeforeTouchingTheVault(t *testing.T) {
	vaultDir := t.TempDir()
	err := Run(context.Background(), Config{Vault: vaultDir, Addr: "0.0.0.0:0", DataDir: t.TempDir(), ConfigDir: t.TempDir()})
	if err == nil || !strings.Contains(err.Error(), "refusing") {
		t.Fatalf("got %v", err)
	}
	if ents, _ := os.ReadDir(vaultDir); len(ents) != 0 {
		t.Fatalf("a refused start still wrote into the vault: %v", ents)
	}
	err = Run(context.Background(), Config{Vault: filepath.Join(vaultDir, "nope"), Addr: "127.0.0.1:0", DataDir: t.TempDir(), ConfigDir: t.TempDir()})
	if err == nil || !strings.Contains(err.Error(), "does not exist") {
		t.Fatalf("got %v", err)
	}
	if _, err := os.Stat(filepath.Join(vaultDir, "nope")); err == nil {
		t.Fatal("a typo in --vault created a folder")
	}
}

func TestPortAlreadyInUse(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip(err)
	}
	defer ln.Close()
	err = Run(context.Background(), Config{Vault: t.TempDir(), Addr: ln.Addr().String(), DataDir: t.TempDir(), ConfigDir: t.TempDir(), Logf: t.Logf})
	if err == nil || !strings.Contains(err.Error(), "cannot listen") {
		t.Fatalf("got %v", err)
	}
}

// client is the test's browser. signIn gives it a session.
var client = &http.Client{}

// signIn opens a one-time link the atrium printed, as a new browser would,
// and keeps the session cookie for the requests that follow.
func signIn(t *testing.T, link string) {
	t.Helper()
	base, token, ok := strings.Cut(link, "/#/join/")
	if !ok {
		t.Fatalf("not a sign-in link: %q", link)
	}
	jar, _ := cookiejar.New(nil)
	client = &http.Client{Jar: jar}
	resp, err := client.Post(base+"/api/join", "application/json", strings.NewReader(`{"token":"`+token+`","name":"Test browser"}`))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 201 {
		t.Fatalf("sign in: %d", resp.StatusCode)
	}
}

func get(t *testing.T, url string) (int, map[string]any) {
	t.Helper()
	resp, err := client.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	var m map[string]any
	json.Unmarshal(b, &m)
	return resp.StatusCode, m
}

func TestWholeHubEndToEnd(t *testing.T) {
	old := vault.Settle
	vault.Settle = 50 * time.Millisecond
	defer func() { vault.Settle = old }()

	vaultDir := t.TempDir()
	// A note that exists before the atrium starts: the first scan must index it.
	os.MkdirAll(filepath.Join(vaultDir, "Old"), 0o755)
	os.WriteFile(filepath.Join(vaultDir, "Old/before.md"), []byte("# Before\nplum"), 0o644)
	time.Sleep(100 * time.Millisecond)

	ctx, cancel := context.WithCancel(context.Background())
	addrCh, linkCh := make(chan string, 1), make(chan string, 1)
	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, Config{Vault: vaultDir, Addr: "127.0.0.1:0", DataDir: t.TempDir(), ConfigDir: t.TempDir(),
			Logf: t.Logf, Ready: func(a string) { addrCh <- a }, BrowserLink: func(l string) { linkCh <- l }})
	}()
	var addr string
	select {
	case addr = <-addrCh:
	case err := <-done:
		t.Fatalf("hub stopped: %v", err)
	case <-time.After(10 * time.Second):
		t.Fatal("hub did not start")
	}
	base := "http://" + addr
	// No browser is trusted yet, so the atrium printed a link, and nothing
	// works without signing in.
	if code, m := get(t, base+"/api/status"); code != 401 || m["code"] != "login_required" {
		t.Fatalf("before signing in: %d %v", code, m)
	}
	signIn(t, <-linkCh)

	// The first scan finished before it started listening.
	if code, m := get(t, base+"/api/notes?q=plum"); code != 200 || len(m["notes"].([]any)) != 1 {
		t.Fatalf("existing note not indexed: %d %v", code, m)
	}
	if code, _ := get(t, base+"/api/status"); code != 200 {
		t.Fatalf("status %d", code)
	}

	// An edit made outside the app reaches the API through the watcher.
	os.WriteFile(filepath.Join(vaultDir, "Old/outside.md"), []byte("# Outside\napricot"), 0o644)
	deadline := time.Now().Add(10 * time.Second)
	for {
		if _, m := get(t, base+"/api/notes?q=apricot"); len(m["notes"].([]any)) == 1 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("outside file never showed up")
		}
		time.Sleep(50 * time.Millisecond)
	}

	// Shutdown is clean and the port is released.
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Run: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("hub did not stop")
	}
	if _, err := http.Get(base + "/api/status"); err == nil {
		t.Fatal("still answering after shutdown")
	}
}

func TestSecondRunOnTheSameVaultKeepsEverything(t *testing.T) {
	vaultDir, data, cfg := t.TempDir(), t.TempDir(), t.TempDir()
	var link string // the sign-in link the last start printed, if any
	start := func(newLink bool, then func(base string)) {
		ctx, cancel := context.WithCancel(context.Background())
		addrCh, done := make(chan string, 1), make(chan error, 1)
		link = ""
		go func() {
			done <- Run(ctx, Config{Vault: vaultDir, Addr: "127.0.0.1:0", DataDir: data, ConfigDir: cfg, Logf: t.Logf, NewBrowserLink: newLink,
				Ready: func(a string) { addrCh <- a }, BrowserLink: func(l string) { link = l }})
		}()
		select {
		case a := <-addrCh:
			then("http://" + a)
		case err := <-done:
			t.Fatal(err)
		case <-time.After(10 * time.Second):
			t.Fatal("no start")
		}
		cancel()
		if err := <-done; err != nil {
			t.Fatal(err)
		}
	}
	var id string
	start(false, func(base string) {
		signIn(t, link)
		resp, err := client.Post(base+"/api/notes", "application/json", strings.NewReader(`{"path":"keep.md","text":"# Keep\nfig"}`))
		if err != nil {
			t.Fatal(err)
		}
		var m map[string]string
		json.NewDecoder(resp.Body).Decode(&m)
		resp.Body.Close()
		id = m["id"]
		if resp.StatusCode != 201 || id == "" {
			t.Fatalf("create: %d", resp.StatusCode)
		}
	})
	start(false, func(base string) {
		// The browser stays signed in across the restart, so no link is printed.
		if link != "" {
			t.Fatalf("a link was printed although a browser is trusted: %q", link)
		}
		if code, m := get(t, base+"/api/notes/"+id); code != 200 || m["text"] != "# Keep\nfig" {
			t.Fatalf("note lost across restart: %d %v", code, m)
		}
		if _, m := get(t, base+"/api/notes?q=fig"); len(m["notes"].([]any)) != 1 {
			t.Fatal("index lost across restart")
		}
	})
	// --new-browser-link prints one anyway (every trusted browser was lost).
	start(true, func(base string) {
		if link == "" {
			t.Fatal("no link with NewBrowserLink")
		}
	})
}

// A machine that has paired with an atrium is a device: it opens no sync port,
// refuses to be told to, and its sync status is on the web API.
func TestDeviceMode(t *testing.T) {
	vaultDir, cfgDir := t.TempDir(), t.TempDir()
	set, err := settings.Open(vaultDir, cfgDir)
	if err != nil {
		t.Fatal(err)
	}
	unreachable := "127.0.0.1:1"
	if err := set.SetAtrium(&settings.AtriumLink{ID: strings.Repeat("a", 64), Addr: unreachable, PairedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}

	err = Run(context.Background(), Config{Vault: vaultDir, Addr: "127.0.0.1:0", SyncAddr: "127.0.0.1:0", DataDir: t.TempDir(), ConfigDir: cfgDir, Logf: t.Logf})
	if err == nil || !strings.Contains(err.Error(), "device") {
		t.Fatalf("explicit sync port on a device: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	addrCh := make(chan string, 1)
	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, Config{Vault: vaultDir, Addr: "127.0.0.1:0", SyncAddr: DefaultSyncAddr, DataDir: t.TempDir(), ConfigDir: cfgDir,
			Logf: t.Logf, Ready: func(a string) { addrCh <- a }})
	}()
	var addr string
	select {
	case addr = <-addrCh:
	case err := <-done:
		t.Fatalf("device stopped: %v", err)
	case <-time.After(10 * time.Second):
		t.Fatal("device did not start")
	}
	if code, m := get(t, "http://"+addr+"/api/sync"); code != 200 || m["atrium"] != unreachable {
		t.Fatalf("sync status: %d %v", code, m)
	}
	if code, _ := get(t, "http://"+addr+"/api/pairing"); code != 404 {
		t.Fatalf("a device offers pairing: %d", code)
	}
	if c, err := net.DialTimeout("tcp", "127.0.0.1"+DefaultSyncAddr, 200*time.Millisecond); err == nil {
		c.Close()
		t.Log("something listens on the default sync port; it may be another program") // can't assert: the port is shared
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// A device whose saved address is dead finds the atrium announcing itself on
// the network, syncs, and keeps the address that worked (and the old one).
func TestDeviceFindsTheAtriumOnTheNetwork(t *testing.T) {
	if _, err := discovery.Browse(context.Background(), 50*time.Millisecond); err != nil {
		t.Skipf("no network to announce on: %v", err)
	}
	atriumDir, atriumCfg, devDir, devCfg := t.TempDir(), t.TempDir(), t.TempDir(), t.TempDir()
	atriumKey, devKey := must(devicekey.Load(atriumCfg)), must(devicekey.Load(devCfg))
	if err := must(devices.Open(atriumDir)).Add(devKey.ID, "Laptop"); err != nil {
		t.Fatal(err)
	}
	const dead = "127.0.0.1:1"
	set := must(settings.Open(devDir, devCfg))
	if err := set.SetAtrium(&settings.AtriumLink{ID: atriumKey.ID, Addr: dead, PairedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}
	must(vault.Open(devDir)).CreateNote("Found.md", []byte("hello\n"))

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 2)
	run := func(c Config) { c.Logf, c.Addr = t.Logf, "127.0.0.1:0"; done <- Run(ctx, c) }
	go run(Config{Vault: atriumDir, ConfigDir: atriumCfg, DataDir: t.TempDir(), SyncAddr: ":0"})
	go run(Config{Vault: devDir, ConfigDir: devCfg, DataDir: t.TempDir(), SyncAddr: DefaultSyncAddr})

	deadline := time.Now().Add(30 * time.Second)
	for {
		if b, _ := os.ReadFile(filepath.Join(atriumDir, "Found.md")); string(b) == "hello\n" {
			break
		}
		if time.Now().After(deadline) {
			cancel()
			t.Fatal("the note never reached the atrium")
		}
		time.Sleep(100 * time.Millisecond)
	}
	cancel()
	for range 2 {
		if err := <-done; err != nil {
			t.Fatal(err)
		}
	}
	a := must(settings.Open(devDir, devCfg)).Device().Atrium
	if a == nil || a.Addr == dead || len(a.Addrs) != 1 || a.Addrs[0] != dead {
		t.Fatalf("saved atrium: %+v", a)
	}
}

// With --no-announce nothing is announced, so the same device cannot find it.
func TestNoAnnounceKeepsTheAtriumQuiet(t *testing.T) {
	if _, err := discovery.Browse(context.Background(), 50*time.Millisecond); err != nil {
		t.Skipf("no network to announce on: %v", err)
	}
	cfg := t.TempDir()
	key := must(devicekey.Load(cfg))
	ctx, cancel := context.WithCancel(context.Background())
	ready, done := make(chan string, 1), make(chan error, 1)
	go func() {
		done <- Run(ctx, Config{Vault: t.TempDir(), ConfigDir: cfg, DataDir: t.TempDir(), Addr: "127.0.0.1:0", SyncAddr: ":0", NoAnnounce: true, Logf: t.Logf, Ready: func(a string) { ready <- a }})
	}()
	select {
	case <-ready:
	case err := <-done:
		t.Fatalf("stopped: %v", err)
	case <-time.After(10 * time.Second):
		t.Fatal("did not start")
	}
	found, _ := discovery.Browse(ctx, time.Second)
	for _, f := range found {
		if f.ID == key.ID {
			t.Fatal("announced although told not to")
		}
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}
