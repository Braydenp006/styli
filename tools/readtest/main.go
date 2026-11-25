// readtest sends each page of each PDF/image in a folder to an OpenAI-compatible
// vision server and saves the Markdown it returns, so models can be compared by eye.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type content map[string]any

var reasoning *string

func main() {
	server := flag.String("server", "http://localhost:11434/v1", "OpenAI-compatible base URL")
	model := flag.String("model", "", "model name (required)")
	in := flag.String("in", "../../samples", "folder with PDFs and images")
	out := flag.String("out", "out", "output folder; results go in <out>/<model>/")
	promptFile := flag.String("prompt", "prompt.txt", "prompt file")
	dpi := flag.Int("dpi", 150, "PDF render resolution")
	reasoning = flag.String("reasoning", "none", `reasoning_effort sent to the server ("" to omit)`)
	flag.Parse()
	if *model == "" {
		fmt.Fprintln(os.Stderr, "need -model")
		os.Exit(2)
	}
	prompt, err := os.ReadFile(*promptFile)
	check(err)
	dir := filepath.Join(*out, strings.NewReplacer("/", "_", ":", "_").Replace(*model))
	check(os.MkdirAll(dir, 0o755))

	files, _ := filepath.Glob(filepath.Join(*in, "*"))
	sort.Strings(files)
	for _, f := range files {
		pages, cleanup, err := toImages(f, *dpi)
		if err != nil {
			fmt.Fprintf(os.Stderr, "skip %s: %v\n", f, err)
			continue
		}
		base := strings.TrimSuffix(filepath.Base(f), filepath.Ext(f))
		var parts []string
		for i, img := range pages {
			start := time.Now()
			md, err := describe(*server, *model, string(prompt), img)
			if err != nil {
				fmt.Fprintf(os.Stderr, "%s page %d: %v\n", base, i+1, err)
				md = fmt.Sprintf("[PAGE %d FAILED: %v]", i+1, err) // keep the gap visible
			}
			parts = append(parts, pageMarker(i+1, img)+"\n\n"+stripFence(md))
			fmt.Printf("%s page %d/%d  %.1fs\n", base, i+1, len(pages), time.Since(start).Seconds())
		}
		check(os.WriteFile(filepath.Join(dir, base+".md"), []byte(strings.Join(parts, "\n\n")+"\n"), 0o644))
		cleanup()
	}
}

// toImages returns PNG/JPEG paths for a file. PDFs are rendered with pdftoppm
// (poppler); shortcut: needs poppler installed, fine for a test tool.
func toImages(path string, dpi int) ([]string, func(), error) {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".png", ".jpg", ".jpeg":
		return []string{path}, func() {}, nil
	case ".pdf":
		tmp, err := os.MkdirTemp("", "readtest")
		if err != nil {
			return nil, nil, err
		}
		cleanup := func() { os.RemoveAll(tmp) }
		if b, err := exec.Command("pdftoppm", "-png", "-r", fmt.Sprint(dpi), path, filepath.Join(tmp, "p")).CombinedOutput(); err != nil {
			cleanup()
			return nil, nil, fmt.Errorf("pdftoppm: %v: %s", err, b)
		}
		pages, _ := filepath.Glob(filepath.Join(tmp, "p-*.png"))
		sort.Strings(pages)
		return pages, cleanup, nil
	}
	return nil, nil, fmt.Errorf("unsupported type")
}

func describe(server, model, prompt, imgPath string) (string, error) {
	raw, err := os.ReadFile(imgPath)
	if err != nil {
		return "", err
	}
	mime := "image/png"
	if e := strings.ToLower(filepath.Ext(imgPath)); e == ".jpg" || e == ".jpeg" {
		mime = "image/jpeg"
	}
	req := map[string]any{
		"model":       model,
		"temperature": 0,
		"messages": []any{map[string]any{"role": "user", "content": []content{
			{"type": "text", "text": prompt},
			{"type": "image_url", "image_url": map[string]string{"url": "data:" + mime + ";base64," + base64.StdEncoding.EncodeToString(raw)}},
		}}},
	}
	if reasoning != nil && *reasoning != "" {
		req["reasoning_effort"] = *reasoning
	}
	body, _ := json.Marshal(req)
	resp, err := (&http.Client{Timeout: 10 * time.Minute}).Post(strings.TrimRight(server, "/")+"/chat/completions", "application/json", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("server said %d: %s", resp.StatusCode, data)
	}
	var r struct {
		Choices []struct {
			FinishReason string                   `json:"finish_reason"`
			Message      struct{ Content string } `json:"message"`
		} `json:"choices"`
	}
	if err := json.Unmarshal(data, &r); err != nil || len(r.Choices) == 0 {
		return "", fmt.Errorf("bad reply: %s", data)
	}
	if strings.TrimSpace(r.Choices[0].Message.Content) == "" {
		return "", fmt.Errorf("empty answer (model may have spent its token limit thinking; try -reasoning none)")
	}
	if r.Choices[0].FinishReason == "length" {
		return r.Choices[0].Message.Content + "\n\n[TRUNCATED: hit the token limit]\n", nil
	}
	return r.Choices[0].Message.Content, nil
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

// stripFence removes a ```markdown fence the model sometimes wraps around the whole page.
func stripFence(s string) string {
	t := strings.TrimSpace(s)
	if strings.HasPrefix(t, "```markdown\n") && strings.HasSuffix(t, "\n```") {
		return strings.TrimSpace(t[len("```markdown\n") : len(t)-len("\n```")])
	}
	return t
}

// pageMarker is an HTML comment: valid CommonMark, invisible in other viewers,
// and the app splits on it. Orientation comes from the page image's shape.
func pageMarker(n int, imgPath string) string {
	orientation := "portrait"
	if f, err := os.Open(imgPath); err == nil {
		defer f.Close()
		if c, _, err := image.DecodeConfig(f); err == nil && c.Width > c.Height {
			orientation = "landscape"
		}
	}
	return fmt.Sprintf("<!-- styli:page %d %s -->", n, orientation)
}
