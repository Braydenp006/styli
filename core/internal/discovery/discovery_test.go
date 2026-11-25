package discovery

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/hashicorp/mdns"
)

var goodID = strings.Repeat("ab", 32)

func entry(txt []string, port int, v4 string) *mdns.ServiceEntry {
	e := &mdns.ServiceEntry{InfoFields: txt, Port: port}
	if v4 != "" {
		e.AddrV4 = net.ParseIP(v4)
	}
	return e
}

func TestParseAcceptsAGoodAdvertAndNothingElse(t *testing.T) {
	good := []string{"v=1", "id=" + goodID}
	if id, addrs := parse(entry(good, 7879, "192.168.1.20")); id != goodID || len(addrs) != 1 || addrs[0] != "192.168.1.20:7879" {
		t.Fatalf("%q %v", id, addrs)
	}
	v6 := entry(good, 7879, "")
	v6.AddrV6IPAddr = &net.IPAddr{IP: net.ParseIP("2001:db8::5")}
	if _, addrs := parse(v6); len(addrs) != 1 || addrs[0] != "[2001:db8::5]:7879" {
		t.Fatalf("%v", addrs)
	}

	bad := map[string]*mdns.ServiceEntry{
		"no version":        entry([]string{"id=" + goodID}, 7879, "192.168.1.20"),
		"a newer version":   entry([]string{"v=2", "id=" + goodID}, 7879, "192.168.1.20"),
		"no id":             entry([]string{"v=1"}, 7879, "192.168.1.20"),
		"short id":          entry([]string{"v=1", "id=abcd"}, 7879, "192.168.1.20"),
		"id not hex":        entry([]string{"v=1", "id=" + strings.Repeat("zz", 32)}, 7879, "192.168.1.20"),
		"upper-case id":     entry([]string{"v=1", "id=" + strings.ToUpper(goodID)}, 7879, "192.168.1.20"),
		"port zero":         entry(good, 0, "192.168.1.20"),
		"port too big":      entry(good, 70000, "192.168.1.20"),
		"loopback":          entry(good, 7879, "127.0.0.1"),
		"link-local":        entry(good, 7879, "169.254.3.4"),
		"no address":        entry(good, 7879, ""),
		"a hostile id text": entry([]string{"v=1", "id=" + goodID + "\n" + goodID}, 7879, "192.168.1.20"),
	}
	zoned := entry(good, 7879, "")
	zoned.AddrV6IPAddr = &net.IPAddr{IP: net.ParseIP("fe80::1"), Zone: "eth0"}
	bad["zoned link-local v6"] = zoned
	for name, e := range bad {
		if id, addrs := parse(e); id != "" || len(addrs) != 0 {
			t.Errorf("%s was accepted: %q %v", name, id, addrs)
		}
	}
}

func TestAnnounceNeedsAKeyID(t *testing.T) {
	if err := announceOn(context.Background(), usableInterfaces(), "short", 7879, t.Logf); err == nil {
		t.Fatal("announced with no key ID")
	}
	if _, err := browseOn(context.Background(), nil, time.Second); err == nil {
		t.Fatal("browsed with no interface")
	}
}

// A real round trip over multicast on one interface: what Announce says is
// what Browse finds. It needs a network interface that can multicast.
func TestAnnouncedAtriumIsFound(t *testing.T) {
	ifs := usableInterfaces()
	if len(ifs) == 0 {
		t.Skip("no network interface that can multicast")
	}
	ctx, stop := context.WithCancel(context.Background())
	defer stop()
	if err := announceOn(ctx, ifs[:1], goodID, 7879, t.Logf); err != nil {
		t.Skipf("this machine cannot announce: %v", err)
	}
	found, err := browseOn(ctx, ifs[:1], 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	// Other tests (other packages run at the same time) announce their own
	// atriums on this network, so look for this one's ID.
	var f *Found
	for i := range found {
		if found[i].ID == goodID {
			f = &found[i]
		}
	}
	if f == nil || f.Name != "styli-abababab" || len(f.Addrs) == 0 {
		t.Fatalf("found %+v", found)
	}
	for _, a := range f.Addrs {
		if _, p, err := net.SplitHostPort(a); err != nil || p != "7879" {
			t.Fatalf("address %q", a)
		}
	}
}
