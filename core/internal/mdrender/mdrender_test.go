package mdrender

import (
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"
)

func render(t *testing.T, s string) string {
	t.Helper()
	out, err := Render([]byte(s))
	if err != nil {
		t.Fatal(err)
	}
	return string(out)
}

func has(t *testing.T, got string, wants ...string) {
	t.Helper()
	for _, w := range wants {
		if !strings.Contains(got, w) {
			t.Errorf("output lacks %q:\n%s", w, got)
		}
	}
}

func lacks(t *testing.T, got string, nots ...string) {
	t.Helper()
	for _, n := range nots {
		if strings.Contains(got, n) {
			t.Errorf("output contains %q:\n%s", n, got)
		}
	}
}

func TestGitHubStyleExtras(t *testing.T) {
	got := render(t, "| a | b |\n|---|:-:|\n| 1 | 2 |\n\n~~gone~~\n\n- [x] done\n- [ ] todo\n\nSee https://example.com and www.example.org.")
	has(t, got, "<table>", "<th>a</th>", `text-align:center`, "<del>gone</del>",
		`<input checked="" disabled="" type="checkbox"`, `<input disabled="" type="checkbox"`,
		`<a href="https://example.com">`, `<a href="http://www.example.org">`)
}

func TestCommonMarkBasics(t *testing.T) {
	got := render(t, "# Title\n\nSome *emphasis*, **strong** and `code`.\n\n1. one\n2. two\n\n> quote\n\n```go\nx := 1\n```\n")
	has(t, got, "<h1>Title</h1>", "<em>emphasis</em>", "<strong>strong</strong>", "<code>code</code>", "<ol>", "<blockquote>", "<pre><code")
	lacks(t, got, "id=") // heading ids are not generated: ids from note text could clobber page elements
}

func TestMath(t *testing.T) {
	for name, c := range map[string]struct{ in, want string }{
		"inline":                   {"so $x^2$ here", `<span class="math math-inline">x^2</span>`},
		"underscore and star":      {"$a_1 + b_2 * c_3$", `<span class="math math-inline">a_1 + b_2 * c_3</span>`},
		"display in a paragraph":   {"text $$ y_1 $$ more", `<span class="math math-display"> y_1 </span>`},
		"display on its own line":  {"$$ E = mc^2 $$", `<span class="math math-display"> E = mc^2 </span>`},
		"multi-line block":         {"$$\n\\begin{aligned}\na &= b_1 \\\\\nc &= d*2\n\\end{aligned}\n$$\n\nafter", "<div class=\"math math-display\">\\begin{aligned}\na &amp;= b_1 \\\\\nc &amp;= d*2\n\\end{aligned}</div>\n<p>after</p>"},
		"block opened with text":   {"$$ a +\nb $$", `<div class="math math-display">a +` + "\nb</div>"},
		"two on one line":          {"$x$ and $y$", `<span class="math math-inline">x</span> and <span class="math math-inline">y</span>`},
		"escaped dollar in math":   {`$a \$ b$`, `<span class="math math-inline">a \$ b</span>`},
		"in a list":                {"- item $x_1$\n  $$\n  z\n  $$\n", `<div class="math math-display">z</div>`},
		"in a quote, last line":    {"> $$\n> q\n> $$", `<div class="math math-display">q</div>`},
		"block at the end of file": {"intro\n\n$$\nz\n$$", `<div class="math math-display">z</div>`},
		"directly after text":      {"intro\n$$\nz\n$$", `<div class="math math-display">z</div>`},
	} {
		got := render(t, c.in)
		if !strings.Contains(got, c.want) {
			t.Errorf("%s: want %q in:\n%s", name, c.want, got)
		}
		lacks(t, got, "<p>$</p>", "<p>$$</p>") // no stray delimiter left behind
	}
}

