package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"

	"styli/core/internal/devicekey"
	"styli/core/internal/discovery"
	"styli/core/internal/pairing"
	"styli/core/internal/settings"
)

// stdin is where join reads the words and answers; tests replace it.
var stdin io.Reader = os.Stdin

// DefaultSyncPort is the atrium's sync port when --atrium leaves it out.
const DefaultSyncPort = "7879"

// browse looks for atriums on the local network; tests replace it.
var browse = discovery.Browse

// addrList is a flag that can be given more than once.
type addrList []string

func (l *addrList) String() string { return strings.Join(*l, ", ") }
func (l *addrList) Set(v string) error {
	if _, _, err := net.SplitHostPort(v); err != nil {
		v = net.JoinHostPort(strings.Trim(v, "[]"), DefaultSyncPort)
	}
	*l = append(*l, v)
	return nil
}

// reachable returns the first of addrs that accepts a connection (the first
// one if none does, so the error names it).
func reachable(addrs []string) string {
	for _, a := range addrs {
		if c, err := net.DialTimeout("tcp", a, 3*time.Second); err == nil {
			c.Close()
			return a
		}
	}
	return addrs[0]
}

// join pairs this device with an atrium: the person types the magic words the
// atrium shows, then checks the emoji match on both screens.
func join(args []string, in io.Reader, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("join", flag.ContinueOnError)
	fs.SetOutput(stderr)
	var vault, configDir, name string
	var given addrList
	fs.StringVar(&vault, "vault", "", "this device's notes folder (must exist)")
	fs.StringVar(&configDir, "config", "", "this device's settings folder (default: user config folder)")
	fs.Var(&given, "atrium", "the atrium's address, host or host:port (port "+DefaultSyncPort+" if left out); give it more than once for other ways in, such as a VPN name. Left out, the atrium is looked for on this network")
	fs.StringVar(&name, "name", "", "what to call this device on the atrium (default: its device name, or the computer's name)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if vault == "" {
		fmt.Fprintln(stderr, "join: --vault is required")
		return 2
	}
	if fi, err := os.Stat(vault); err != nil || !fi.IsDir() {
		fmt.Fprintf(stderr, "join: vault folder %q does not exist; create it first\n", vault)
		return 1
	}
	var err error
	if configDir == "" {
		if configDir, err = settings.DeviceDir(vault); err != nil {
			fmt.Fprintln(stderr, "join:", err)
			return 1
		}
	}
	set, err := settings.Open(vault, configDir)
	if err != nil {
		fmt.Fprintln(stderr, "join:", err)
		return 1
	}
	if a := set.Device().Atrium; a != nil {
		fmt.Fprintf(stderr, "join: this device is already paired with the atrium at %s. Pairing it again is not supported yet.\n", a.Addr)
		return 1
	}
	if name == "" {
		name = set.Device().Name
	}
	if name == "" {
		name, _ = os.Hostname()
	}
	key, err := devicekey.Load(configDir)
	if err != nil {
		fmt.Fprintln(stderr, "join:", err)
		return 1
	}

	// Which atrium: the one named, or the one that answers on this network. An
	// advert proves nothing; the words and the key check still decide who this
	// device ends up paired with.
	candidates := []string(given)
	if len(candidates) == 0 {
		fmt.Fprintln(stdout, "Looking for an atrium on this network…")
		found, err := browse(context.Background(), 3*time.Second)
		switch {
		case len(found) == 0:
			fmt.Fprintln(stderr, "join: no atrium was found on this network. Check that it is running (and not started with --no-announce) on the same network, or give its address with --atrium.")
			if err != nil {
				fmt.Fprintln(stderr, "join:", err)
			}
			return 1
		case len(found) > 1:
			fmt.Fprintln(stderr, "join: more than one atrium was found; run again with --atrium and the address of yours:")
			for _, f := range found {
				fmt.Fprintf(stderr, "    %s at %s\n", f.Name, strings.Join(f.Addrs, ", "))
			}
			return 1
		}
		fmt.Fprintf(stdout, "Found the atrium %s.\n", found[0].Name)
		candidates = found[0].Addrs
	}
	atrium := reachable(candidates)

	r := bufio.NewReader(in)
	ask := func(prompt string) string {
		fmt.Fprint(stdout, prompt)
		line, _ := r.ReadString('\n')
		return strings.TrimSpace(line)
	}
	var words string
	for tries := 0; ; tries++ {
		typed := ask("Type the magic words shown on the atrium: ")
		if words, err = pairing.NormalizeWords(typed); err == nil {
			break
		}
		if tries == 2 || typed == "" {
			fmt.Fprintln(stderr, "join:", err)
			return 1
		}
		fmt.Fprintln(stdout, "Those aren't four of the magic words. Check them and try again (they have not been used up).")
	}

	compare := func(row []pairing.Symbol) bool {
		fmt.Fprintln(stdout, "\nThe words matched. Check that the atrium shows these six symbols, in this order:")
		var chars, names []string
		for _, s := range row {
			chars = append(chars, s.Char)
			names = append(names, s.Name)
		}
		fmt.Fprintf(stdout, "\n    %s\n    (%s)\n\n", strings.Join(chars, "  "), strings.Join(names, ", "))
		return strings.EqualFold(ask("Do they match? [y/N] "), "y")
	}

	ctx, stop := context.WithTimeout(context.Background(), 2*pairing.Window)
	defer stop()
	for {
		fmt.Fprintf(stdout, "Connecting to the atrium at %s…\n", atrium)
		atriumID, err := pairing.Join(ctx, atrium, key, words, name, compare)
		switch {
		case err == nil:
			if err := set.SetAtrium(&settings.AtriumLink{ID: atriumID, Addr: atrium, Addrs: []string(given), PairedAt: time.Now().UTC()}); err != nil {
				fmt.Fprintln(stderr, "join: paired, but this device could not save it:", err)
				return 1
			}
			fmt.Fprintf(stdout, "Paired. This device is %q on the atrium at %s.\n", name, atrium)
			return 0
		case errors.Is(err, pairing.ErrRevokedKey):
			// The words were not used up: the atrium refused the key first.
			if !strings.EqualFold(ask("This device was removed from the atrium before. Make it a new key and try again? [y/N] "), "y") {
				return 1
			}
			if err := devicekey.Retire(configDir); err != nil {
				fmt.Fprintln(stderr, "join:", err)
				return 1
			}
			if key, err = devicekey.Load(configDir); err != nil {
				fmt.Fprintln(stderr, "join:", err)
				return 1
			}
		case errors.Is(err, pairing.ErrWrongWords):
			fmt.Fprintln(stderr, "join: the words did not match. They are used up now: start a new pairing on the atrium for new words.")
			return 1
		case errors.Is(err, pairing.ErrCancelled):
			fmt.Fprintln(stderr, "join: pairing was cancelled. Nothing was saved.")
			return 1
		case errors.Is(err, pairing.ErrNoPairing):
			fmt.Fprintln(stderr, "join: the atrium has no pairing open. On the atrium, open Settings, Devices, and choose Pair new device.")
			return 1
		case errors.Is(err, pairing.ErrKnownKey):
			fmt.Fprintln(stderr, "join: the atrium already knows this device's key. Remove it there first.")
			return 1
		default:
			fmt.Fprintln(stderr, "join:", err)
			return 1
		}
	}
}
