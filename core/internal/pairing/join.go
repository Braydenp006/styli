package pairing

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"filippo.io/cpace"

	"styli/core/internal/devicekey"
)

// Join pairs this device with the atrium at addr (host:port of its sync port)
// using the magic words. compare shows the emoji to the person and reports
// whether they match the atrium's screen. It returns the atrium's key ID,
// which the caller saves: from then on sync only talks to that key.
func Join(ctx context.Context, addr string, key *devicekey.Key, typedWords, name string, compare func([]Symbol) bool) (string, error) {
	words, err := NormalizeWords(typedWords)
	if err != nil {
		return "", err
	}
	if !ValidName(name) {
		return "", ErrBadName
	}

	// The atrium's key is not known yet: the words are what prove it is the
	// right one. TLS still checks that the atrium holds the key it shows.
	var atriumID string
	conf := &tls.Config{
		MinVersion:         tls.VersionTLS13,
		Certificates:       []tls.Certificate{key.Cert},
		InsecureSkipVerify: true, // replaced by the PAKE below, which binds this key
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			id, err := certID(raw)
			atriumID = id
			return err
		},
	}
	d := tls.Dialer{NetDialer: &net.Dialer{Timeout: 10 * time.Second}, Config: conf}
	c, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return "", fmt.Errorf("pairing: cannot reach the atrium at %s: %w", addr, err)
	}
	conn := c.(*tls.Conn)
	defer conn.Close()
	cs := conn.ConnectionState()
	exporter, err := cs.ExportKeyingMaterial(ExporterLabel, nil, ExporterLen)
	if err != nil {
		return "", err
	}

	// Every request goes over this one connection, because the PAKE is bound
	// to it. If it drops, pairing has to start again.
	used := false
	cl := &http.Client{Transport: &http.Transport{
		DialTLSContext: func(context.Context, string, string) (net.Conn, error) {
			if used {
				return nil, errors.New("pairing: the connection to the atrium was lost; start again")
			}
			used = true
			return conn, nil
		},
	}}
	p := peer{cl, "https://" + addr}

	msgA, st, err := cpace.Start(words, contextInfo(key.ID, atriumID, exporter))
	if err != nil {
		return "", err
	}
	var started struct {
		MsgB  []byte `json:"msg_b"`
		Proof []byte `json:"proof"`
	}
	if err := p.call(ctx, "POST", "/pair/v1/start", map[string]any{"msg_a": msgA, "name": name}, &started); err != nil {
		// With no pairing open, the atrium refuses an unknown key during the
		// TLS handshake; TLS 1.3 reports that on the first request.
		// Go reports that alert from the other side as a "remote error"
		// with an unexported type, so it is recognised by its message.
		var op *net.OpError
		if errors.As(err, &op) && op.Op == "remote error" && op.Err != nil && op.Err.Error() == "tls: bad certificate" {
			return "", ErrNoPairing
		}
		return "", err
	}
	shared, err := st.Finish(started.MsgB)
	if err != nil {
		return "", ErrWrongWords
	}
	// Send our proof even if theirs is wrong, so the atrium's screen says at
	// once that the words did not match.
	var confirmed struct {
		Emoji []Symbol `json:"emoji"`
	}
	cerr := p.call(ctx, "POST", "/pair/v1/confirm", map[string]any{"proof": proof(shared, "device")}, &confirmed)
	if !hmac.Equal(started.Proof, proof(shared, "atrium")) {
		return "", ErrWrongWords
	}
	if cerr != nil {
		return "", cerr
	}

	// Our own emoji, worked out here; the atrium's copy must agree.
	mine := emojiFor(exporter, atriumID, key.ID)
	if !sameEmoji(mine, confirmed.Emoji) || !compare(mine) {
		p.call(ctx, "POST", "/pair/v1/cancel", nil, nil)
		return "", ErrCancelled
	}
	// The approval may be refused because the pairing already ended (it was
	// cancelled on the atrium, or timed out): the status below says which.
	p.call(ctx, "POST", "/pair/v1/approve", nil, nil)
	// Wait for the person to approve on the atrium too.
	for {
		var s Status
		if err := p.call(ctx, "GET", "/pair/v1/status", nil, &s); err != nil {
			return "", err
		}
		switch s.Phase {
		case Paired:
			return atriumID, nil
		case Comparing:
		case Cancelled:
			return "", ErrCancelled
		default:
			return "", fmt.Errorf("pairing: %s", s.Reason)
		}
		select {
		case <-ctx.Done():
			p.call(context.Background(), "POST", "/pair/v1/cancel", nil, nil)
			return "", ctx.Err()
		case <-time.After(time.Second):
		}
	}
}

func sameEmoji(a, b []Symbol) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

type peer struct {
	cl   *http.Client
	base string
}

// errorCodes turns the atrium's answers back into this package's errors.
var errorCodes = map[string]error{
	"no_pairing":  ErrNoPairing,
	"wrong_words": ErrWrongWords,
	"known_key":   ErrKnownKey,
	"revoked_key": ErrRevokedKey,
	"bad_name":    ErrBadName,
}

func (p peer) call(ctx context.Context, method, path string, in, out any) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, p.base+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := p.cl.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	b, err := io.ReadAll(io.LimitReader(res.Body, 1<<16))
	if err != nil {
		return err
	}
	if res.StatusCode != http.StatusOK {
		var e struct{ Code, Error string }
		json.Unmarshal(b, &e)
		if known := errorCodes[e.Code]; known != nil {
			return known
		}
		return fmt.Errorf("pairing: the atrium answered %d: %s", res.StatusCode, e.Error)
	}
	if out != nil {
		return json.Unmarshal(b, out)
	}
	return nil
}

// ErrorCode is the code the atrium sends for a pairing error.
func ErrorCode(err error) string {
	for code, e := range errorCodes {
		if errors.Is(err, e) {
			return code
		}
	}
	return ""
}