// A formula on one line must not swallow what comes after it. (An earlier
// version did, and its tests passed because the formula was the last line.)
func TestContentAfterADisplayFormulaStillRenders(t *testing.T) {
	for name, in := range map[string]string{
		"one line then table":    "$$\\int_0^1 x^2\\,dx = \\frac{1}{3}$$\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nlast",
		"no spaces":              "$$x$$\n\nlast",
		"spaces":                 "$$ x $$\n\nlast",
		"two in a row":           "$$ x $$\n$$ y $$\n\nlast",
		"text between":           "$$ x $$\nmiddle\n$$ y $$\n\nlast",
		"after a paragraph":      "intro $5 and $10.\n\n$$x$$\n\nlast",
		"multi-line then more":   "$$\nx\n$$\n\n| a |\n|---|\n| 1 |\n\nlast",
		"multi-line, two blocks": "$$\nx\n$$\n\ntext\n\n$$\ny\n$$\n\nlast",
	} {
		got := render(t, in)
		has(t, got, "<p>last</p>")
		lacks(t, got, "<div class=\"math math-display\">last", "swallow")
		if strings.Contains(in, "|") {
			has(t, got, "<table>")
		}
		if strings.Contains(in, "middle") {
			has(t, got, "<p>")
			lacks(t, got, `<div class="math math-display">`)
		}
		_ = name
	}
}

func TestPricesAndEscapesAreNotMath(t *testing.T) {
	for name, in := range map[string]string{
		"two prices":         "costs $5 and $10 total",
		"price then text":    "paid $5, then $ 10",
		"closing then digit": "the $x$5 case",
		"space after open":   "a $ b $ c",
		"escaped":            `escaped \$5 and \$10`,
		"lone dollar":        "just one $ here",
		"unterminated":       "a $b c",
		"empty":              "a $$ b",
		"in a code span":     "`$a_1$`",
		"in a code block":    "```\n$$\nx\n$$\n```",
		"indented code":      "    $$\n    x\n    $$\n",
	} {
		got := render(t, in)
		lacks(t, got, `class="math`)
		_ = name
	}
	has(t, render(t, `escaped \$5 and \$10`), "escaped $5 and $10")
	has(t, render(t, "costs $5 and $10 total"), "costs $5 and $10 total")
	has(t, render(t, "`$a_1$`"), "<code>$a_1$</code>")
}

func TestAnUnclosedDisplayBlockDoesNotSwallowTheNote(t *testing.T) {
	got := render(t, "before\n\n$$\nnever closed\n\n# Heading\n\nlater text $y$")
	has(t, got, "<h1>Heading</h1>", "later text", `<span class="math math-inline">y</span>`)
	lacks(t, got, `<div class="math`)
}

func TestMathIsEscapedNeverTrusted(t *testing.T) {
	got := render(t, "$<script>alert(1)</script>$ and $$<img src=x onerror=alert(1)>$$\n\n$$\n<b>x</b> & </div><script>alert(2)</script>\n$$")
	lacks(t, got, "<script", "<img", "<b>", "</div><script")
	has(t, got, "&lt;script&gt;")
	assertSafe(t, got)
}

