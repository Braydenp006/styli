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

// A text box written before colour and highlight existed has neither (zero), and one with them keeps them.
func TestTextBoxColours(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p")
	old := []byte{} // a box as an old writer made it: no fields 6 and 7
	old, _ = proto.Marshal(&inkpb.TextBoxSet{X: 1, Y: 2, Width: 3000, FontSize: 400, Text: "hi"})
	var box inkpb.TextBoxSet
	if err := proto.Unmarshal(old, &box); err != nil || box.Color != 0 || box.Highlight != 0 || box.Angle != 0 {
		t.Fatal(box.String(), err)
	}
	set := func(seq uint64, b *inkpb.TextBoxSet) *inkpb.Event {
		return &inkpb.Event{Id: []byte("0123456789abcdef"), Device: "tab", Seq: seq, Kind: &inkpb.Event_TextBoxSet{TextBoxSet: b}}
	}
	if err := Append(p, set(1, &inkpb.TextBoxSet{Text: "plain"}), set(2, &inkpb.TextBoxSet{Text: "red", Color: 0xffc62828, Highlight: 0x99ffeb3b, Angle: -4550})); err != nil {
		t.Fatal(err)
	}
	l, err := Read(p)
	if err != nil || len(l.Events) != 2 {
		t.Fatal(l, err)
	}
	if b := l.Events[1].GetTextBoxSet(); b.Color != 0xffc62828 || b.Highlight != 0x99ffeb3b || b.Angle != -4550 {
		t.Errorf("colours lost: %v", b)
	}
	var out bytes.Buffer
	Dump(&out, l)
	if !strings.Contains(out.String(), `color=ffc62828 highlight=99ffeb3b angle=-4550 "red"`) || !strings.Contains(out.String(), `color=00000000 highlight=00000000 angle=0 "plain"`) {
		t.Errorf("dump:\n%s", out.String())
	}
}

// A page's place in the notebook's grid survives the log, and a page from before it existed sits at the first cell.
func TestPageCell(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p")
	set := func(seq uint64, ps *inkpb.PageSet) *inkpb.Event {
		return &inkpb.Event{Device: "tab", Seq: seq, Kind: &inkpb.Event_PageSet{PageSet: ps}}
	}
	if err := Append(p, set(1, &inkpb.PageSet{Width: 21000, Height: 29700}), set(2, &inkpb.PageSet{Width: 21000, Height: 29700, Col: -1, Row: 3})); err != nil {
		t.Fatal(err)
	}
	l, err := Read(p)
	if err != nil || len(l.Events) != 2 {
		t.Fatal(l, err)
	}
	if a, b := l.Events[0].GetPageSet(), l.Events[1].GetPageSet(); a.Col != 0 || a.Row != 0 || b.Col != -1 || b.Row != 3 {
		t.Errorf("cells: %v %v", a, b)
	}
	var out bytes.Buffer
	Dump(&out, l)
	if !strings.Contains(out.String(), "infinite=false cell=(-1,3)") || strings.Count(out.String(), "cell=") != 1 {
		t.Errorf("dump:\n%s", out.String())
	}
}

// A table is one whole-state event like a text box; it reads back and dumps with its sizes.
func TestTable(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p")
	set := func(seq uint64, k any) *inkpb.Event {
		e := &inkpb.Event{Id: []byte("0123456789abcdef"), Device: "tab", Seq: seq}
		switch k := k.(type) {
		case *inkpb.TableSet:
			e.Kind = &inkpb.Event_TableSet{TableSet: k}
		case *inkpb.TableRemoved:
			e.Kind = &inkpb.Event_TableRemoved{TableRemoved: k}
		}
		return e
	}
	in := &inkpb.TableSet{X: -100, Y: 250, ColWidths: []uint32{3000, 4500, 1000}, RowHeights: []uint32{800, 800}, LineWidth: 40, Color: 0xff1565c0, Fill: 0x99ffeb3b}
	if err := Append(p, set(1, in), set(2, &inkpb.TableRemoved{})); err != nil {
		t.Fatal(err)
	}
	l, err := Read(p)
	if err != nil || len(l.Events) != 2 {
		t.Fatal(l, err)
	}
	got := l.Events[0].GetTableSet()
	if !proto.Equal(got, in) {
		t.Errorf("table changed: %v", got)
	}
	if l.Events[1].GetTableRemoved() == nil {
		t.Error("removed event lost")
	}
	var out bytes.Buffer
	Dump(&out, l)
	for _, want := range []string{"table-set ", "at=(-100,250) cols=[3000 4500 1000] rows=[800 800] line=40 color=ff1565c0 fill=99ffeb3b", "table-removed"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("dump lacks %q:\n%s", want, out.String())
		}
	}
}

// A layer move reads back and dumps with where it went.
func TestZMoved(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p")
	mk := func(seq uint64, above []byte) *inkpb.Event {
		return &inkpb.Event{Id: []byte("0123456789abcdef"), Device: "z", Seq: seq, Kind: &inkpb.Event_ZMoved{ZMoved: &inkpb.ZMoved{Above: above}}}
	}
	if err := Append(p, mk(1, []byte("fedcba9876543210")), mk(2, nil)); err != nil {
		t.Fatal(err)
	}
	l, err := Read(p)
	if err != nil || len(l.Events) != 2 {
		t.Fatal(l, err)
	}
	var out bytes.Buffer
	Dump(&out, l)
	for _, want := range []string{"above 66656463626139383736353433323130", "to the bottom"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("dump lacks %q:\n%s", want, out.String())
		}
	}
}

