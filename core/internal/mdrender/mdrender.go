// Package mdrender turns a note's Markdown into HTML for the web UI.
//
// Notes are untrusted text: they can come from a PDF, from the AI reader, or
// from any file dropped into the vault. So the renderer runs goldmark in its
// default safe mode: raw HTML in a note is dropped, and links with dangerous
// schemes (javascript:, vbscript:, file:, data: other than a few image types)
// are emptied. On top of that the page's Content-Security-Policy refuses
// inline scripts and any resource from outside the atrium.
//
// It understands CommonMark plus GitHub-style tables, strikethrough, task
// lists and bare-URL links, and $math$ (see math.go).
package mdrender

import (
	"bytes"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/extension"
)

var md = goldmark.New(goldmark.WithExtensions(extension.GFM, Math))

// Render converts Markdown to an HTML fragment. It is safe to call from many
// goroutines at once.
func Render(src []byte) ([]byte, error) {
	var out bytes.Buffer
	if err := md.Convert(src, &out); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}
