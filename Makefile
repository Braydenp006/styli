# Build and test the Go core. Run from the repo root.
OUT := $(CURDIR)/out

.PHONY: build test cross ui-assets
build:
	cd core && go build -ldflags "-X main.Version=$$(git describe --always --dirty 2>/dev/null || echo dev)" -o $(OUT)/styli ./cmd/styli

test:
	cd core && go test ./...
	cd tools/uiassets && go test ./...
	@if command -v node >/dev/null 2>&1; then NODE_NO_WARNINGS=1 node --test core/internal/web/uitest/; else echo "node not found: skipping the UI helper tests"; fi

# Copy the design system (design/, which is not in the repo) into the web UI.
# Only needed when the design changes; the output is committed.
ui-assets:
	cd tools/uiassets && go run . ../../design ../../core/internal/web/ui/static

# Pure Go (no cgo), so these build from Linux without extra toolchains.
cross:
	cd core && for t in linux/amd64 freebsd/amd64 windows/amd64; do \
		GOOS=$${t%/*} GOARCH=$${t#*/} CGO_ENABLED=0 go build -o $(OUT)/cross-$${t%/*} ./cmd/styli || exit 1; \
		echo "ok $$t"; \
	done
