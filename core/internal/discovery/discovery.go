// Package discovery lets an atrium announce itself on the local network and a
// device find it (mDNS / DNS-SD, service _styli._tcp). Finding an atrium here
// grants no trust: a device only talks to the key it saved at pairing, and
// pairing proves the key with the magic words. An advert nobody should have
// sent costs a failed connection, nothing more.
package discovery

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/hashicorp/mdns"
)

// Service is the DNS-SD service type an atrium announces.
const Service = "_styli._tcp"

var idRe = regexp.MustCompile(`^[0-9a-f]{64}$`)

// Found is an atrium seen on the network.
type Found struct {
	ID    string   // the atrium's key fingerprint, from its advert
	Name  string   // the advert's instance name, "styli-" and the start of the ID
	Addrs []string // host:port to try, sorted
}

// iface is a network interface worth using, with the addresses to announce on it.
type iface struct {
	net.Interface
	ips []net.IP
}

// quiet keeps the library's own logging out of the terminal.
var quiet = log.New(io.Discard, "", 0)

// usableInterfaces lists the interfaces that are up, can multicast, are not
// loopback, and have an address worth announcing: not loopback, and not
// link-local (an IPv6 fe80:: address needs a zone to be reached).
func usableInterfaces() []iface {
	all, _ := net.Interfaces()
	var out []iface
	for _, f := range all {
		if f.Flags&net.FlagUp == 0 || f.Flags&net.FlagMulticast == 0 || f.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := f.Addrs()
		var ips []net.IP
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.IsGlobalUnicast() {
				ips = append(ips, n.IP)
			}
		}
		if len(ips) > 0 {
			out = append(out, iface{f, ips})
		}
	}
	return out
}

// Announce makes this atrium findable on every usable network interface until
// ctx ends. The advert holds only the atrium's key ID (already shown to anyone
// who opens a TLS connection) and the sync port: no vault name, no note data.
// It fails only if it could not announce on any interface.
// shortcut: the interfaces are read once; a network joined later is not announced
// on until the atrium restarts.
func Announce(ctx context.Context, id string, port int, logf func(string, ...any)) error {
	return announceOn(ctx, usableInterfaces(), id, port, logf)
}

func announceOn(ctx context.Context, ifs []iface, id string, port int, logf func(string, ...any)) error {
	if !idRe.MatchString(id) {
		return errors.New("discovery: no atrium key ID to announce")
	}
	name := "styli-" + id[:8]
	var servers []*responder
	for _, f := range ifs {
		svc, err := mdns.NewMDNSService(name, Service, "", name+".local.", port, f.ips, []string{"v=1", "id=" + id})
		if err == nil {
			var srv *responder
			if srv, err = newResponder(svc, &f.Interface); err == nil {
				servers = append(servers, srv)
				continue
			}
		}
		logf("discovery: cannot announce on %s: %v", f.Name, err)
	}
	if len(servers) == 0 {
		return errors.New("discovery: no network interface could announce this atrium")
	}
	go func() {
		<-ctx.Done()
		for _, s := range servers {
			s.Shutdown()
		}
	}()
	return nil
}

// Browse looks for atriums for up to timeout and returns the ones it saw.
func Browse(ctx context.Context, timeout time.Duration) ([]Found, error) {
	return browseOn(ctx, usableInterfaces(), timeout)
}

func browseOn(ctx context.Context, ifs []iface, timeout time.Duration) ([]Found, error) {
	if len(ifs) == 0 {
		return nil, errors.New("discovery: no network interface to look on")
	}
	entries := make(chan *mdns.ServiceEntry, 64)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var errs []error
	for _, f := range ifs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			err := mdns.QueryContext(ctx, &mdns.QueryParam{Service: Service, Timeout: timeout, Interface: &f.Interface, Entries: entries, Logger: quiet})
			if err != nil {
				mu.Lock()
				errs = append(errs, fmt.Errorf("%s: %w", f.Name, err))
				mu.Unlock()
			}
		}()
	}
	go func() { wg.Wait(); close(entries) }()

	// The library goes on filling in an entry after it has sent it (more address
	// records can arrive for the same atrium), so no entry is read until every
	// query has returned: the channel closes only then.
	var got []*mdns.ServiceEntry
	for e := range entries {
		got = append(got, e)
	}
	byID := map[string]*Found{}
	for _, e := range got {
		id, addrs := parse(e)
		if id == "" {
			continue
		}
		f := byID[id]
		if f == nil {
			f = &Found{ID: id, Name: "styli-" + id[:8]}
			byID[id] = f
		}
		for _, a := range addrs {
			if !slices.Contains(f.Addrs, a) {
				f.Addrs = append(f.Addrs, a)
			}
		}
	}
	out := make([]Found, 0, len(byID))
	for _, f := range byID {
		slices.Sort(f.Addrs)
		out = append(out, *f)
	}
	slices.SortFunc(out, func(a, b Found) int { return strings.Compare(a.ID, b.ID) })
	if len(out) == 0 && len(errs) > 0 {
		return nil, errors.Join(errs...)
	}
	return out, nil
}

// parse reads an advert: its atrium ID (empty if the advert is not a valid
// one, which is ignored) and the addresses to try. It trusts nothing in it.
func parse(e *mdns.ServiceEntry) (id string, addrs []string) {
	var version string
	for _, kv := range e.InfoFields {
		switch k, v, _ := strings.Cut(kv, "="); k {
		case "v":
			version = v
		case "id":
			id = v
		}
	}
	if version != "1" || !idRe.MatchString(id) || e.Port < 1 || e.Port > 65535 {
		return "", nil
	}
	port := strconv.Itoa(e.Port)
	for _, ip := range []net.IP{e.AddrV4, e.Addr, ipOf(e.AddrV6IPAddr)} {
		if ip != nil && ip.IsGlobalUnicast() { // not link-local, not loopback
			if a := net.JoinHostPort(ip.String(), port); !slices.Contains(addrs, a) {
				addrs = append(addrs, a)
			}
		}
	}
	if len(addrs) == 0 {
		return "", nil
	}
	return id, addrs
}

func ipOf(a *net.IPAddr) net.IP {
	if a == nil || a.Zone != "" { // a zoned address is link-local
		return nil
	}
	return a.IP
}
