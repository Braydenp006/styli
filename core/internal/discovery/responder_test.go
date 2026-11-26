//go:build unix

package discovery

import (
	"context"
	"net"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/hashicorp/mdns"
	"github.com/miekg/dns"
	"golang.org/x/net/ipv4"
)

const testHost = "styli-cdcdcdcd.local."

func testZone(t *testing.T, ips ...net.IP) *mdns.MDNSService {
	t.Helper()
	z, err := mdns.NewMDNSService("styli-cdcdcdcd", Service, "", testHost, 7879, ips, []string{"v=1", "id=" + strings.Repeat("cd", 32)})
	if err != nil {
		t.Fatal(err)
	}
	return z
}

func question(t *testing.T, id uint16, name string, qtype uint16, qu bool) []byte {
	t.Helper()
	m := new(dns.Msg)
	m.Id = id
	m.Question = []dns.Question{{Name: name, Qtype: qtype, Qclass: dns.ClassINET}}
	if qu {
		m.Question[0].Qclass |= quBit
	}
	b, err := m.Pack()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// The rules, without a network: where each answer goes and how it is marked.
func TestAnswerRules(t *testing.T) {
	r := &responder{zone: testZone(t, net.ParseIP("192.0.2.10"))}

	// A normal question: one multicast reply, ID 0, no question echoed.
	out := r.answer(question(t, 7, testHost, dns.TypeA, false), false)
	if len(out) != 1 || out[0].unicast || out[0].msg.Id != 0 || len(out[0].msg.Question) != 0 {
		t.Fatalf("normal question: %+v", out)
	}
	a, ok := out[0].msg.Answer[0].(*dns.A)
	if !ok || !a.A.Equal(net.ParseIP("192.0.2.10")) || a.Hdr.Class != dns.ClassINET|flushBit {
		t.Fatalf("A answer %v", out[0].msg.Answer)
	}

	// QU bit: the same answer, sent back to the asker.
	if out := r.answer(question(t, 7, testHost, dns.TypeA, true), false); len(out) != 1 || !out[0].unicast {
		t.Fatalf("QU question: %+v", out)
	}

	// Browsing (PTR): the shared PTR has no flush bit, the rest of the advert does.
	out = r.answer(question(t, 0, Service+".local.", dns.TypePTR, false), false)
	if len(out) != 1 {
		t.Fatalf("PTR question: %+v", out)
	}
	seen := map[uint16]bool{}
	for _, rr := range out[0].msg.Answer {
		h := rr.Header()
		seen[h.Rrtype] = true
		if flush := h.Class&flushBit != 0; flush == (h.Rrtype == dns.TypePTR) {
			t.Errorf("%s: flush bit %v", dns.TypeToString[h.Rrtype], flush)
		}
	}
	for _, typ := range []uint16{dns.TypePTR, dns.TypeSRV, dns.TypeTXT, dns.TypeA} {
		if !seen[typ] {
			t.Errorf("no %s in the PTR answer", dns.TypeToString[typ])
		}
	}

	// A legacy query: unicast, its ID and question repeated, short TTLs, no
	// flush bit; and the normal answer to the group too, for an asker whose
	// firewall drops the unicast one.
	out = r.answer(question(t, 42, testHost, dns.TypeA, false), true)
	if len(out) != 2 || out[0].unicast || out[0].msg.Id != 0 || !out[1].unicast || out[1].msg.Id != 42 || len(out[1].msg.Question) != 1 {
		t.Fatalf("legacy query: %+v", out)
	}
	if h := out[1].msg.Answer[0].Header(); h.Ttl > legacyTTL || h.Class != dns.ClassINET {
		t.Fatalf("legacy unicast answer header %+v", h)
	}
	if h := out[0].msg.Answer[0].Header(); h.Class != dns.ClassINET|flushBit {
		t.Fatalf("legacy group answer header %+v", h)
	}

	// Nothing for other names, for answers, or for junk.
	other := question(t, 0, "someone-else.local.", dns.TypeA, false)
	resp := new(dns.Msg)
	resp.Response = true
	resp.Question = []dns.Question{{Name: testHost, Qtype: dns.TypeA, Qclass: dns.ClassINET}}
	respBytes, _ := resp.Pack()
	for name, p := range map[string][]byte{"other name": other, "a response": respBytes, "junk": []byte("hello")} {
		if out := r.answer(p, false); len(out) != 0 {
			t.Errorf("%s got %+v", name, out)
		}
	}

	// The zone's own records are not changed by marking answers.
	r.answer(question(t, 0, testHost, dns.TypeA, false), false)
	if rr := r.zone.Records(dns.Question{Name: testHost, Qtype: dns.TypeA, Qclass: dns.ClassINET}); rr[0].Header().Class != dns.ClassINET {
		t.Fatal("zone record was changed")
	}
}

// asker is an mDNS client on port 5353, like Apple's resolver: it hears the
// group and unicast sent to port 5353, and reads each packet's IP TTL.
type asker struct {
	p   *ipv4.PacketConn
	ifi *net.Interface
}

func newAsker(t *testing.T, ifi *net.Interface) *asker {
	t.Helper()
	lc := net.ListenConfig{Control: func(_, _ string, c syscall.RawConn) error {
		var serr error
		err := c.Control(func(fd uintptr) { serr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1) })
		if err != nil {
			return err
		}
		return serr
	}}
	c, err := lc.ListenPacket(context.Background(), "udp4", "0.0.0.0:5353")
	if err != nil {
		t.Skipf("cannot listen on port 5353: %v", err)
	}
	t.Cleanup(func() { c.Close() })
	p := ipv4.NewPacketConn(c)
	if err := p.JoinGroup(ifi, groupV4); err != nil {
		t.Skipf("cannot join the mDNS group: %v", err)
	}
	if err := p.SetMulticastInterface(ifi); err != nil {
		t.Fatal(err)
	}
	p.SetMulticastLoopback(true) // so the responder on this machine hears the question
	if err := p.SetControlMessage(ipv4.FlagTTL|ipv4.FlagDst, true); err != nil {
		t.Skipf("no IP control messages here: %v", err)
	}
	return &asker{p, ifi}
}

