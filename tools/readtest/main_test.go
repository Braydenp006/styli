package main

import (
	"image"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func TestDescribe(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/chat/completions" {
			t.Errorf("path %s", r.URL.Path)
		}
		w.Write([]byte(`{"choices":[{"message":{"content":"# Hi"}}]}`))
	}))
	defer srv.Close()
	img := t.TempDir() + "/a.png"
	os.WriteFile(img, []byte("x"), 0o644)
	got, err := describe(srv.URL, "m", "p", img)
	if err != nil || got != "# Hi" {
		t.Fatalf("got %q, %v", got, err)
	}
	bad := httptest.NewServer(http.NotFoundHandler())
	defer bad.Close()
	if _, err := describe(bad.URL, "m", "p", img); err == nil {
		t.Fatal("expected error on 404")
	}
}

func TestStripFence(t *testing.T) {
	if got := stripFence("```markdown\n# A\n```\n"); got != "# A" {
		t.Fatalf("got %q", got)
	}
	in := "# A\n\n```mermaid\nx\n```"
	if got := stripFence(in); got != in {
		t.Fatalf("changed a normal page: %q", got)
	}
}

func TestPageMarker(t *testing.T) {
	for _, c := range []struct {
		w, h int
		want string
	}{{10, 20, "portrait"}, {20, 10, "landscape"}} {
		p := t.TempDir() + "/a.png"
		f, _ := os.Create(p)
		png.Encode(f, image.NewRGBA(image.Rect(0, 0, c.w, c.h)))
		f.Close()
		if got, want := pageMarker(3, p), "<!-- styli:page 3 "+c.want+" -->"; got != want {
			t.Fatalf("got %q want %q", got, want)
		}
	}
}
