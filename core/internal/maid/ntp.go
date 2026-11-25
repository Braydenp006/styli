package maid

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"time"
)

// A tiny SNTP client (RFC 4330), enough to answer "is this machine's clock
// roughly right?". The maid only needs minutes of accuracy, so there is no
// filtering or averaging. Note: NTP is not authenticated; that is why the
// maid's circuit breaker stays on even when this check passes.

const ntpEpochOffset = 2208988800 // seconds from 1900 (NTP) to 1970 (Unix)

// toNTP turns a time into the 64-bit NTP format: seconds since 1900 in the
// high 32 bits (wrapping in 2036, as the format does), fraction in the low.
func toNTP(t time.Time) uint64 {
	secs := uint64(t.Unix()+ntpEpochOffset) & 0xffffffff
	frac := (uint64(t.Nanosecond()) << 32) / 1_000_000_000
	return secs<<32 | frac
}

// fromNTP reverses toNTP. Seconds with the top bit clear belong to the era
// that starts in February 2036 (RFC 4330, section 3).
func fromNTP(v uint64) time.Time {
	secs := int64(v >> 32)
	if secs < 0x80000000 {
		secs += 1 << 32
	}
	nanos := int64(((v & 0xffffffff) * 1_000_000_000) >> 32)
	return time.Unix(secs-ntpEpochOffset, nanos)
}

// clockOffset asks server (host or host:port, port 123 by default) for the
// time and returns how far it is ahead of now() (negative: our clock is ahead).
func clockOffset(ctx context.Context, server string, now func() time.Time, timeout time.Duration) (time.Duration, error) {
	addr := server
	if _, _, err := net.SplitHostPort(server); err != nil {
		addr = net.JoinHostPort(server, "123")
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var d net.Dialer
	conn, err := d.DialContext(ctx, "udp", addr)
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		conn.SetDeadline(deadline)
	}

	req := make([]byte, 48)
	req[0] = 0x23 // leap indicator 0, version 4, mode 3 (client)
	t1 := now()
	binary.BigEndian.PutUint64(req[40:], toNTP(t1)) // echoed back, so we can match the reply
	if _, err := conn.Write(req); err != nil {
		return 0, err
	}
	resp := make([]byte, 512)
	n, err := conn.Read(resp)
	t4 := now()
	if err != nil {
		return 0, err
	}
	resp = resp[:n]
	if n < 48 {
		return 0, errors.New("time server: reply too short")
	}
	leap, mode, stratum := resp[0]>>6, resp[0]&7, resp[1]
	switch {
	case mode != 4:
		return 0, fmt.Errorf("time server: not a server reply (mode %d)", mode)
	case leap == 3 || stratum == 0 || stratum > 15:
		return 0, errors.New("time server: server's own clock is not synchronised")
	case !bytes.Equal(resp[24:32], req[40:48]):
		return 0, errors.New("time server: reply does not match our request")
	case binary.BigEndian.Uint64(resp[40:48]) == 0:
		return 0, errors.New("time server: reply has no time in it")
	}
	t2 := fromNTP(binary.BigEndian.Uint64(resp[32:40])) // server received
	t3 := fromNTP(binary.BigEndian.Uint64(resp[40:48])) // server sent
	return (t2.Sub(t1) + t3.Sub(t4)) / 2, nil
}