// ask sends a question to the group and waits for an answer about testHost.
// It returns the IP TTL the answer arrived with and whether it was sent to the
// group (false: unicast to us).
func (a *asker) ask(t *testing.T, qu bool) (ttl int, toGroup bool) {
	t.Helper()
	if _, err := a.p.WriteTo(question(t, 0, testHost, dns.TypeA, qu), nil, groupV4); err != nil {
		t.Fatal(err)
	}
	return a.wait(t)
}

// wait reads until an answer about testHost arrives on port 5353.
func (a *asker) wait(t *testing.T) (ttl int, toGroup bool) {
	t.Helper()
	a.p.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 9000)
	for {
		n, cm, _, err := a.p.ReadFrom(buf)
		if err != nil {
			t.Fatalf("no answer: %v", err)
		}
		var m dns.Msg
		// Skip our own question coming back, and anything else on the network.
		if m.Unpack(buf[:n]) != nil || !m.Response || len(m.Answer) == 0 || m.Answer[0].Header().Name != testHost {
			continue
		}
		return cm.TTL, cm.Dst.Equal(groupV4.IP)
	}
}

// Over a real interface: a normal question is answered to the group, a QU one
// to the asker, both with IP TTL 255.
func TestResponderOnTheNetwork(t *testing.T) {
	ifs := usableInterfaces()
	if len(ifs) == 0 {
		t.Skip("no network interface that can multicast")
	}
	f := ifs[0]
	r, err := newResponder(testZone(t, f.ips...), &f.Interface)
	if err != nil {
		t.Skipf("cannot answer on %s: %v", f.Name, err)
	}
	defer r.Shutdown()
	a := newAsker(t, &f.Interface)

	if ttl, toGroup := a.ask(t, false); ttl != 255 || !toGroup {
		t.Fatalf("normal question: TTL %d, to the group %v", ttl, toGroup)
	}
	if ttl, toGroup := a.ask(t, true); ttl != 255 || toGroup {
		t.Fatalf("QU question: TTL %d, to the group %v", ttl, toGroup)
	}

	// A question from a random port (as Browse asks) is also answered at the
	// group, where an asker behind a firewall still hears it on port 5353.
	c, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	oneShot := ipv4.NewPacketConn(c)
	oneShot.SetMulticastInterface(&f.Interface)
	oneShot.SetMulticastLoopback(true)
	if _, err := oneShot.WriteTo(question(t, 9, testHost, dns.TypeA, false), nil, groupV4); err != nil {
		t.Fatal(err)
	}
	if ttl, toGroup := a.wait(t); ttl != 255 || !toGroup {
		t.Fatalf("question from a random port: TTL %d, to the group %v", ttl, toGroup)
	}
}
