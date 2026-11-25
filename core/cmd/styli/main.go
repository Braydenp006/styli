// Command styli is the single Styli binary.
//
//	styli serve --vault DIR [--addr 127.0.0.1:7878] [--data DIR] [--config DIR]
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
		fmt.Fprintln(stderr, "usage: styli serve --vault DIR | styli version")
		return 2
	}
	switch args[0] {
	case "version":
		fmt.Fprintln(stdout, "styli", Version)
		return 0
	case "serve":
		return serve(args[1:], stdout, stderr)
	}
	fmt.Fprintf(stderr, "unknown command %q\nusage: styli serve --vault DIR | styli version\n", args[0])
	return 2
}

func serve(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	fs.SetOutput(stderr)
	cfg := app.Config{}
	fs.StringVar(&cfg.Vault, "vault", "", "the notes folder (must exist)")
	fs.StringVar(&cfg.Addr, "addr", app.DefaultAddr, "listen address; loopback only until login exists")
	fs.StringVar(&cfg.SyncAddr, "sync-addr", ":7879", "where paired devices sync (mutual TLS); empty turns sync off")
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
	cfg.Ready = func(addr string) { fmt.Fprintf(stdout, "Styli is running at http://%s\n", addr) }

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := app.Run(ctx, cfg); err != nil {
		fmt.Fprintln(stderr, "serve:", err)
		return 1
	}
	return 0
}
