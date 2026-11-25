# styli

A self-hosted notes app: handwrite on a tablet or type anywhere, sync between your own devices, and let a local AI turn handwriting into plain Markdown. Notes are ordinary `.md` files in a folder.

Status: phase 1 (the Go core on one machine). There is a hub server with a JSON API, and the web interface is being built screen by screen (the page shell, theme, fonts and Markdown with math rendering are in; the vault and editor screens are next).

## Run the hub

```
make build
./out/styli serve --vault ~/notes
```

- `--vault` is the notes folder. It must already exist.
- The hub listens on `http://127.0.0.1:7878` and **refuses any other address**, because there is no login yet. Nothing on your network can reach it.
- `--data` (search index) and `--config` (this device's settings) default to your cache and config folders.
- Stop it with Ctrl-C. It shuts down cleanly.

## Develop

```
make test    # go test ./... in core/
make cross   # check it builds for linux, freebsd and windows
make ui-assets  # copy the design system (design/) into the web UI; only when the design changes
```

The design is in `docs/DESIGN.md`.
