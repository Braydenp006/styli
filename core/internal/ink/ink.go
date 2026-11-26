// Package ink reads and writes a page's stroke log (see proto/ink.proto): a
// Header record, then one Event record per change, each as a varint length and
// a message. The file only grows.
package ink

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"google.golang.org/protobuf/encoding/protodelim"

	"styli/core/internal/atomicfile"
	"styli/core/internal/ink/inkpb"
)

// Version is the newest log format this build reads and writes.
const Version = 1

// ErrNewer means the log was written by a newer format than this build knows.
var ErrNewer = errors.New("ink: log is from a newer version of Styli")

// Log is a page's log as read from disk.
type Log struct {
	Events []*inkpb.Event
	// Valid is the length in bytes of the part that parsed. A file longer than
	// this ends in a record that was cut off (a crash), which is ignored.
	Valid int64
}

// Parse reads a log. A missing or cut-off last record is not an error; a
// damaged one in the middle is, since nothing after it can be trusted.
func Parse(r io.Reader) (*Log, error) {
	cr := &countReader{r: r}
	br := bufio.NewReader(cr)
	l := &Log{}
	var pos int64 // bytes consumed by whole records so far
	sawHeader := false
	for {
		rec := &inkpb.Record{}
		err := protodelim.UnmarshalFrom(br, rec)
		if err == io.EOF || errors.Is(err, io.ErrUnexpectedEOF) {
			return l, nil // clean end, or a cut-off tail
		}
		if err != nil {
			return nil, fmt.Errorf("ink: damaged record at byte %d: %w", pos, err)
		}
		pos = cr.n - int64(br.Buffered())
		switch b := rec.Body.(type) {
		case *inkpb.Record_Header:
			if sawHeader {
				return nil, fmt.Errorf("ink: second header at byte %d", pos)
			}
			if b.Header.Version > Version {
				return nil, ErrNewer
			}
			sawHeader = true
		case *inkpb.Record_Event:
			if !sawHeader {
				return nil, errors.New("ink: event before the header")
			}
			l.Events = append(l.Events, b.Event)
		}
		// A record of a kind this build does not know is skipped, as the
		// format promises for new fields.
		l.Valid = pos
	}
}

// Read parses the log in path. A missing file is an empty log.
func Read(path string) (*Log, error) {
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return &Log{}, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return Parse(f)
}

// Append adds events to the log in path, creating it with its header if it is
// new, and drops a cut-off tail first. It is not atomic across a crash; the
// log is then a prefix of what was written, which Read accepts.
func Append(path string, events ...*inkpb.Event) error {
	l, err := Read(path)
	if err != nil {
		return err
	}
	if fi, err := os.Stat(path); err == nil && fi.Size() > l.Valid {
		if err := os.Truncate(path, l.Valid); err != nil {
			return err
		}
	}
	var buf bytes.Buffer
	if l.Valid == 0 {
		if err := write(&buf, &inkpb.Record{Body: &inkpb.Record_Header{Header: &inkpb.Header{Version: Version}}}); err != nil {
			return err
		}
	}
	for _, e := range events {
		if err := write(&buf, &inkpb.Record{Body: &inkpb.Record_Event{Event: e}}); err != nil {
			return err
		}
	}
	_, err = atomicfile.Append(path, l.Valid, &buf)
	return err
}

func write(w io.Writer, rec *inkpb.Record) error {
	_, err := protodelim.MarshalTo(w, rec)
	return err
}

type countReader struct {
	r io.Reader
	n int64
}

func (c *countReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	c.n += int64(n)
	return n, err
}

// Dump writes the log as readable text, one event per line.
func Dump(w io.Writer, l *Log) {
	fmt.Fprintf(w, "styli ink log, version %d, %d events\n", Version, len(l.Events))
	for _, e := range l.Events {
		fmt.Fprintf(w, "%s #%d %s %x %s\n", e.Device, e.Seq, kind(e), e.Id, detail(e))
	}
}

func kind(e *inkpb.Event) string {
	switch e.Kind.(type) {
	case *inkpb.Event_StrokeAdded:
		return "stroke-added"
	case *inkpb.Event_StrokeErased:
		return "stroke-erased"
	case *inkpb.Event_Transformed:
		return "transformed"
	case *inkpb.Event_TextBoxSet:
		return "text-box-set"
	case *inkpb.Event_TextBoxRemoved:
		return "text-box-removed"
	case *inkpb.Event_PageSet:
		return "page-set"
	}
	return "unknown"
}

func detail(e *inkpb.Event) string {
	switch k := e.Kind.(type) {
	case *inkpb.Event_StrokeAdded:
		s := k.StrokeAdded
		return fmt.Sprintf("%s color=%08x width=%d points=%d first=(%d,%d)", strings.ToLower(strings.TrimPrefix(s.Tool.String(), "TOOL_")), s.Color, s.Width, len(s.Dx)+1, s.X0, s.Y0)
	case *inkpb.Event_Transformed:
		t := k.Transformed
		return fmt.Sprintf("ids=%d matrix=[%g %g %g %g %g %g]", len(t.Ids), t.A, t.B, t.C, t.D, t.E, t.F)
	case *inkpb.Event_TextBoxSet:
		t := k.TextBoxSet
		return fmt.Sprintf("at=(%d,%d) width=%d size=%d %q", t.X, t.Y, t.Width, t.FontSize, t.Text)
	case *inkpb.Event_PageSet:
		p := k.PageSet
		return fmt.Sprintf("%dx%d %s infinite=%v", p.Width, p.Height, strings.ToLower(strings.TrimPrefix(p.Template.String(), "TEMPLATE_")), p.Infinite)
	}
	return ""
}
