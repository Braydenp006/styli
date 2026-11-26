package ink

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"google.golang.org/protobuf/encoding/protodelim"
	"google.golang.org/protobuf/proto"

	"styli/core/internal/ink/inkpb"
)

func stroke(seq uint64) *inkpb.Event {
	return &inkpb.Event{Id: []byte("0123456789abcdef"), Device: "tab", Seq: seq, Kind: &inkpb.Event_StrokeAdded{StrokeAdded: &inkpb.StrokeAdded{
		Color: 0xff000000, Width: 50, X0: 100, Y0: 200, Dx: []int32{5, -3}, Dy: []int32{1, 2}, Pressure: []byte{10, 20, 30}, Dt: []uint32{4, 4},
	}}}
}

func page() *inkpb.Event {
	return &inkpb.Event{Device: "tab", Seq: 1, Kind: &inkpb.Event_PageSet{PageSet: &inkpb.PageSet{Width: 21000, Height: 29700, Template: inkpb.Template_TEMPLATE_LINED}}}
}

func TestAppendReadRoundTrip(t *testing.T) {
	p := filepath.Join(t.TempDir(), "n.ink", "page-001.stylog")
	in := []*inkpb.Event{page(), stroke(2)}
	if err := Append(p, in[0]); err != nil {
		t.Fatal(err)
	}
	if err := Append(p, in[1]); err != nil {
		t.Fatal(err)
	}
	l, err := Read(p)
	if err != nil || len(l.Events) != 2 {
		t.Fatalf("got %v, %v", l, err)
	}
	for i := range in {
		if !proto.Equal(in[i], l.Events[i]) {
			t.Errorf("event %d changed", i)
		}
	}
	if fi, _ := os.Stat(p); fi.Size() != l.Valid {
		t.Errorf("valid %d, file %d", l.Valid, fi.Size())
	}
}

func TestMissingFileIsEmpty(t *testing.T) {
	l, err := Read(filepath.Join(t.TempDir(), "none"))
	if err != nil || len(l.Events) != 0 {
		t.Fatal(l, err)
	}
}

// A crash can cut the file anywhere: every cut must read as a prefix of the
// events, and Append must repair it and carry on.
func TestCutAtEveryByte(t *testing.T) {
	dir := t.TempDir()
	full := filepath.Join(dir, "full")
	for i := uint64(1); i <= 3; i++ {
		if err := Append(full, stroke(i)); err != nil {
			t.Fatal(err)
		}
	}
	data, _ := os.ReadFile(full)
	for n := 0; n <= len(data); n++ {
		p := filepath.Join(dir, "cut")
		os.WriteFile(p, data[:n], 0o600)
		l, err := Read(p)
		if err != nil {
			t.Fatalf("cut at %d: %v", n, err)
		}
		for i, e := range l.Events {
			if e.Seq != uint64(i+1) {
				t.Fatalf("cut at %d: event %d has seq %d", n, i, e.Seq)
			}
		}
		if err := Append(p, stroke(99)); err != nil {
			t.Fatalf("cut at %d: append: %v", n, err)
		}
		l2, err := Read(p)
		if err != nil || len(l2.Events) != len(l.Events)+1 || l2.Events[len(l.Events)].Seq != 99 {
			t.Fatalf("cut at %d: after append %v %v", n, l2, err)
		}
	}
}

func TestUnknownFieldsAndKindsAreSkipped(t *testing.T) {
	var b bytes.Buffer
	protodelim.MarshalTo(&b, &inkpb.Record{Body: &inkpb.Record_Header{Header: &inkpb.Header{Version: 1}}})
	// An event with a field number this build does not have (99, varint 7)
	// and, in a second record, a record body of an unknown kind (field 50).
	ev, _ := proto.Marshal(&inkpb.Record{Body: &inkpb.Record_Event{Event: stroke(1)}})
	ev = append(ev, 0xf8, 0x06, 0x07) // field 111, varint 7
	b.Write(append(protoVarint(len(ev)), ev...))
	unknown := []byte{0x92, 0x03, 0x01, 0x00} // field 50, length-delimited, 1 byte
	b.Write(append(protoVarint(len(unknown)), unknown...))
	l, err := Parse(&b)
	if err != nil || len(l.Events) != 1 {
		t.Fatal(l, err)
	}
}

func protoVarint(n int) []byte { // n < 16384 in these tests
	if n < 128 {
		return []byte{byte(n)}
	}
	return []byte{byte(n&0x7f) | 0x80, byte(n >> 7)}
}

func TestNewerVersionAndDamageRefused(t *testing.T) {
	var b bytes.Buffer
	protodelim.MarshalTo(&b, &inkpb.Record{Body: &inkpb.Record_Header{Header: &inkpb.Header{Version: Version + 1}}})
	if _, err := Parse(&b); err != ErrNewer {
		t.Errorf("newer: %v", err)
	}
	// An event before any header.
	b.Reset()
	protodelim.MarshalTo(&b, &inkpb.Record{Body: &inkpb.Record_Event{Event: stroke(1)}})
	if _, err := Parse(&b); err == nil {
		t.Error("event before header accepted")
	}
	// Garbage in the middle: a complete record that is not a Record.
	b.Reset()
	protodelim.MarshalTo(&b, &inkpb.Record{Body: &inkpb.Record_Header{Header: &inkpb.Header{Version: 1}}})
	b.Write([]byte{3, 0xff, 0xff, 0xff})
	if _, err := Parse(&b); err == nil {
		t.Error("damaged record accepted")
	}
}

func TestDump(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p")
	Append(p, page(), stroke(2))
	l, _ := Read(p)
	var b bytes.Buffer
	Dump(&b, l)
	want := `styli ink log, version 1, 2 events
tab #1 page-set  21000x29700 lined infinite=false
tab #2 stroke-added 30313233343536373839616263646566 pen color=ff000000 width=50 points=3 first=(100,200)
`
	if b.String() != want {
		t.Errorf("got:\n%s", b.String())
	}
	if !strings.Contains(b.String(), "stroke-added") {
		t.Fatal()
	}
}

// proto/testdata/sample.stylog is read by the Kotlin test too, so both sides
// agree on the bytes. Run with STYLI_UPDATE_GOLDEN=1 to rewrite it.
func TestSharedSample(t *testing.T) {
	const golden = "../../../proto/testdata/sample.stylog"
	p := filepath.Join(t.TempDir(), "s")
	if err := Append(p, page(), stroke(2), &inkpb.Event{Id: []byte("0123456789abcdef"), Device: "tab", Seq: 3, Kind: &inkpb.Event_StrokeErased{StrokeErased: &inkpb.StrokeErased{}}}); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(p)
	if os.Getenv("STYLI_UPDATE_GOLDEN") != "" {
		os.MkdirAll(filepath.Dir(golden), 0o755)
		os.WriteFile(golden, got, 0o644)
	}
	want, err := os.ReadFile(golden)
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("sample.stylog differs from what this build writes (%v); rerun with STYLI_UPDATE_GOLDEN=1 if the change is meant", err)
	}
}
