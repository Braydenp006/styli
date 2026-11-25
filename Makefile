# Build and test the Go core. Run from the repo root.
OUT := $(CURDIR)/out

.PHONY: build test cross
build:
	cd core && go build -ldflags "-X main.Version=$$(git describe --always --dirty 2>/dev/null || echo dev)" -o $(OUT)/styli ./cmd/styli

test:
	cd core && go test ./...

# Pure Go (no cgo), so these build from Linux without extra toolchains.
cross:
	cd core && for t in linux/amd64 freebsd/amd64 windows/amd64; do \
		GOOS=$${t%/*} GOARCH=$${t#*/} CGO_ENABLED=0 go build -o $(OUT)/cross-$${t%/*} ./cmd/styli || exit 1; \
		echo "ok $$t"; \
	done
