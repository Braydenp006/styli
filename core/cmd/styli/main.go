// Command styli is the single Styli binary.
//
//	styli serve --vault DIR [--addr 127.0.0.1:7878] [--sync-addr :7879] [--no-announce] [--new-browser-link] [--clear-password] [--tls-cert F --tls-key F] [--web-name N] [--new-ca] [--data DIR] [--config DIR]
//	styli join --vault DIR --atrium HOST[:PORT] [--name NAME] [--config DIR]
//	styli version
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"syscall"

	"styli/core/internal/app"
)

// Version is the build version; the Makefile can override it with -ldflags.
var Version = "dev"

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprintln(stderr, "usage: styli serve --vault DIR | styli join --vault DIR --atrium HOST | styli version")
		return 2
	}
	switch args[0] {
	case "version":
		fmt.Fprintln(stdout, "styli", Version)
		return 0
	case "serve":
		return serve(args[1:], stdout, stderr)
	case "join":
		return join(args[1:], stdin, stdout, stderr)
	}
	fmt.Fprintf(stderr, "unknown command %q\nusage: styli serve --vault DIR | styli join --vault DIR --atrium HOST | styli version\n", args[0])
	return 2
}

func serve(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	fs.SetOutput(stderr)
	cfg := app.Config{}
	fs.StringVar(&cfg.Vault, "vault", "", "the notes folder (must exist)")
	fs.StringVar(&cfg.Addr, "addr", app.DefaultAddr, "web UI address: 127.0.0.1 for this machine only, or 0.0.0.0 (atrium only, with HTTPS) for the network")
	fs.StringVar(&cfg.TLSCert, "tls-cert", "", "atrium only: your own HTTPS certificate (PEM), instead of the atrium's own certificate authority")
	fs.StringVar(&cfg.TLSKey, "tls-key", "", "atrium only: the key for --tls-cert (PEM)")
	fs.Func("web-name", "atrium only: another name phones reach the atrium by, such as a VPN name; can be given more than once, and the first is used in sign-in links", func(v string) error {
		cfg.WebNames = append(cfg.WebNames, v)
		return nil
	})
	fs.BoolVar(&cfg.NewCA, "new-ca", false, "atrium only: replace the atrium's certificate authority (every phone and laptop must install the new one)")
	fs.BoolVar(&cfg.ClearPassword, "clear-password", false, "atrium only: remove the web password, for when it is forgotten")
	fs.BoolVar(&cfg.NewBrowserLink, "new-browser-link", false, "atrium only: print a one-time link to sign a browser in, even if one is already trusted (for when every trusted browser is lost)")
	fs.BoolVar(&cfg.NoAnnounce, "no-announce", false, "atrium only: do not announce this atrium on the local network (devices then need its address)")
	fs.StringVar(&cfg.SyncAddr, "sync-addr", app.DefaultSyncAddr, "atrium only: where paired devices sync (mutual TLS); empty turns sync off")
	fs.StringVar(&cfg.DataDir, "data", "", "search index folder (default: user cache folder)")
	fs.StringVar(&cfg.ConfigDir, "config", "", "this device's settings folder (default: user config folder)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if cfg.Vault == "" {
		fmt.Fprintln(stderr, "serve: --vault is required")
		return 2
	}
	logger := log.New(stderr, "", log.LstdFlags)
	cfg.Logf = logger.Printf
	cfg.Version = Version
	cfg.Ready = func(addr string) { fmt.Fprintf(stdout, "Styli is running on %s\n", addr) }

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := app.Run(ctx, cfg); err != nil {
		fmt.Fprintln(stderr, "serve:", err)
		return 1
	}
	return 0
}
