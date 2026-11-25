package mdrender

import (
	"bytes"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/parser"
	"github.com/yuin/goldmark/renderer"
	"github.com/yuin/goldmark/text"
	"github.com/yuin/goldmark/util"
)

// Math support: $inline$, $$display$$ inside a paragraph, and $$ blocks on
// their own lines. The server never evaluates math. It keeps the TeX out of
// Markdown processing (so _ and * inside it survive), escapes it, and wraps it
// in <span class="math"> / <div class="math"> for the browser to hand to KaTeX.
//
// Inline rules follow Pandoc, so prices are not math: the opening $ must be
// followed by a non-space, the closing $ must follow a non-space and must not
// be followed by a digit. "costs $5 and $10" stays text.
// shortcut: inline math must close on the same line, within maxInline bytes,
// and a display block must close within maxBlockLines lines; anything longer
// stays literal text. The caps keep a hostile note (a megabyte of "$a $a $a")
// from making the scan quadratic.
const (
	maxInline     = 1000
	maxBlockLines = 100
)

var (
	kindInline = ast.NewNodeKind("MathInline")
	kindBlock  = ast.NewNodeKind("MathBlock")
)

type mathInline struct {
	ast.BaseInline
	tex     []byte
	display bool
}

func (n *mathInline) Kind() ast.NodeKind         { return kindInline }
func (n *mathInline) Dump(src []byte, level int) { ast.DumpHelper(n, src, level, nil, nil) }

type mathBlock struct{ ast.BaseBlock }

func (n *mathBlock) Kind() ast.NodeKind         { return kindBlock }
func (n *mathBlock) IsRaw() bool                { return true }
func (n *mathBlock) Dump(src []byte, level int) { ast.DumpHelper(n, src, level, nil, nil) }

// Math is the goldmark extension.
var Math goldmark.Extender = mathExt{}

type mathExt struct{}

func (mathExt) Extend(m goldmark.Markdown) {
	m.Parser().AddOptions(
		parser.WithInlineParsers(util.Prioritized(inlineParser{}, 150)),
		parser.WithBlockParsers(util.Prioritized(blockParser{}, 100)),
	)
	m.Renderer().AddOptions(renderer.WithNodeRenderers(util.Prioritized(mathRenderer{}, 500)))
}

// ---- inline ----

type inlineParser struct{}

func (inlineParser) Trigger() []byte { return []byte{'$'} }

func (inlineParser) Parse(_ ast.Node, block text.Reader, _ parser.Context) ast.Node {
	line, _ := block.PeekLine()
	if len(line) < 2 || line[0] != '$' {
		return nil
	}
	if len(line) > maxInline {
		line = line[:maxInline]
	}
	if line[1] == '$' { // $$ ... $$ inside a paragraph
		if end := bytes.Index(line[2:], []byte("$$")); end > 0 {
			block.Advance(end + 4)
			return &mathInline{tex: bytes.Clone(line[2 : 2+end]), display: true}
		}
		return nil
	}
	if isSpace(line[1]) {
		return nil
	}
	for i := 1; i < len(line); i++ {
		switch line[i] {
		case '\\':
			i++ // an escaped character, including \$, never closes the math
		case '$':
			closingOK := !isSpace(line[i-1]) && (i+1 == len(line) || line[i+1] < '0' || line[i+1] > '9')
			if closingOK {
				block.Advance(i + 1)
				return &mathInline{tex: bytes.Clone(line[1:i])}
			}
		}
	}
	return nil
}

func isSpace(b byte) bool { return b == ' ' || b == '\t' || b == '\n' || b == '\r' }

// ---- block ----

type blockParser struct{}

func (blockParser) Trigger() []byte                             { return []byte{'$'} }
func (blockParser) CanInterruptParagraph() bool                 { return true }
func (blockParser) CanAcceptIndentedLine() bool                 { return false }
func (blockParser) Close(ast.Node, text.Reader, parser.Context) {}

