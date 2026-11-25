package syncdev

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

// client talks to the atrium's sync API (see syncsrv). Every call goes over
// mutual TLS pinned to the atrium's key.
type client struct {
	hc  *http.Client
	url string // "https://host:port"
}

func newClient(tc *tls.Config, addr string) *client {
	return &client{
		hc: &http.Client{Transport: &http.Transport{
			TLSClientConfig:       tc,
			ForceAttemptHTTP2:     true,
			DialContext:           (&net.Dialer{Timeout: 10 * time.Second}).DialContext,
			TLSHandshakeTimeout:   10 * time.Second,
			ResponseHeaderTimeout: syncsrv.MaxWait + 15*time.Second, // a long poll answers within MaxWait
			IdleConnTimeout:       90 * time.Second,
		}},
		url: "https://" + addr,
	}
}

// apiError is an answer from the atrium that is not a success. Code is the
// atrium's error code (see syncsrv.fail); Current is set on a refused commit.
type apiError struct {
	Status  int
	Code    string         `json:"code"`
	Msg     string         `json:"error"`
	Current *syncsrv.Group `json:"current"`
}

func (e *apiError) Error() string {
	return fmt.Sprintf("the atrium answered %d %s: %s", e.Status, e.Code, e.Msg)
}

func (c *client) do(ctx context.Context, method, path string, body io.Reader, size int64) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.url+path, body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.ContentLength = size
	}
	res, err := c.hc.Do(req)
	if err != nil {
		return nil, err
	}
	if res.StatusCode/100 != 2 {
		defer res.Body.Close()
		e := &apiError{Status: res.StatusCode}
		json.NewDecoder(io.LimitReader(res.Body, 16<<20)).Decode(e) // a body that is not JSON still leaves the status
		return nil, e
	}
	return res, nil
}

func (c *client) getJSON(ctx context.Context, path string, dst any) (string, error) {
	res, err := c.do(ctx, http.MethodGet, path, nil, 0)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if err := json.NewDecoder(res.Body).Decode(dst); err != nil {
		return "", fmt.Errorf("sync: bad answer from the atrium: %w", err)
	}
	return res.Header.Get("Styli-Epoch"), nil
}

type hello struct {
	Protocol int    `json:"protocol"`
	Version  string `json:"version"`
	Epoch    string `json:"epoch"`
	Seq      uint64 `json:"seq"`
}

func (c *client) hello(ctx context.Context) (hello, error) {
	var h hello
	_, err := c.getJSON(ctx, "/sync/v1/hello", &h)
	return h, err
}

type page struct {
	Groups []syncsrv.Group `json:"groups"`
	Next   uint64          `json:"next"`
	More   bool            `json:"more"`
	Epoch  string          `json:"-"` // from the answer's header
}

// changes reads one page of the change feed; wait > 0 holds it open that many
// seconds until something changes.
func (c *client) changes(ctx context.Context, since uint64, wait int) (page, error) {
	var p page
	q := url.Values{"since": {strconv.FormatUint(since, 10)}}
	if wait > 0 {
		q.Set("wait", strconv.Itoa(wait))
	}
	epoch, err := c.getJSON(ctx, "/sync/v1/changes?"+q.Encode(), &p)
	p.Epoch = epoch
	return p, err
}

// download streams the note (index -1) or attached file index of group id,
// which must still have the given version on the atrium.
func (c *client) download(ctx context.Context, id string, index int, version string) (io.ReadCloser, error) {
	p := "/sync/v1/groups/" + url.PathEscape(id) + "/note"
	if index >= 0 {
		p = "/sync/v1/groups/" + url.PathEscape(id) + "/files/" + strconv.Itoa(index)
	}
	res, err := c.do(ctx, http.MethodGet, p+"?version="+url.QueryEscape(version), nil, 0)
	if err != nil {
		return nil, err
	}
	return res.Body, nil
}

func (c *client) putBlob(ctx context.Context, hash string, r io.Reader, size int64) error {
	res, err := c.do(ctx, http.MethodPut, "/sync/v1/blobs/"+url.PathEscape(hash), r, size)
	if err != nil {
		return err
	}
	res.Body.Close()
	return nil
}

// commit asks the atrium to make group id equal g, if its rev is still base.
func (c *client) commit(ctx context.Context, id string, base uint64, g vault.Group) (syncsrv.Group, error) {
	files := g.Files
	if files == nil {
		files = []vault.File{}
	}
	b, err := json.Marshal(map[string]any{"base": base, "note": g.Note, "files": files, "history": g.History, "conflict": g.Conflict})
	if err != nil {
		return syncsrv.Group{}, err
	}
	res, err := c.do(ctx, http.MethodPut, "/sync/v1/groups/"+url.PathEscape(id), bytes.NewReader(b), int64(len(b)))
	if err != nil {
		return syncsrv.Group{}, err
	}
	defer res.Body.Close()
	var out syncsrv.Group
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return syncsrv.Group{}, fmt.Errorf("sync: bad answer from the atrium: %w", err)
	}
	return out, nil
}
