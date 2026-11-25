package pairing_test

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"testing"
	"time"

	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/pairing"
	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

// atrium is a real atrium sync port with pairing, on loopback.
type atrium struct {
	key  *devicekey.Key
	devs *devices.List
	pair *pairing.Atrium
	addr string
}

func newAtrium(t *testing.T) *atrium {
	t.Helper()
	root := t.TempDir()
	v, err := vault.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	devs, err := devices.Open(root)
	if err != nil {
		t.Fatal(err)
	}
	key, err := devicekey.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	pair := pairing.NewAtrium(key.ID, devs)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := syncsrv.NewServer(syncsrv.Deps{Vault: v, Logf: t.Logf, Pairing: pair}, key, devs)
	go s.Serve(ln)
	t.Cleanup(func() { s.Shutdown(context.Background()) })
	return &atrium{key, devs, pair, ln.Addr().String()}
}

// approveWhenAsked plays the person at the atrium: once the emoji are up, it
// compares them with what the device showed and approves if they match.
func (a *atrium) approveWhenAsked(t *testing.T, shown <-chan []pairing.Symbol, approve bool) {
	go func() {
		row := <-shown
		for range 100 {
			st := a.pair.Status()
			if st.Phase == pairing.Comparing {
				if !approve || !same(st.Emoji, row) {
					a.pair.Cancel("")
				} else {
					a.pair.Approve("")
				}
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}()
}

func same(a, b []pairing.Symbol) bool {
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

func newKey(t *testing.T) *devicekey.Key {
	k, err := devicekey.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return k
}

// join runs the device side; the person at the device says the emoji match.
func join(t *testing.T, addr string, key *devicekey.Key, words string, shown chan<- []pairing.Symbol, match bool) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return pairing.Join(ctx, addr, key, words, "Laptop", func(row []pairing.Symbol) bool {
		shown <- row
		return match
	})
}

func TestPairThenSync(t *testing.T) {
	a := newAtrium(t)
	dev := newKey(t)
	words := a.pair.Open().Words
	shown := make(chan []pairing.Symbol, 1)
	a.approveWhenAsked(t, shown, true)
	got, err := join(t, a.addr, dev, words, shown, true)
	if err != nil {
		t.Fatal(err)
	}
	if got != a.key.ID {
		t.Fatal("the device saved the wrong atrium key")
	}
	if !a.devs.Trusted(dev.ID) {
		t.Fatal("the atrium did not save the device")
	}
	// Sync now works over the pinned keys.
	cl := &http.Client{Transport: &http.Transport{TLSClientConfig: syncsrv.ClientTLS(dev, got)}}
	res, err := cl.Get("https://" + a.addr + "/sync/v1/hello")
	if err != nil || res.StatusCode != 200 {
		t.Fatalf("sync after pairing: %v %v", err, res)
	}
	res.Body.Close()
}

func TestWrongWordsOverTheNetwork(t *testing.T) {
	a := newAtrium(t)
	right := a.pair.Open().Words
	wrong := "amber-anchor-anvil-apple"
	if wrong == right {
		wrong = "arch-arena-armor-arrow"
	}
	shown := make(chan []pairing.Symbol, 1)
	if _, err := join(t, a.addr, newKey(t), wrong, shown, true); !errors.Is(err, pairing.ErrWrongWords) {
		t.Fatalf("wrong words: %v", err)
	}
	if st := a.pair.Status(); st.Phase != pairing.Failed {
		t.Fatalf("atrium shows %s", st.Phase)
	}
	// The right words afterwards: already used up, and the handshake no
	// longer lets an unknown key in.
	if _, err := join(t, a.addr, newKey(t), right, shown, true); err == nil {
		t.Fatal("second try worked")
	}
	if len(a.devs.List()) != 0 {
		t.Fatal("a device was saved")
	}
}

// Someone on the network sits between the device and the atrium, with the
// right words (they relay them), terminating TLS on both sides. The two TLS
// connections differ, so the PAKE fails and nothing is paired.
func TestRelayInTheMiddleIsCaught(t *testing.T) {
	a := newAtrium(t)
	words := a.pair.Open().Words

	mitm := newKey(t)
	target, _ := url.Parse("https://" + a.addr)
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.Transport = &http.Transport{TLSClientConfig: &tls.Config{
		Certificates: []tls.Certificate{mitm.Cert}, InsecureSkipVerify: true, MinVersion: tls.VersionTLS13,
	}}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ps := &http.Server{Handler: proxy, TLSConfig: &tls.Config{
		Certificates: []tls.Certificate{mitm.Cert}, ClientAuth: tls.RequireAnyClientCert, MinVersion: tls.VersionTLS13,
	}}
	go ps.ServeTLS(ln, "", "")
	t.Cleanup(func() { ps.Close() })

	shown := make(chan []pairing.Symbol, 1)
	_, err = join(t, ln.Addr().String(), newKey(t), words, shown, true)
	if !errors.Is(err, pairing.ErrWrongWords) {
		t.Fatalf("relay not caught: %v", err)
	}
	if a.pair.Status().Phase != pairing.Failed || len(a.devs.List()) != 0 {
		t.Fatal("the relay paired")
	}
}

func TestEmojiMismatchSavesNothing(t *testing.T) {
	a := newAtrium(t)
	words := a.pair.Open().Words
	shown := make(chan []pairing.Symbol, 1)
	if _, err := join(t, a.addr, newKey(t), words, shown, false); !errors.Is(err, pairing.ErrCancelled) {
		t.Fatalf("device said no: %v", err)
	}
	if a.pair.Status().Phase != pairing.Cancelled || len(a.devs.List()) != 0 {
		t.Fatal("saved anyway")
	}

	// And the other way: the person at the atrium says they don't match.
	words = a.pair.Open().Words
	shown = make(chan []pairing.Symbol, 1)
	a.approveWhenAsked(t, shown, false)
	if _, err := join(t, a.addr, newKey(t), words, shown, true); !errors.Is(err, pairing.ErrCancelled) {
		t.Fatalf("atrium said no: %v", err)
	}
	if len(a.devs.List()) != 0 {
		t.Fatal("saved anyway")
	}
}

func TestRevokedDeviceNeedsANewKey(t *testing.T) {
	a := newAtrium(t)
	dev := newKey(t)
	a.devs.Add(dev.ID, "Laptop")
	a.devs.Revoke(dev.ID)
	words := a.pair.Open().Words
	shown := make(chan []pairing.Symbol, 1)
	if _, err := join(t, a.addr, dev, words, shown, true); !errors.Is(err, pairing.ErrRevokedKey) {
		t.Fatalf("revoked key: %v", err)
	}
	// The same words still work, with a new key.
	fresh := newKey(t)
	a.approveWhenAsked(t, shown, true)
	if _, err := join(t, a.addr, fresh, words, shown, true); err != nil {
		t.Fatal(err)
	}
	if !a.devs.Trusted(fresh.ID) || a.devs.Trusted(dev.ID) {
		t.Fatal("wrong key trusted")
	}
}

// While a pairing is open, a key that is not paired gets through the TLS
// handshake, but only as far as the pairing endpoints.
func TestOpenPairingDoesNotOpenTheSyncAPI(t *testing.T) {
	a := newAtrium(t)
	stranger := newKey(t)
	cl := &http.Client{Transport: &http.Transport{TLSClientConfig: syncsrv.ClientTLS(stranger, a.key.ID)}}
	get := func() (int, error) {
		res, err := cl.Get("https://" + a.addr + "/sync/v1/changes?since=0")
		if err != nil {
			return 0, err
		}
		res.Body.Close()
		return res.StatusCode, nil
	}
	if _, err := get(); err == nil {
		t.Fatal("stranger got through the handshake with no pairing open")
	}
	a.pair.Open()
	if code, err := get(); err != nil || code != http.StatusForbidden {
		t.Fatalf("stranger during pairing: %d %v", code, err)
	}
}

func TestNoPairingOpen(t *testing.T) {
	a := newAtrium(t)
	shown := make(chan []pairing.Symbol, 1)
	if _, err := join(t, a.addr, newKey(t), "amber-anchor-anvil-apple", shown, true); !errors.Is(err, pairing.ErrNoPairing) {
		t.Fatalf("no pairing open: %v", err)
	}
	if _, err := join(t, a.addr, newKey(t), "not four words", shown, true); !errors.Is(err, pairing.ErrBadWords) {
		t.Fatalf("typo: %v", err)
	}
}
