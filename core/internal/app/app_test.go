package app

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

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

func get(t *testing.T, url string) (int, map[string]any) {
	t.Helper()
	resp, err := http.Get(url)
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
	// A note that exists before the hub starts: the first scan must index it.
	os.MkdirAll(filepath.Join(vaultDir, "Old"), 0o755)
	os.WriteFile(filepath.Join(vaultDir, "Old/before.md"), []byte("# Before\nplum"), 0o644)
	time.Sleep(100 * time.Millisecond)

	ctx, cancel := context.WithCancel(context.Background())
	addrCh := make(chan string, 1)
	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, Config{Vault: vaultDir, Addr: "127.0.0.1:0", DataDir: t.TempDir(), ConfigDir: t.TempDir(),
			Logf: t.Logf, Ready: func(a string) { addrCh <- a }})
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
	start := func(then func(base string)) {
		ctx, cancel := context.WithCancel(context.Background())
		addrCh, done := make(chan string, 1), make(chan error, 1)
		go func() {
			done <- Run(ctx, Config{Vault: vaultDir, Addr: "127.0.0.1:0", DataDir: data, ConfigDir: cfg, Logf: t.Logf, Ready: func(a string) { addrCh <- a }})
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
	start(func(base string) {
		resp, err := http.Post(base+"/api/notes", "application/json", strings.NewReader(`{"path":"keep.md","text":"# Keep\nfig"}`))
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
	start(func(base string) {
		if code, m := get(t, base+"/api/notes/"+id); code != 200 || m["text"] != "# Keep\nfig" {
			t.Fatalf("note lost across restart: %d %v", code, m)
		}
		if _, m := get(t, base+"/api/notes?q=fig"); len(m["notes"].([]any)) != 1 {
			t.Fatal("index lost across restart")
		}
	})
}
