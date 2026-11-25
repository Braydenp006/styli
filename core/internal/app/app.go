// Package app wires the pieces into a running atrium: vault, settings, search
// index, file watcher, maid and the HTTP API.
package app

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/maid"
	"styli/core/internal/settings"
	"styli/core/internal/store"
	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
	"styli/core/internal/watch"
	"styli/core/internal/web"
)

// DefaultAddr is where the atrium listens unless told otherwise.
const DefaultAddr = "127.0.0.1:7878"

// Config says what to run.
type Config struct {
	Vault     string // the notes folder; it must already exist
	Addr      string // loopback only until login exists; default DefaultAddr
	SyncAddr  string // where devices sync, behind mutual TLS; "" means no sync listener
	DataDir   string // search index; default: the user cache folder
	ConfigDir string // this device's settings; default: the user config folder
	Logf      func(string, ...any)
	Version   string            // shown on the dashboard
	Ready     func(addr string) // called once the atrium is listening
}

// CheckAddr refuses anything that is not a loopback address. The web API has
// no login yet, so it must never be reachable from the network.
func CheckAddr(addr string) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return fmt.Errorf("bad address %q: %w", addr, err)
	}
	if host == "" {
		return fmt.Errorf("refusing to listen on %q: that is every network interface; use 127.0.0.1", addr)
	}
	if host != "localhost" {
		if ip := net.ParseIP(strings.Trim(host, "[]")); ip == nil || !ip.IsLoopback() {
			return fmt.Errorf("refusing to listen on %q: until login exists the atrium only listens on this machine (127.0.0.1)", addr)
		}
	}
	return nil
}

// Run starts the atrium and blocks until ctx ends, then shuts down cleanly.
func Run(parent context.Context, cfg Config) error {
	if cfg.Logf == nil {
		cfg.Logf = log.Printf
	}
	if cfg.Addr == "" {
		cfg.Addr = DefaultAddr
	}
	if err := CheckAddr(cfg.Addr); err != nil {
		return err
	}
	if fi, err := os.Stat(cfg.Vault); err != nil || !fi.IsDir() {
		return fmt.Errorf("vault folder %q does not exist; create it first", cfg.Vault)
	}
	var err error
	if cfg.DataDir == "" {
		if cfg.DataDir, err = store.DataDir(cfg.Vault); err != nil {
			return err
		}
	}
	if cfg.ConfigDir == "" {
		if cfg.ConfigDir, err = settings.DeviceDir(cfg.Vault); err != nil {
			return err
		}
	}

	v, err := vault.Open(cfg.Vault)
	if err != nil {
		return err
	}
	set, err := settings.Open(cfg.Vault, cfg.ConfigDir)
	if err != nil {
		return err
	}
	if err := set.ApplyTo(v); err != nil {
		return err
	}
	// settings.Open made the config folder; the counter's copy lives there,
	// outside the vault, so a vault restored from a backup is noticed.
	if restored, err := v.SetSeqMirror(filepath.Join(cfg.ConfigDir, "seq")); err != nil {
		return err
	} else if restored {
		cfg.Logf("the vault's change counter went backwards (restored from a backup?); devices will do a full check")
	}
	st, err := store.Open(cfg.DataDir)
	if err != nil {
		return err
	}
	defer st.Close()
	m := maid.New(v, set, cfg.Logf)

	ctx, cancel := context.WithCancel(parent)
	var wg sync.WaitGroup
	// On every way out: stop the watcher and the maid, wait for them, and only
	// then (st.Close was deferred earlier, so it runs after this) close the index.
	defer func() { cancel(); wg.Wait() }()

	// First scan and index rebuild, so the UI never opens onto an empty list.
	cfg.Logf("scanning %s…", cfg.Vault)
	ready := make(chan struct{})
	watchErr := make(chan error, 1)
	wg.Add(1)
	go func() {
		defer wg.Done()
		watchErr <- watch.Run(ctx, v, st, watch.Options{Ready: func() { close(ready) }, Logf: cfg.Logf})
	}()
	select {
	case <-ready:
	case err := <-watchErr:
		return fmt.Errorf("scanning the vault: %w", err)
	case <-ctx.Done():
		return nil
	}

	wg.Add(1)
	go func() { defer wg.Done(); m.Run(ctx, maid.StartDelay, maid.Interval) }()

	// Uploads from devices that never got committed: tidy them every hour, not
	// only at startup, so an atrium that runs for months does not collect them.
	wg.Add(1)
	go func() {
		defer wg.Done()
		t := time.NewTicker(time.Hour)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if n, err := v.CleanBlobs(); err != nil {
					cfg.Logf("sync: tidying uploads: %v", err)
				} else if n > 0 {
					cfg.Logf("sync: removed %d unused uploads", n)
				}
			}
		}
	}()

	// The sync API, for paired devices only (mutual TLS with pinned keys). It
	// may face the network; the web UI below stays on this machine.
	syncErr := make(chan error, 1)
	if cfg.SyncAddr != "" {
		key, err := devicekey.Load(cfg.ConfigDir)
		if err != nil {
			return err
		}
		devs, err := devices.Open(cfg.Vault)
		if err != nil {
			return err
		}
		sln, err := net.Listen("tcp", cfg.SyncAddr)
		if err != nil {
			return fmt.Errorf("cannot listen for sync on %s: %w", cfg.SyncAddr, err)
		}
		ss := syncsrv.NewServer(syncsrv.Deps{Vault: v, Store: st, Logf: cfg.Logf, Version: cfg.Version}, key, devs)
		go func() { syncErr <- ss.Serve(sln) }()
		defer func() {
			shutdown, stop := context.WithTimeout(context.Background(), 5*time.Second)
			defer stop()
			ss.Shutdown(shutdown)
		}()
		cfg.Logf("sync is listening on %s for %d paired devices; this atrium's key: %s", sln.Addr(), len(devs.List()), key.ID)
	}

	ln, err := net.Listen("tcp", cfg.Addr)
	if err != nil {
		return fmt.Errorf("cannot listen on %s (is the atrium already running?): %w", cfg.Addr, err)
	}
	if tcp, ok := ln.Addr().(*net.TCPAddr); !ok || !tcp.IP.IsLoopback() {
		ln.Close()
		return fmt.Errorf("refusing to serve on %s: not a loopback address", ln.Addr())
	}
	addr := ln.Addr().String()
	srv := &http.Server{
		Handler: web.New(web.Deps{Vault: v, Store: st, Settings: set, Maid: m, Logf: cfg.Logf, Version: cfg.Version, Addr: addr}),
		// No WriteTimeout: a big PDF must be able to stream.
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	serveErr := make(chan error, 1)
	go func() { serveErr <- srv.Serve(ln) }()
	cfg.Logf("atrium is listening on http://%s", addr)
	if cfg.Ready != nil {
		cfg.Ready(addr)
	}

	select {
	case err := <-serveErr:
		return err
	case err := <-syncErr:
		srv.Close()
		return fmt.Errorf("sync server: %w", err)
	case <-ctx.Done():
	}
	cfg.Logf("shutting down…")
	shutdown, stop := context.WithTimeout(context.Background(), 5*time.Second)
	defer stop()
	if err := srv.Shutdown(shutdown); err != nil && !errors.Is(err, http.ErrServerClosed) {
		srv.Close()
	}
	return nil
}