// A corpus of hostile notes. Whatever the input, the output may only contain
// the tags and attributes below, and every link must go somewhere safe.
func TestHostileNotesProduceSafeHTML(t *testing.T) {
	for name, in := range map[string]string{
		"script tag":         "<script>alert(1)</script>",
		"script in a para":   "hello <script>alert(1)</script> world",
		"img onerror":        "<img src=x onerror=alert(1)>",
		"svg onload":         "<svg onload=alert(1)><circle/></svg>",
		"iframe":             "<iframe src=//evil.example></iframe>",
		"style":              "<style>body{display:none}</style>",
		"object/embed":       "<object data=x></object><embed src=x>",
		"form":               `<form action=//evil><input name=x><button>go</button></form>`,
		"meta refresh":       `<meta http-equiv="refresh" content="0;url=//evil">`,
		"base":               `<base href="//evil/">`,
		"link tag":           `<link rel=stylesheet href=//evil>`,
		"event handler a":    `<a href="#" onclick="alert(1)">x</a>`,
		"js link":            "[x](javascript:alert(1))",
		"js link mixed case": "[x](JaVaScRiPt:alert(1))",
		"js link entity":     "[x](&#106;avascript:alert(1))",
		"js link spaces":     "[x](  javascript:alert(1))",
		"js link newline":    "[x](java\nscript:alert(1))",
		"js link ref":        "[x][r]\n\n[r]: javascript:alert(1)",
		"vbscript link":      "[x](vbscript:msgbox(1))",
		"file link":          "[x](file:///etc/passwd)",
		"data html link":     "[x](data:text/html,<script>alert(1)</script>)",
		"data html base64":   "[x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)",
		"js image":           "![x](javascript:alert(1))",
		"data svg image":     "![x](data:image/svg+xml,<svg onload=alert(1)>)",
		"js autolink":        "<javascript:alert(1)>",
		"js in title":        `[x](http://a.example "t" onmouseover="alert(1)")`,
		"attribute breakout": `[x](http://a.example"onmouseover="alert(1))`,
		"alt breakout":       `![a" onerror="alert(1)](http://a.example/x.png)`,
		"link text html":     "[<img src=x onerror=alert(1)>](http://a.example)",
		"html comment":       "<!-- --><script>alert(1)</script><!-- -->",
		"cdata":              "<![CDATA[<script>alert(1)</script>]]>",
		"processing":         "<?php system('x'); ?>",
		"nested emphasis":    "*<script>alert(1)</script>*",
		"table cell":         "| a |\n|---|\n| <script>alert(1)</script> |",
		"table align attr":   "| a |\n|:--|\n| x |",
		"task list":          "- [x] <img src=x onerror=alert(1)>",
		"auto url":           "www.example.com/\"onmouseover=\"alert(1)",
		"fenced info string": "```\"onmouseover=\"alert(1)\ncode\n```",
		"heading with html":  "# <script>alert(1)</script>",
		"unicode tricks":     "<scr\u0131pt>alert(1)</scr\u0131pt> <ſcript>alert(1)</ſcript>",
		"null byte":          "<scr\x00ipt>alert(1)</script>",
		"styli markers":      "<!-- styli:page 2 portrait -->\n<!-- styli:tags a, b -->",
	} {
		assertSafe(t, render(t, in))
		_ = name
	}
}

var (
	tagRe  = regexp.MustCompile(`<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s[^<>]*)?)/?>`)
	attrRe = regexp.MustCompile(`\s([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:="([^"]*)")?`)
)

var allowedTags = map[string]bool{
	"p": true, "h1": true, "h2": true, "h3": true, "h4": true, "h5": true, "h6": true, "ul": true, "ol": true, "li": true,
	"a": true, "em": true, "strong": true, "del": true, "code": true, "pre": true, "blockquote": true, "hr": true, "br": true,
	"table": true, "thead": true, "tbody": true, "tr": true, "th": true, "td": true, "img": true, "input": true, "span": true, "div": true,
}

var allowedAttrs = map[string]bool{"href": true, "src": true, "alt": true, "title": true, "class": true, "type": true,
	"checked": true, "disabled": true, "style": true, "start": true}

var alignStyle = regexp.MustCompile(`^text-align:(left|right|center)$`)

// assertSafe checks the output against an allowlist of tags, attributes and
// URL schemes, so it fails on anything new and unexpected, not just on a
// blocklist of known tricks.
func assertSafe(t *testing.T, html string) {
	t.Helper()
	for _, m := range tagRe.FindAllStringSubmatch(html, -1) {
		tag := strings.ToLower(m[2])
		if !allowedTags[tag] {
			t.Errorf("tag <%s> is not allowed in:\n%s", tag, html)
		}
		for _, a := range attrRe.FindAllStringSubmatch(m[3], -1) {
			name, val := strings.ToLower(a[1]), a[2]
			if !allowedAttrs[name] {
				t.Errorf("attribute %q on <%s> is not allowed in:\n%s", name, tag, html)
				continue
			}
			switch name {
			case "style":
				if !alignStyle.MatchString(val) {
					t.Errorf("style %q is not allowed", val)
				}
			case "href", "src":
				if !safeURL(tag, val) {
					t.Errorf("%s=%q on <%s> is not allowed in:\n%s", name, val, tag, html)
				}
			}
		}
	}
	// No raw "<" may be left that is not part of an allowed tag or a comment.
	stripped := tagRe.ReplaceAllString(html, "")
	stripped = regexp.MustCompile(`<!--.*?-->`).ReplaceAllString(stripped, "")
	if strings.Contains(stripped, "<") {
		t.Errorf("a stray < is left in:\n%s", html)
	}
}

