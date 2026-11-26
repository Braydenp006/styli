package discovery

import (
	"errors"
	"net"

	"github.com/hashicorp/mdns"
	"github.com/miekg/dns"
	"golang.org/x/net/ipv4"
	"golang.org/x/net/ipv6"
)

// The answering side of mDNS (RFC 6762), written here because hashicorp/mdns's
// server answers only by unicast and with the system's IP TTL, which Apple's
// resolver drops. Records still come from the library's MDNSService.
//
// shortcut: no probing for name clashes, no announcing at start, no known-answer
// suppression and no rate limit; one atrium on a home network does not need
// them. Add them if two atriums ever share a network or Apple devices complain.

const (
	mdnsPort  = 5353
	quBit     = 1 << 15 // in a question's class: the asker wants a unicast reply
	flushBit  = 1 << 15 // in an answer's class: replace what you cached for this name
	legacyTTL = 10      // seconds, for answers to one-shot queries (RFC 6762 6.7)
)

var (
	groupV4 = &net.UDPAddr{IP: net.IPv4(224, 0, 0, 251), Port: mdnsPort}
	groupV6 = &net.UDPAddr{IP: net.ParseIP("ff02::fb"), Port: mdnsPort}
)

// packetConn is what ipv4.PacketConn and ipv6.PacketConn have in common for
// us: read with the arrival interface, and write.
type packetConn interface {
	readFrom(b []byte) (n, ifIndex int, src net.Addr, err error)
	writeTo(b []byte, dst net.Addr)
}

// responder answers mDNS questions on one interface, over IPv4 and IPv6.
type responder struct {
	zone  *mdns.MDNSService
	ifi   *net.Interface
	conns []*net.UDPConn
}

// newResponder starts answering on ifi. It fails only if neither IPv4 nor IPv6
// could listen there.
func newResponder(zone *mdns.MDNSService, ifi *net.Interface) (*responder, error) {
	r := &responder{zone: zone, ifi: ifi}
	var errs []error
	if c, err := net.ListenMulticastUDP("udp4", ifi, groupV4); err == nil {
		p := ipv4.NewPacketConn(c)
		// 255 on every answer, unicast or multicast: receivers check it to know the
		// answer came from this link. Loopback on, so mDNS tools on this machine
		// (Avahi, our tests) hear the answers too.
		err = errors.Join(p.SetMulticastTTL(255), p.SetTTL(255), p.SetMulticastLoopback(true))
		// Not every system gives the arrival interface; without it nothing is filtered.
		_ = p.SetControlMessage(ipv4.FlagInterface, true)
		if err != nil {
			c.Close()
			errs = append(errs, err)
		} else {
			r.conns = append(r.conns, c)
			go r.serve(v4conn{p}, groupV4)
		}
	} else {
		errs = append(errs, err)
	}
	if c, err := net.ListenMulticastUDP("udp6", ifi, groupV6); err == nil {
		p := ipv6.NewPacketConn(c)
		err = errors.Join(p.SetMulticastHopLimit(255), p.SetHopLimit(255), p.SetMulticastLoopback(true))
		_ = p.SetControlMessage(ipv6.FlagInterface, true)
		if err != nil {
			c.Close()
			errs = append(errs, err)
		} else {
			r.conns = append(r.conns, c)
			go r.serve(v6conn{p}, groupV6)
		}
	} else {
		errs = append(errs, err)
	}
	if len(r.conns) == 0 {
		return nil, errors.Join(errs...)
	}
	return r, nil
}

func (r *responder) Shutdown() {
	for _, c := range r.conns {
		c.Close()
	}
}

// serve reads questions until the connection is closed.
func (r *responder) serve(c packetConn, group *net.UDPAddr) {
	buf := make([]byte, 9000)
	for {
		n, ifIndex, src, err := c.readFrom(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		// On Linux a socket on port 5353 also gets packets from other interfaces;
		// answering those would offer addresses the asker cannot reach.
		if ifIndex != 0 && ifIndex != r.ifi.Index {
			continue
		}
		from, ok := src.(*net.UDPAddr)
		if !ok {
			continue
		}
		for _, out := range r.answer(buf[:n], from.Port != mdnsPort) {
			dst := group
			if out.unicast {
				dst = from
			}
			if b, err := out.msg.Pack(); err == nil {
				c.writeTo(b, dst) // a lost answer is asked for again
			}
		}
	}
}

type reply struct {
	msg     *dns.Msg
	unicast bool
}

// answer works out the replies to one packet. A legacy query (one not sent from
// port 5353, such as dig or a one-shot resolver) gets one unicast reply that
// repeats its ID and questions. Otherwise answers go to the multicast group,
// except those to questions with the QU bit, which go back to the asker.
func (r *responder) answer(packet []byte, legacy bool) []reply {
	var q dns.Msg
	if q.Unpack(packet) != nil || q.Response || q.Opcode != dns.OpcodeQuery || q.Rcode != 0 {
		return nil
	}
	var multi, uni []dns.RR
	for _, question := range q.Question {
		unicast := legacy || question.Qclass&quBit != 0
		question.Qclass &^= quBit
		if question.Qclass != dns.ClassINET && question.Qclass != dns.ClassANY {
			continue
		}
		for _, rr := range r.zone.Records(question) {
			rr = dns.Copy(rr)
			h := rr.Header()
			if legacy {
				h.Ttl = min(h.Ttl, legacyTTL)
			} else if h.Rrtype != dns.TypePTR { // a PTR is shared: many may answer it
				h.Class |= flushBit
			}
			if unicast {
				uni = append(uni, rr)
			} else {
				multi = append(multi, rr)
			}
		}
	}
	var out []reply
	if len(multi) > 0 {
		out = append(out, reply{&dns.Msg{MsgHdr: dns.MsgHdr{Response: true, Authoritative: true}, Answer: multi}, false})
	}
	if len(uni) > 0 {
		m := &dns.Msg{MsgHdr: dns.MsgHdr{Response: true, Authoritative: true}, Answer: uni}
		if legacy {
			m.Id, m.Question = q.Id, q.Question
		}
		out = append(out, reply{m, true})
	}
	return out
}

type v4conn struct{ *ipv4.PacketConn }

func (c v4conn) readFrom(b []byte) (int, int, net.Addr, error) {
	n, cm, src, err := c.ReadFrom(b)
	if cm == nil {
		return n, 0, src, err
	}
	return n, cm.IfIndex, src, err
}

func (c v4conn) writeTo(b []byte, dst net.Addr) { c.PacketConn.WriteTo(b, nil, dst) }

type v6conn struct{ *ipv6.PacketConn }

func (c v6conn) readFrom(b []byte) (int, int, net.Addr, error) {
	n, cm, src, err := c.ReadFrom(b)
	if cm == nil {
		return n, 0, src, err
	}
	return n, cm.IfIndex, src, err
}

func (c v6conn) writeTo(b []byte, dst net.Addr) { c.PacketConn.WriteTo(b, nil, dst) }
