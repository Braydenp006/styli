package syncsrv

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
)

// Mutual TLS with pinned keys. There is no certificate authority: the atrium
// lets a device in only if its key's fingerprint is in the paired-device list,
// and a device talks only to the atrium whose fingerprint it saved when it
// paired. TLS itself is Go's standard library, TLS 1.3 only.

var (
	errNoCert      = errors.New("sync: no certificate")
	errUnknown     = errors.New("sync: this device is not paired, or was revoked")
	errWrongAtrium = errors.New("sync: this is not the atrium this device paired with")
)

// leafID returns the fingerprint of the certificate the other side sent.
// TLS has already checked that the other side holds that certificate's key.
func leafID(raw [][]byte) (string, error) {
	if len(raw) == 0 {
		return "", errNoCert
	}
	c, err := x509.ParseCertificate(raw[0])
	if err != nil {
		return "", err
	}
	return devicekey.Fingerprint(c), nil
}

// ServerTLS is the atrium's TLS setup: a client certificate is required, and
// the handshake fails unless its key is paired and not revoked.
func ServerTLS(key *devicekey.Key, trusted func(id string) bool) *tls.Config {
	return &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{key.Cert},
		ClientAuth:   tls.RequireAnyClientCert, // any certificate: trust is decided below, by fingerprint
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			id, err := leafID(raw)
			if err != nil {
				return err
			}
			if !trusted(id) {
				return errUnknown
			}
			return nil
		},
	}
}

// ClientTLS is a device's TLS setup for talking to its atrium, pinned to the
// atrium's fingerprint.
func ClientTLS(key *devicekey.Key, atriumID string) *tls.Config {
	return &tls.Config{
		MinVersion:   tls.VersionTLS13,
		Certificates: []tls.Certificate{key.Cert},
		// The usual name-and-authority check is replaced by pinning: the
		// atrium's certificate is self-signed, and only its key matters.
		InsecureSkipVerify: true,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			id, err := leafID(raw)
			if err != nil {
				return err
			}
			if id != atriumID {
				return errWrongAtrium
			}
			return nil
		},
	}
}

type ctxKey int

const (
	deviceKey ctxKey = iota
	connKey
)

// DeviceID returns the paired device making the request.
func DeviceID(ctx context.Context) string {
	id, _ := ctx.Value(deviceKey).(string)
	return id
}

// Server serves the sync API behind mutual TLS.
type Server struct {
	srv  *http.Server
	devs *devices.List
	logf func(string, ...any)

	mu    sync.Mutex
	conns map[string]map[net.Conn]bool // device ID → its open connections
	owner map[net.Conn]string
}

// NewServer builds the sync server. It sets devs.OnRevoke, so revoking a
// device also closes its open connections.
func NewServer(d Deps, key *devicekey.Key, devs *devices.List) *Server {
	if d.Logf == nil {
		d.Logf = log.Printf
	}
	s := &Server{devs: devs, logf: d.Logf, conns: map[string]map[net.Conn]bool{}, owner: map[net.Conn]string{}}
	s.srv = &http.Server{
		Handler: s.check(newHandler(d)),
		TLSConfig: ServerTLS(key, func(id string) bool {
			// A key that is not paired gets through the handshake only while a
			// pairing is open; check below then keeps it to the pairing endpoints.
			return devs.Trusted(id) || (d.Pairing != nil && d.Pairing.Admits(id))
		}),
		// No ReadTimeout or WriteTimeout: a large file must be able to stream,
		// and only paired devices get past the handshake.
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		ConnContext:       func(ctx context.Context, c net.Conn) context.Context { return context.WithValue(ctx, connKey, c) },
		ConnState:         s.connState,
		ErrorLog:          log.New(logWriter(d.Logf), "sync: ", 0),
	}
	devs.OnRevoke = s.closeDevice
	return s
}

// Serve accepts connections on ln until Shutdown.
func (s *Server) Serve(ln net.Listener) error {
	err := s.srv.ServeTLS(ln, "", "")
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Shutdown stops the server, waiting for requests in progress until ctx ends.
func (s *Server) Shutdown(ctx context.Context) error {
	err := s.srv.Shutdown(ctx)
	if err != nil {
		s.srv.Close()
	}
	return err
}

// check runs on every request, not only at the handshake, so a revoked
// device is turned away even on a connection it opened before.
func (s *Server) check(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
			fail(w, http.StatusForbidden, "not_paired", errNoCert.Error())
			return
		}
		id := devicekey.Fingerprint(r.TLS.PeerCertificates[0])
		// Note the connection before checking, so a revoke that lands in
		// between still finds it and closes it.
		if c, ok := r.Context().Value(connKey).(net.Conn); ok {
			s.track(id, c)
		}
		ctx := context.WithValue(r.Context(), deviceKey, id)
		if strings.HasPrefix(r.URL.Path, "/pair/v1/") {
			next.ServeHTTP(w, r.WithContext(ctx)) // the pairing endpoints check the device themselves
			return
		}
		if !s.devs.Trusted(id) {
			w.Header().Set("Connection", "close")
			fail(w, http.StatusForbidden, "not_paired", errUnknown.Error())
			return
		}
		s.devs.Seen(id, time.Now())
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *Server) track(id string, c net.Conn) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.owner[c] == id {
		return
	}
	if s.conns[id] == nil {
		s.conns[id] = map[net.Conn]bool{}
	}
	s.conns[id][c], s.owner[c] = true, id
}

func (s *Server) connState(c net.Conn, st http.ConnState) {
	if st != http.StateClosed && st != http.StateHijacked {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if id, ok := s.owner[c]; ok {
		delete(s.conns[id], c)
		if len(s.conns[id]) == 0 {
			delete(s.conns, id)
		}
		delete(s.owner, c)
	}
}

// closeDevice cuts every open connection of a revoked device, which also
// ends any request it has waiting.
func (s *Server) closeDevice(id string) {
	s.mu.Lock()
	var cs []net.Conn
	for c := range s.conns[id] {
		cs = append(cs, c)
	}
	s.mu.Unlock()
	for _, c := range cs {
		c.Close()
	}
	if len(cs) > 0 {
		s.logf("sync: closed %d connections of a revoked device", len(cs))
	}
}

// logWriter sends the HTTP server's own messages (such as a refused
// handshake) to the app's log.
type logWriter func(string, ...any)

func (f logWriter) Write(p []byte) (int, error) {
	f("%s", strings.TrimRight(string(p), "\n"))
	return len(p), nil
}