var schemeRe = regexp.MustCompile(`^([a-zA-Z][a-zA-Z0-9+.-]*):`)

func safeURL(tag, v string) bool {
	v = strings.ToLower(strings.TrimSpace(strings.ReplaceAll(v, "&amp;", "&")))
	m := schemeRe.FindStringSubmatch(v)
	if m == nil {
		return true // relative, or empty (dangerous links are emptied)
	}
	switch m[1] {
	case "http", "https", "mailto":
		return true
	case "data":
		return tag == "img" && regexp.MustCompile(`^data:image/(png|gif|jpe?g|webp)[;,]`).MatchString(v)
	}
	return false
}

func TestSafeLinksAndImagesSurvive(t *testing.T) {
	got := render(t, "[a](https://example.com/x?y=1&z=2) [b](mailto:me@example.com) [c](#top) [d](../other.md) ![e](pic.png) ![f](data:image/png;base64,AAAA)")
	has(t, got, `href="https://example.com/x?y=1&amp;z=2"`, `href="mailto:me@example.com"`, `href="#top"`, `href="../other.md"`, `src="pic.png"`, `src="data:image/png;base64,AAAA"`)
	assertSafe(t, got)
}

func TestPageAndTagMarkersAreInvisible(t *testing.T) {
	got := render(t, "<!-- styli:page 2 portrait -->\n# Page two\n\ntext\n<!-- styli:tags a, b -->")
	lacks(t, got, "styli:", "portrait", "a, b")
	has(t, got, "<h1>Page two</h1>")
}

func TestRenderIsSafeForConcurrentUse(t *testing.T) {
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				out, err := Render([]byte("# T\n\n| a |\n|---|\n| $x_1$ |\n\n$$\nz\n$$\n"))
				if err != nil || !strings.Contains(string(out), `class="math math-display"`) {
					t.Errorf("%v %s", err, out)
					return
				}
			}
		}()
	}
	wg.Wait()
}

func TestAdversarialInputStaysFast(t *testing.T) {
	for name, in := range map[string]string{
		"300 KB of openers":          strings.Repeat("$a ", 100000),
		"300 KB of $$ openers":       strings.Repeat("$$ a\n", 50000),
		"alternating $ and \\":       strings.Repeat("$\\", 150000),
		"openers with far closer":    strings.Repeat("$a ", 90000) + "b$",
		"many unclosed display open": strings.Repeat("$$\nx\n\n", 30000),
	} {
		start := time.Now()
		render(t, in)                                  // the safety of the output is covered by the hostile corpus
		if d := time.Since(start); d > 4*time.Second { // about 1s normally; the race detector is much slower
			t.Errorf("%s took %v", name, d)
		}
	}
}

func TestLongMathStillWorksUnderTheCaps(t *testing.T) {
	long := "$" + strings.Repeat("x+", 400) + "y$" // about 800 bytes
	has(t, render(t, long), `class="math math-inline"`)
	var b strings.Builder
	b.WriteString("$$\n")
	for i := 0; i < 80; i++ {
		b.WriteString("a_" + "1 &= 2 \\\\\n")
	}
	b.WriteString("$$")
	has(t, render(t, b.String()), `<div class="math math-display">`)
}

func TestHugeAndOddInputsDoNotHang(t *testing.T) {
	for name, in := range map[string]string{
		"empty":             "",
		"only dollars":      strings.Repeat("$", 50000),
		"many openers":      strings.Repeat("$a ", 20000),
		"deep emphasis":     strings.Repeat("*a ", 5000),
		"deep quotes":       strings.Repeat("> ", 2000) + "x",
		"deep brackets":     strings.Repeat("[", 5000) + strings.Repeat("](", 5000),
		"many display open": strings.Repeat("$$\n", 20000),
		"long line":         strings.Repeat("word ", 200000),
	} {
		got := render(t, in)
		assertSafe(t, got)
		_ = name
	}
}