// Shapes, connectors, fills and per-cell table fills read back whole and dump readably.
func TestShapesConnectorsFills(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p")
	id := []byte("0123456789abcdef")
	shape := &inkpb.ShapeSet{Kind: inkpb.ShapeKind_SHAPE_POLYGON, X: -50, Y: 80, Width: 3000, Height: 2000, Angle: 4500, Color: 0xff1565c0, LineWidth: 40,
		LineStyle: inkpb.LineStyle_LINE_DASHED, Fill: 0x99ffeb3b, Vertices: []uint32{5000, 0, 10000, 10000, 0, 10000}, Text: "hi", TextColor: 0xff000000, FontSize: 400}
	conn := &inkpb.ConnectorSet{From: &inkpb.ConnectorEnd{Shape: id, Anchor: 2, X: 10, Y: 20}, To: &inkpb.ConnectorEnd{X: -300, Y: 400},
		FromHead: inkpb.Arrowhead_ARROW_DOT, ToHead: inkpb.Arrowhead_ARROW_FILLED, Color: 0xffc62828, LineWidth: 60,
		Route: inkpb.ConnectorRoute_ROUTE_CURVE, Bend: -250, Via: []int32{100, -200, 3000, 40}}
	conn.From = &inkpb.ConnectorEnd{Shape: id, Anchor: 5, X: 10, Y: 20, Fx: 5000, Fy: 10000}
	fill := &inkpb.FillSet{Color: 0x9966bb6a, Rings: []*inkpb.Ring{{X0: 5, Y0: -5, Dx: []int32{100, 0, -100}, Dy: []int32{0, 100, 0}}}}
	table := &inkpb.TableSet{X: 0, Y: 0, ColWidths: []uint32{1000, 1000}, RowHeights: []uint32{500}, LineWidth: 40, CellFills: []uint32{0, 0x99ffeb3b}}
	evs := []*inkpb.Event{
		{Id: id, Device: "d", Seq: 1, Kind: &inkpb.Event_ShapeSet{ShapeSet: shape}},
		{Id: id, Device: "d", Seq: 2, Kind: &inkpb.Event_ConnectorSet{ConnectorSet: conn}},
		{Id: id, Device: "d", Seq: 3, Kind: &inkpb.Event_FillSet{FillSet: fill}},
		{Id: id, Device: "d", Seq: 4, Kind: &inkpb.Event_TableSet{TableSet: table}},
		{Id: id, Device: "d", Seq: 5, Kind: &inkpb.Event_ShapeRemoved{ShapeRemoved: &inkpb.ShapeRemoved{}}},
		{Id: id, Device: "d", Seq: 6, Kind: &inkpb.Event_ConnectorRemoved{ConnectorRemoved: &inkpb.ConnectorRemoved{}}},
		{Id: id, Device: "d", Seq: 7, Kind: &inkpb.Event_FillRemoved{FillRemoved: &inkpb.FillRemoved{}}},
	}
	if err := Append(p, evs...); err != nil {
		t.Fatal(err)
	}
	l, err := Read(p)
	if err != nil || len(l.Events) != 7 {
		t.Fatal(l, err)
	}
	if !proto.Equal(l.Events[0].GetShapeSet(), shape) || !proto.Equal(l.Events[1].GetConnectorSet(), conn) ||
		!proto.Equal(l.Events[2].GetFillSet(), fill) || !proto.Equal(l.Events[3].GetTableSet(), table) {
		t.Error("an event changed on the way")
	}
	var out bytes.Buffer
	Dump(&out, l)
	for _, want := range []string{
		`polygon at=(-50,80) size=3000x2000 angle=4500 line=ff1565c0/40/dashed fill=99ffeb3b corner=0 vertices=3 "hi"`,
		"30313233343536373839616263646566/at(5000,10000)@(10,20) -> (-300,400) heads=dot,filled", "route=curve bend=-250 via=2",
		"color=9966bb6a rings=1", "cells=2", "shape-removed", "connector-removed", "fill-removed",
	} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("dump lacks %q:\n%s", want, out.String())
		}
	}
}

// A dotted stroke is an ordinary stroke event with its own tool; it reads back and dumps by name.
func TestDottedStroke(t *testing.T) {
	p := filepath.Join(t.TempDir(), "p")
	in := &inkpb.StrokeAdded{Tool: inkpb.Tool_TOOL_DOTTED, Color: 0xff000000, Width: 40, X0: 5, Y0: 6, Dx: []int32{100}, Dy: []int32{0}}
	e := &inkpb.Event{Id: []byte("0123456789abcdef"), Device: "d", Seq: 1, Kind: &inkpb.Event_StrokeAdded{StrokeAdded: in}}
	if err := Append(p, e); err != nil {
		t.Fatal(err)
	}
	l, err := Read(p)
	if err != nil || len(l.Events) != 1 || l.Events[0].GetStrokeAdded().Tool != inkpb.Tool_TOOL_DOTTED {
		t.Fatal(l, err)
	}
	var out bytes.Buffer
	Dump(&out, l)
	if !strings.Contains(out.String(), "dotted color=ff000000 width=40 points=2") {
		t.Errorf("dump:\n%s", out.String())
	}
}
