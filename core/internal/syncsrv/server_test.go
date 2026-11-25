package syncsrv

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"styli/core/internal/devicekey"
	"styli/core/internal/devices"
	"styli/core/internal/vault"
)

// tlsEnv is an atrium serving sync over real TLS on loopback.
type tlsEnv struct {
	v      *vault.Vault
	devs   *devices.List
	atrium *devicekey.Key
	url    string
}

func tlsSetup(t *testing.T) *tlsEnv {
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
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := NewServer(Deps{Vault: v, Logf: t.Logf, Version: "test"}, key, devs)
	go s.Serve(ln)
	t.Cleanup(func() { s.Shutdown(context.Background()) })
	return &tlsEnv{v, devs, key, "https://" + ln.Addr().String()}
}

// device makes a device with its own key, optionally paired.
func (e *tlsEnv) device(t *testing.T, paired bool) (*devicekey.Key, *http.Client) {
	t.Helper()
	k, err := devicekey.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if paired {
		if err := e.devs.Add(k.ID, "Test device"); err != nil {
			t.Fatal(err)
		}
	}
	return k, clientFor(ClientTLS(k, e.atrium.ID))
}

func clientFor(c *tls.Config) *http.Client {
	return &http.Client{Transport: &http.Transport{TLSClientConfig: c, ForceAttemptHTTP2: true}, Timeout: 10 * time.Second}
}

func get(c *http.Client, url string) (int, error) {
	res, err := c.Get(url)
	if err != nil {
		return 0, err
	}
	res.Body.Close()
	return res.StatusCode, nil
}

func TestPairedDeviceSyncs(t *testing.T) {
	e := tlsSetup(t)
	k, c := e.device(t, true)
	res, err := c.Get(e.url + "/sync/v1/hello")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var hello map[string]any
	json.NewDecoder(res.Body).Decode(&hello)
	if res.StatusCode != 200 || hello["epoch"] != e.v.Epoch() || res.TLS.Version != tls.VersionTLS13 {
		t.Fatalf("hello: %d %v", res.StatusCode, hello)
	}
	if d := e.devs.List()[0]; d.ID != k.ID || d.LastSeen.IsZero() {
		t.Fatalf("not seen: %+v", d)
	}
}

func TestStrangersAreTurnedAway(t *testing.T) {
	e := tlsSetup(t)
	_, unpaired := e.device(t, false)
	k, _ := e.device(t, true)

	noCert := ClientTLS(k, e.atrium.ID)
	noCert.Certificates = nil
	old := ClientTLS(k, e.atrium.ID)
	old.MinVersion, old.MaxVersion = tls.VersionTLS12, tls.VersionTLS12

	for name, c := range map[string]*http.Client{
		"not paired":     unpaired,
		"no certificate": clientFor(noCert),
		"TLS 1.2":        clientFor(old),
	} {
		if code, err := get(c, e.url+"/sync/v1/hello"); err == nil {
			t.Errorf("%s: got in with %d", name, code)
		}
	}
	// Plain HTTP gets nowhere either.
	if code, err := get(http.DefaultClient, "http"+e.url[len("https"):]+"/sync/v1/hello"); err == nil && code == 200 {
		t.Error("plain HTTP served")
	}
}

// A device refuses to talk to anyone but the atrium it paired with, so a
// machine on the network cannot pose as the atrium.
func TestDeviceRefusesAnImpostorAtrium(t *testing.T) {
	e := tlsSetup(t)
	k, _ := e.device(t, true)
	impostor, _ := devicekey.Load(t.TempDir())
	if _, err := get(clientFor(ClientTLS(k, impostor.ID)), e.url+"/sync/v1/hello"); err == nil {
		t.Fatal("device talked to the wrong atrium")
	}
}

func TestRevokeCutsOffAWaitingRequest(t *testing.T) {
	e := tlsSetup(t)
	k, c := e.device(t, true)
	if _, err := get(c, e.url+"/sync/v1/hello"); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		_, err := get(c, fmt.Sprintf("%s/sync/v1/changes?since=%d&wait=30", e.url, e.v.Seq()))
		done <- err
	}()
	time.Sleep(200 * time.Millisecond) // let the request start waiting
	start := time.Now()
	if err := e.devs.Revoke(k.ID); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("the waiting request finished normally")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the waiting request was not cut off")
	}
	t.Logf("cut off after %v", time.Since(start))
	if code, err := get(c, e.url+"/sync/v1/hello"); err == nil {
		t.Fatalf("revoked device got back in: %d", code)
	}
}

// Every request is checked, not only the handshake: a revoked device is
// turned away even on a connection it already had open.
func TestRevokedDeviceRefusedOnAnOpenConnection(t *testing.T) {
	e := tlsSetup(t)
	k, c := e.device(t, true)
	if _, err := get(c, e.url+"/sync/v1/hello"); err != nil {
		t.Fatal(err)
	}
	e.devs.OnRevoke = nil // pretend closing its connections did not happen
	e.devs.Revoke(k.ID)
	code, err := get(c, e.url+"/sync/v1/hello")
	if err != nil || code != http.StatusForbidden {
		t.Fatalf("revoked device served on its old connection: %d", code)
	}
}

// The device list on disk is what decides, across restarts of the atrium.
func TestRevokeSurvivesRestart(t *testing.T) {
	root := t.TempDir()
	devs, _ := devices.Open(root)
	k, _ := devicekey.Load(t.TempDir())
	os.MkdirAll(filepath.Join(root, ".ids"), 0o700)
	devs.Add(k.ID, "Laptop")
	devs.Revoke(k.ID)
	again, err := devices.Open(root)
	if err != nil || again.Trusted(k.ID) {
		t.Fatalf("revoked device trusted after restart: %v", err)
	}
}

// Restoring a revoked device lets it back in on a fresh connection.
func TestUnrevokedDeviceConnectsAgain(t *testing.T) {
	e := tlsSetup(t)
	k, c := e.device(t, true)
	e.devs.Revoke(k.ID)
	if code, err := get(c, e.url+"/sync/v1/hello"); err == nil && code == http.StatusOK {
		t.Fatalf("revoked device served: %d", code)
	}
	if err := e.devs.Unrevoke(k.ID); err != nil {
		t.Fatal(err)
	}
	c.CloseIdleConnections()
	if code, err := get(c, e.url+"/sync/v1/hello"); err != nil || code != http.StatusOK {
		t.Fatalf("restored device refused: %d %v", code, err)
	}
}
