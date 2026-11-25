package syncdev

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"styli/core/internal/syncsrv"
	"styli/core/internal/vault"
)

// client talks to the atrium's sync API (see syncsrv). Every call goes over
// mutual TLS pinned to the atrium's key.
type client struct {
	hc *http.Client

	mu    sync.Mutex
	last  string   // the address that last worked: tried first
	given []string // the addresses the person gave, in order; always kept
	// find returns where the atrium is on the network now (nil: nobody looks).
	// It is asked only when the last address could not be reached.
	find func(ctx context.Context) []string
	// worked is told when an address other than the last one worked, so it can
	// be saved for next time.
	worked func(addr string)
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
		last: addr, given: []string{addr},
	}
}

// current is the address that last worked.
func (c *client) current() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.last
}

// fallback adds addresses to try when the others cannot be reached.
func (c *client) fallback(addrs ...string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, a := range addrs {
		if !slices.Contains(c.given, a) {
			c.given = append(c.given, a)
		}
	}
}

// remember makes addr the one tried first, and says so if it changed.
func (c *client) remember(addr string) {
	c.mu.Lock()
	changed := c.last != addr
	c.last = addr
	worked := c.worked
	c.mu.Unlock()
	if changed && worked != nil {
		worked(addr)
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
	return c.doWith(ctx, method, path, body, size, nil)
}

// doWith is do with extra request headers. It tries the atrium's addresses in
// turn, but only moves on from one that could not be connected to (nothing was
// sent, or the atrium there did not show the pinned key): the last address
// that worked, then what is on the network now, then the other given ones. An
// answer from the atrium, an error or not, is final.
func (c *client) doWith(ctx context.Context, method, path string, body io.Reader, size int64, hdr http.Header) (*http.Response, error) {
	c.mu.Lock()
	first, rest, find := c.last, slices.DeleteFunc(slices.Clone(c.given), func(a string) bool { return a == c.last }), c.find
	c.mu.Unlock()

	tried := map[string]bool{}
	var lastErr error
	try := func(addrs []string) (*http.Response, bool, error) {
		for _, a := range addrs {
			if tried[a] {
				continue
			}
			tried[a] = true
			res, connected, err := c.send(ctx, a, method, path, body, size, hdr)
			if connected || ctx.Err() != nil {
				if connected {
					c.remember(a)
				}
				return res, true, err
			}
			lastErr = err
		}
		return nil, false, nil
	}
	if res, done, err := try([]string{first}); done {
		return res, err
	}
	if find != nil {
		if res, done, err := try(find(ctx)); done {
			return res, err
		}
	}
	if res, done, err := try(rest); done {
		return res, err
	}
	return nil, lastErr
}

// send makes one request to addr. connected says a connection to it was made
// (so the request may have been sent); if not, nothing was, and the body is
// still unread for the next address.
func (c *client) send(ctx context.Context, addr, method, path string, body io.Reader, size int64, hdr http.Header) (res *http.Response, connected bool, err error) {
	var got atomic.Bool
	ctx = httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{GotConn: func(httptrace.GotConnInfo) { got.Store(true) }})
	req, err := http.NewRequestWithContext(ctx, method, "https://"+addr+path, body)
	if err != nil {
		return nil, true, err // nothing to retry with another address
	}
	for k, v := range hdr {
		req.Header[k] = v
	}
	if body != nil {
		req.ContentLength = size
	}
	res, err = c.hc.Do(req)
	if err != nil {
		return nil, got.Load(), err
	}
	if res.StatusCode/100 != 2 {
		defer res.Body.Close()
		e := &apiError{Status: res.StatusCode}
		json.NewDecoder(io.LimitReader(res.Body, 16<<20)).Decode(e) // a body that is not JSON still leaves the status
		return nil, true, e
	}
	return res, true, nil
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
	// Forgotten: deletes up to this rev are no longer in the change feed.
	Forgotten uint64 `json:"forgotten"`
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
// which must still have the given version on the atrium. With from > 0 it asks
// for the content from that byte on. It returns the byte the stream starts at:
// from, or 0 if the atrium sent the whole file instead.
func (c *client) download(ctx context.Context, id string, index int, version string, from int64) (io.ReadCloser, int64, error) {
	p := "/sync/v1/groups/" + url.PathEscape(id) + "/note"
	if index >= 0 {
		p = "/sync/v1/groups/" + url.PathEscape(id) + "/files/" + strconv.Itoa(index)
	}
	var hdr http.Header
	if from > 0 {
		hdr = http.Header{"Range": {fmt.Sprintf("bytes=%d-", from)}}
	}
	res, err := c.doWith(ctx, http.MethodGet, p+"?version="+url.QueryEscape(version), nil, 0, hdr)
	if err != nil {
		return nil, 0, err
	}
	if res.StatusCode != http.StatusPartialContent {
		return res.Body, 0, nil
	}
	if !strings.HasPrefix(res.Header.Get("Content-Range"), fmt.Sprintf("bytes %d-", from)) {
		res.Body.Close()
		return nil, 0, errors.New("sync: the atrium sent a different part of the file than asked for")
	}
	return res.Body, from, nil
}

// group asks the atrium what it holds of group id right now (see
// syncsrv.getGroup): its state, or its deletion.
func (c *client) group(ctx context.Context, id string) (syncsrv.Group, error) {
	var g syncsrv.Group
	_, err := c.getJSON(ctx, "/sync/v1/groups/"+url.PathEscape(id), &g)
	return g, err
}

// ResumeAbove is the size from which an upload can carry on after it is cut
// off. Smaller files go up in one request, without asking first.
var ResumeAbove int64 = 8 << 20

// putBlob uploads size bytes from r under their hash. A large upload first
// asks the atrium how much of it a cut-off earlier attempt left there, and
// sends the rest.
func (c *client) putBlob(ctx context.Context, hash string, r io.ReadSeeker, size int64) error {
	path := "/sync/v1/blobs/" + url.PathEscape(hash)
	if size <= ResumeAbove {
		res, err := c.do(ctx, http.MethodPut, path, r, size)
		if err != nil {
			return err
		}
		res.Body.Close()
		return nil
	}
	var err error
	for try := 0; try < 3; try++ {
		var head *http.Response
		if head, err = c.do(ctx, http.MethodHead, path, nil, 0); err != nil {
			return err
		}
		head.Body.Close()
		if head.Header.Get("Upload-Complete") != "" {
			return nil
		}
		offset, _ := strconv.ParseInt(head.Header.Get("Upload-Offset"), 10, 64)
		if offset < 0 || offset >= size {
			offset = 0 // not a part of this file (or all of it, unchecked): start again
		}
		if _, err = r.Seek(offset, io.SeekStart); err != nil {
			return err
		}
		hdr := http.Header{"Content-Range": {fmt.Sprintf("bytes %d-%d/%d", offset, size-1, size)}}
		var res *http.Response
		res, err = c.doWith(ctx, http.MethodPut, path, io.LimitReader(r, size-offset), size-offset, hdr)
		if err == nil {
			res.Body.Close()
			return nil
		}
		var ae *apiError
		if !errors.As(err, &ae) || ae.Status != http.StatusConflict || ae.Code != "offset" {
			return err
		}
		// The atrium's part moved on since we asked (a cut-off request it was
		// still reading): ask again.
	}
	return err
}

// deleteGroup asks the atrium to trash group id, if its rev is still base. It
// returns the deletion (Rev is the delete's number).
func (c *client) deleteGroup(ctx context.Context, id string, base uint64) (syncsrv.Group, error) {
	res, err := c.do(ctx, http.MethodDelete, "/sync/v1/groups/"+url.PathEscape(id)+"?base="+strconv.FormatUint(base, 10), nil, 0)
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

// recovery hands the atrium this device's copy of group id, deleted
// everywhere, to keep as a recovery candidate (its content uploaded first). It
// returns the delete's number on the atrium, or 0 if it has been emptied from
// the trash there.
func (c *client) recovery(ctx context.Context, id string, g vault.Group) (uint64, error) {
	files := g.Files
	if files == nil {
		files = []vault.File{}
	}
	b, err := json.Marshal(map[string]any{"note": g.Note, "files": files, "history": g.History, "conflict": g.Conflict})
	if err != nil {
		return 0, err
	}
	res, err := c.do(ctx, http.MethodPost, "/sync/v1/groups/"+url.PathEscape(id)+"/recovery", bytes.NewReader(b), int64(len(b)))
	if err != nil {
		return 0, err
	}
	defer res.Body.Close()
	var out struct {
		DeletedRev uint64 `json:"deleted_rev"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return 0, fmt.Errorf("sync: bad answer from the atrium: %w", err)
	}
	return out.DeletedRev, nil
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
