# Styli

A self-hosted notes app: handwrite on an Android tablet or type anywhere, sync between your own trusted devices through an atrium, and a local AI turns handwriting and diagrams into plain Markdown. The full plan is in `docs/DESIGN.md`. Read the sections that relate to a task before starting it.

## How to work with me

- **Plan before code.** For anything beyond a small fix, say what you'll do and which files change, and wait for my OK. Don't jump ahead to later phases or add features that aren't in the design doc.
- **I'm learning Go and Kotlin.** Explain non-obvious code briefly, in plain words.
- **The design doc is the source of truth.** If something we build needs to differ from it, stop and tell me, and update `docs/DESIGN.md` once I agree.
- **One phase at a time,** following the roadmap in the design doc. We're starting at phase 0a and 0b.
- Keep changes small and finish each one fully, including tests.

## Stack (decided, don't change without asking)

| Part | Choice |
| --- | --- |
| Core (sync, vault, AI queue, web server) | Go, one binary for FreeBSD, Linux, Windows and Android |
| Metadata and search | SQLite via `modernc.org/sqlite` (pure Go, no cgo), FTS5 |
| Ink format | Protocol Buffers: an append-only stroke-event log per page |
| Android tablet app | Kotlin, Jetpack Compose, `androidx.ink`; Go core embedded with gomobile |
| Laptop and desktop app | Wails (the web UI in a native window) |
| iPhone and headless atrium | Web UI served by the Go core |
| AI | Any OpenAI-compatible vision server (llama.cpp, Ollama, LM Studio) on the atrium or a worker |
| Notes | Standard CommonMark Markdown files; Mermaid for diagrams; relative Markdown links (no `[[wikilinks]]`) |
| Transport | Wi-Fi/LAN, mDNS discovery, VPN when away. No Bluetooth, no Syncthing, no third-party cloud. |

## Rules that protect data and security

- **Never lose a note.** Never clear a local copy unless the atrium has confirmed that exact version. Never clear notes with unsynced changes. Deletes go to trash first.
- **Sync, merging, deletes, pairing, TLS and login code:** write a plan and get my approval before writing code, and include tests that simulate offline devices and conflicts.
- **Crypto:** use vetted libraries only (for example an existing PAKE implementation). Never hand-roll cryptography.
- **Never send real notes or vault contents to any outside service.** Tests use sample data.

## Which model for which task

The build plan table in `docs/DESIGN.md` lists a model for every task. In short:

- **Haiku:** boilerplate, setup, simple screens, tests for simple code, docs.
- **Sonnet:** the default for real logic.
- **Opus:** only where a subtle bug loses data or weakens security: sync protocol, device keys and mutual TLS, pairing, deletes, safe clearing of local copies, web login, atrium handover and rebuild, and the security review.
- **OmniRoute** (free and cheap models) only for tasks marked Yes in the build plan. Never for sync, merge, delete or security code, and never with real notes.

If the current model doesn't match the task, tell me before starting.

## Design

- Design system: `design/DESIGN-SYSTEM.md` (voice, colour use, type, icons, logo rules). Read it before building any screen.
- Tokens: `design/tokens.json` is the single source for colours (light and dark), type, spacing, radius and shadow. Never hard-code a colour or size that has a token.
- Web components: `design/components/styli.css` (`sty-` classes) and a short guide per component in `design/components/`.
- Logos: `design/logos/` (light-background and dark-background versions).
- Mockups: https://claude.ai/artifact/DdEZz7DPrtKNAR97AQFGRF
- UI wording: sentence case, no emoji (except the pairing emoji check). Deletes always say "this device" or "everywhere".
- Apps follow the system light/dark setting.

## Repo layout (proposed, confirm in phase 1)

```
core/        Go core (vault, sync, AI queue, web server)
web/         Web UI, also used by the Wails app
android/     Kotlin tablet app
proto/       Protocol Buffer schemas (ink format)
tools/       Prototypes and test tools (phase 0b AI reading test)
design/      Design system: tokens, components, logos
docs/        DESIGN.md and other docs
```
