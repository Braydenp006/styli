# Phase 0b results: AI reading test

Run 2026-11-24 on an RTX 3080 (10 GB) through Ollama 0.40.16. Four PDFs (25 pages) of the user's own handwriting, which the user calls unusually bad. Compared by eye; no typed transcripts, so no error rate.

## Verdict

**Good enough to proceed.** Qwen3-VL 8B instruct read the handwriting well, including text, lists and a drawn diagram. The user's call: success. Tuning is per user later, so no fixed quality bar was set.

## Models

| Model | Result |
| --- | --- |
| `qwen3-vl:8b-instruct` | Works. About 3 to 6 s per page, up to about 19 s on dense pages. |
| `qwen3-vl:8b` (thinking build) | Unusable through Ollama's OpenAI API: `reasoning_effort` is ignored, thinking used the whole token limit and the answer came back empty. |
| Qwen2.5-VL 7B, MiniCPM-V, Llama 3.2 Vision 11B | Not tested. Not needed yet. |
| Dedicated OCR models (PaddleOCR-VL, MinerU, GLM-OCR) | Not tested. They reportedly beat general models at pure transcription but don't run through Ollama's chat API. Revisit if transcription quality becomes the problem. |

## Problems seen

- **Truncation.** Ollama's default context is 4096 tokens and one page image uses about 2200, so the two dense pages of `Test2.pdf` were cut off. Setting `OLLAMA_CONTEXT_LENGTH=8192` should fix it; not tried. The app must tell users to raise the context when they set up their reader (see `DESIGN.md`, AI section). The tool marks cut-off pages with `[TRUNCATED: ...]`.
- **Diagrams.** The model produces valid Mermaid, but a state diagram it drew for `Test4.pdf` looked partly invented (many identical edge labels). Diagram accuracy needs checking against the page before trusting it.
- **Whole-page fence.** The model sometimes wraps its answer in a markdown code fence; the tool strips it.
- **List numbering** restarts on each page, because pages are converted separately.

## Output format (agreed)

One Markdown file per PDF. Each page starts with `<!-- styli:page N portrait|landscape -->` (orientation from the page image). Failed pages leave `[PAGE N FAILED: ...]`. Landscape was not tested with a real PDF; only a unit test covers it.

## Using the tool

```
cd tools/readtest
go build -o readtest .
./readtest -model qwen3-vl:8b-instruct          # reads ../../samples, writes out/<model>/<pdf>.md
```

Flags: `-server`, `-model`, `-in`, `-out`, `-prompt`, `-dpi`, `-reasoning`. Needs `pdftoppm` (poppler). `out/` and `samples/` are gitignored because they hold real notes.