func (blockParser) Open(_ ast.Node, reader text.Reader, _ parser.Context) (ast.Node, parser.State) {
	line, seg := reader.PeekLine()
	indent := 0
	for indent < len(line) && line[indent] == ' ' {
		indent++
	}
	rest := bytes.TrimRight(line[indent:], " \t\r\n")
	if indent > 3 || !bytes.HasPrefix(rest, []byte("$$")) {
		return nil, parser.NoChildren
	}
	// "$$ x $$" complete on one line is not a block: left alone here, it becomes
	// a paragraph and the inline parser makes it display math. (Returning a
	// closed block from Open does not work in goldmark: the block stays open
	// and swallows the lines after it.)
	if bytes.Contains(rest[2:], []byte("$$")) {
		return nil, parser.NoChildren
	}
	n := &mathBlock{}

	// So this line only opens a block, but only if a closing $$ line really follows
	// before the next blank line (a blank line is an error in display math too).
	// Without that check a stray $$ would swallow the rest of the note.
	if !hasClosing(reader.Source(), seg.Stop) {
		return nil, parser.NoChildren
	}
	if first := bytes.TrimSpace(rest[2:]); len(first) > 0 {
		off := seg.Start + indent + 2 + bytes.Index(rest[2:], first)
		n.Lines().Append(text.NewSegment(off, off+len(first)))
	}
	reader.Advance(lineLen(line, seg))
	return n, parser.NoChildren
}

// hasClosing reports whether, starting at src[from:], a line ending in $$
// comes before a blank line or the end of the source.
func hasClosing(src []byte, from int) bool {
	for lines := 0; from < len(src) && lines < maxBlockLines; lines++ {
		end := bytes.IndexByte(src[from:], '\n')
		var line []byte
		if end < 0 {
			line, from = src[from:], len(src)
		} else {
			line, from = src[from:from+end], from+end+1
		}
		line = bytes.TrimSpace(bytes.TrimPrefix(line, []byte(">"))) // inside a quote too
		if len(line) == 0 {
			return false
		}
		if bytes.HasSuffix(line, []byte("$$")) {
			return true
		}
	}
	return false
}

func (blockParser) Continue(node ast.Node, reader text.Reader, _ parser.Context) parser.State {
	line, seg := reader.PeekLine()
	trimmed := bytes.TrimRight(line, " \t\r\n")
	if bytes.HasSuffix(trimmed, []byte("$$")) {
		if before := bytes.TrimRight(trimmed[:len(trimmed)-2], " \t"); len(bytes.TrimSpace(before)) > 0 {
			node.Lines().Append(text.NewSegment(seg.Start, seg.Start+len(before)))
		}
		reader.Advance(lineLen(line, seg))
		return parser.Close
	}
	node.Lines().Append(text.NewSegment(seg.Start, seg.Start+len(trimmed)))
	reader.Advance(lineLen(line, seg))
	return parser.Continue | parser.NoChildren
}

// ---- rendering ----

type mathRenderer struct{}

func (mathRenderer) RegisterFuncs(reg renderer.NodeRendererFuncRegisterer) {
	reg.Register(kindInline, renderInline)
	reg.Register(kindBlock, renderBlock)
}

func renderInline(w util.BufWriter, _ []byte, node ast.Node, entering bool) (ast.WalkStatus, error) {
	if entering {
		n := node.(*mathInline)
		class := "math math-inline"
		if n.display {
			class = "math math-display"
		}
		w.WriteString(`<span class="` + class + `">`)
		w.Write(util.EscapeHTML(n.tex))
		w.WriteString("</span>")
	}
	return ast.WalkSkipChildren, nil
}

func renderBlock(w util.BufWriter, src []byte, node ast.Node, entering bool) (ast.WalkStatus, error) {
	if entering {
		w.WriteString(`<div class="math math-display">`)
		lines := node.Lines()
		for i := 0; i < lines.Len(); i++ {
			if i > 0 {
				w.WriteByte('\n')
			}
			seg := lines.At(i)
			w.Write(util.EscapeHTML(seg.Value(src)))
		}
		w.WriteString("</div>\n")
	}
	return ast.WalkSkipChildren, nil
}

// lineLen is how far to advance to the end of the line's text: everything but
// the newline. The last line of a file may have no newline, and advancing one
// short there would leave a stray character behind.
func lineLen(line []byte, seg text.Segment) int {
	n := seg.Len()
	if len(line) > 0 && line[len(line)-1] == '\n' {
		n--
	}
	return n
}
