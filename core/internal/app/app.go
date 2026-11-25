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

	"styli/core/internal/browsers"
	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/discovery"
	"styli/core/internal/maid"
	"styli/core/internal/pairing"
	"styli/core/internal/settings"
	"styli/core/internal/store"
	"styli/core/internal/syncdev"
	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
	"styli/core/internal/watch"
	"styli/core/internal/web"
)

// DefaultAddr is where the atrium listens unless told otherwise.
const DefaultAddr = "127.0.0.1:7878"

// DefaultSyncAddr is where an atrium listens for its devices unless told otherwise.
const DefaultSyncAddr = ":7879"

// Config says what to run.
type Config struct {
	Vault      string // the notes folder; it must already exist
	Addr       string // loopback only until login exists; default DefaultAddr
	SyncAddr   string // where devices sync, behind mutual TLS; "" means no sync listener. A device opens none.
	NoAnnounce bool   // do not announce the atrium on the local network (mDNS)
	DataDir    string // search index; default: the user cache folder
	ConfigDir  string // this device's settings; default: the user config folder
	Logf       func(string, ...any)
	Version    string            // shown on the dashboard
	Ready      func(addr string) // called once the atrium is listening
	// NewBrowserLink prints a one-time sign-in link at start even when some
	// browser is already trusted (one is always printed when none is).
	NewBrowserLink bool
	// BrowserLink, if set, is also given that link (tests sign in with it).
	BrowserLink func(url string)
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

	// A machine that has paired with an atrium is a device: it syncs with that
	// atrium and opens no sync port, so nothing can pair with it.
	var engine *syncdev.Engine
	if link := set.Device().Atrium; link != nil {
		if cfg.SyncAddr != "" && cfg.SyncAddr != DefaultSyncAddr {
			return fmt.Errorf("this machine is a device of the atrium at %s, so it opens no sync port; leave out --sync-addr", link.Addr)
		}
		cfg.SyncAddr = ""
		key, err := devicekey.Load(cfg.ConfigDir)
		if err != nil {
			return err
		}
		engine = syncdev.New(v, st, syncsrv.ClientTLS(key, link.ID), link.Addr, cfg.Logf)
		engine.DeviceName = set.Device().Name
		// Other ways to the atrium when the last one that worked is down: the
		// addresses given at pairing, and the atrium announcing itself nearby
		// (only the atrium holding the pinned key counts; see discovery).
		engine.Fallback(link.Addrs...)
		engine.Discover(func(ctx context.Context) []string {
			found, err := discovery.Browse(ctx, 2*time.Second)
			if err != nil {
				cfg.Logf("discovery: %v", err)
			}
			for _, f := range found {
				if f.ID == link.ID {
					return f.Addrs
				}
			}
			return nil
		}, func(addr string) {
			cfg.Logf("sync: reached the atrium at %s", addr)
			if err := set.RememberAddr(addr); err != nil {
				cfg.Logf("sync: could not save the atrium's address: %v", err)
			}
		})
		wg.Add(1)
		go func() { defer wg.Done(); engine.Run(ctx) }()
		cfg.Logf("this device syncs with the atrium at %s", link.Addr)
	}

	// The sync API, for paired devices only (mutual TLS with pinned keys). It
	// may face the network; the web UI below stays on this machine.
	syncErr := make(chan error, 1)
	var pair *pairing.Atrium
	var devList *devices.List
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
		devList = devs
		pair = pairing.NewAtrium(key.ID, devs)
		ss := syncsrv.NewServer(syncsrv.Deps{Vault: v, Store: st, Logf: cfg.Logf, Version: cfg.Version, Pairing: pair}, key, devs)
		go func() { syncErr <- ss.Serve(sln) }()
		defer func() {
			shutdown, stop := context.WithTimeout(context.Background(), 5*time.Second)
			defer stop()
			ss.Shutdown(shutdown)
		}()
		cfg.Logf("sync is listening on %s for %d paired devices; this atrium's key: %s", sln.Addr(), len(devs.List()), key.ID)
		if !cfg.NoAnnounce {
			if err := discovery.Announce(ctx, key.ID, sln.Addr().(*net.TCPAddr).Port, cfg.Logf); err != nil {
				cfg.Logf("%v: devices will need this machine's address to pair and sync", err)
			} else {
				cfg.Logf("discovery: announced on the local network as styli-%s (--no-announce turns this off)", key.ID[:8])
			}
		}
	}

	// The atrium's web UI is for trusted browsers only (see browsers). A
	// device's page is for that machine alone, until the desktop app replaces it.
	var brs *browsers.List
	if engine == nil {
		if brs, err = browsers.Open(cfg.Vault); err != nil {
			return err
		}
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
		Handler: web.New(web.Deps{Vault: v, Store: st, Settings: set, Maid: m, Logf: cfg.Logf, Version: cfg.Version, Pairing: pair, Devices: devList, Sync: engine, Addr: addr, Browsers: brs}),
		// No WriteTimeout: a big PDF must be able to stream.
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	serveErr := make(chan error, 1)
	go func() { serveErr <- srv.Serve(ln) }()
	cfg.Logf("atrium is listening on http://%s", addr)
	if brs != nil && (brs.Count() == 0 || cfg.NewBrowserLink) {
		secret, expires, err := brs.NewLink(browsers.SetupLinkLife)
		if err != nil {
			srv.Close()
			return err
		}
		link := web.JoinURL(false, addr, secret)
		cfg.Logf("to sign in, open this link in a browser on this machine. It works once, until %s:\n\n    %s\n", expires.Local().Format("15:04"), link)
		if cfg.BrowserLink != nil {
			cfg.BrowserLink(link)
		}
	}
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
