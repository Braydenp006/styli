// A note's saved versions: pick one, see what changed, read it, or restore it.
// Restoring never throws anything away: the text it replaces is kept as its
// own version, so a restore can always be undone by restoring that.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { href } from "../router.js";
import { renderMarkdown } from "../render.js";
import { relativeTime, fullDate } from "../time.js";
import { diffLines, collapse, hasChanges } from "../diff.js";
import { showToast } from "../toast.js";

// host.restored(id, title) runs after a successful restore.
export function createHistoryPane(host) {
  const pane = h("section", { class: "vault-history", "aria-label": "Version history" });
  let seq = 0;
  let s = null; // { id, note, versions, texts: Map, selected, mode }

  async function show(id) {
    const mine = ++seq;
    s = null;
    document.title = "Version history — Styli";
    setChildren(pane, h("p", { class: "view-sub", role: "status" }, "Loading…"));
    try {
      const [note, hist] = await Promise.all([api.get("/api/notes/" + encodeURIComponent(id)), api.get(`/api/notes/${encodeURIComponent(id)}/history`)]);
      if (mine !== seq) return;
      s = { id, note, versions: hist.versions, texts: new Map(), selected: null, mode: "since" };
      document.title = `Version history — ${note.title || "note"} — Styli`;
      draw();
      if (s.versions.length) select(s.versions[0].id, mine);
    } catch (err) {
      if (mine !== seq) return;
      setChildren(pane, backLink(id), err instanceof ApiError && err.code === "not_found"
        ? h("div", { class: "empty" }, h("h2", {}, "Note not found"), h("p", {}, "It may have been moved to the trash or deleted from your vault."))
        : errorBanner(err, () => show(id)));
    }
  }

  const backLink = (id) => h("a", { class: "history-back", href: href.note(id) }, "← Back to the note");

  async function textOf(versionId) {
    if (!s.texts.has(versionId)) {
      s.texts.set(versionId, (await api.get(`/api/notes/${encodeURIComponent(s.id)}/history/${versionId}`)).text);
    }
    return s.texts.get(versionId);
  }

  function draw() {
    const n = s.versions.length;
    const list = h("ol", { class: "version-list" }, s.versions.map((v, i) => versionItem(v, i)));
    s.detail = h("div", { class: "version-detail" });
    setChildren(pane,
      backLink(s.id),
      h("h1", { class: "view-title" }, "Version history"),
      h("p", { class: "view-sub" }, `${s.note.title || "This note"} · ${n} saved ${n === 1 ? "version" : "versions"}${s.note.history_enabled ? "" : " · Version history is off for this note, so no new versions are being saved"}`),
      n === 0
        ? h("div", { class: "empty" }, h("h2", {}, "No saved versions yet"),
            h("p", {}, s.note.history_enabled ? "Versions appear here as you edit this note." : "Turn version history on for this note and its versions will appear here."))
        : h("div", { class: "history-layout" }, list, s.detail));
  }

  function versionItem(v, i) {
    return h("li", {},
      h("button", { class: "version-item", type: "button", "data-id": v.id, onclick: () => select(v.id, seq) },
        h("span", { class: "version-when" }, relativeTime(v.time)),
        h("span", { class: "version-date" }, fullDate(v.time)),
        i === 0 ? h("span", { class: "version-flags" }, h("span", { class: "sty-chip" }, "Newest")) : null));
  }

  async function select(versionId, mine) {
    if (mine !== seq || !s) return;
    s.selected = versionId;
    for (const b of pane.querySelectorAll(".version-item")) {
      const on = b.dataset.id === versionId;
      b.classList.toggle("is-selected", on);
      if (on) b.setAttribute("aria-current", "true"); else b.removeAttribute("aria-current");
    }
    try {
      const text = await textOf(versionId);
      if (mine !== seq || s.selected !== versionId) return;
      // Choose a useful first view: what changed since, or just read it if nothing did.
      s.mode = text === s.note.text ? "read" : "since";
      drawDetail();
    } catch (err) {
      if (mine === seq) setChildren(s.detail, errorBanner(err, () => select(versionId, mine)));
    }
  }

  function modeButton(mode, label, disabled = false) {
    return h("button", { type: "button", "aria-pressed": String(s.mode === mode), disabled, onclick: () => { s.mode = mode; drawDetail(); } }, label);
  }

  async function drawDetail() {
    const mine = seq;
    const idx = s.versions.findIndex((v) => v.id === s.selected);
    const version = s.versions[idx];
    const text = s.texts.get(version.id);
    const older = s.versions[idx + 1]; // the version before this one
    const isNow = text === s.note.text;

    const body = h("div", { class: "version-body" });
    const restore = h("button", { class: "sty-btn sty-btn--secondary", type: "button", disabled: isNow, onclick: () => restoreVersion(version, restore) }, "Restore this version");
    setChildren(s.detail,
      h("div", { class: "version-head" },
        h("h2", {}, "Version from ", h("time", { datetime: version.time }, fullDate(version.time))),
        restore),
      isNow ? h("p", { class: "view-sub" }, "This is the same as the note now, so there is nothing to restore.") : null,
      h("div", { class: "seg", role: "group", "aria-label": "How to look at this version" },
        modeButton("since", "Changes since"), modeButton("changes", "What it changed", !older), modeButton("read", "Read it")),
      body);

    if (s.mode === "read") {
      body.className = "version-body prose";
      try {
        await renderMarkdown(body, text);
      } catch (err) {
        if (mine === seq) setChildren(body, errorBanner(err, drawDetail));
      }
      return;
    }
    if (s.mode === "since") {
      setChildren(body, h("p", { class: "view-sub" }, "Compared with the note as it is now. Lines marked − were in this version and are gone; lines marked + are new since."),
        diffView(diffLines(text, s.note.text)));
      return;
    }
    // "changes": this version against the one before it
    try {
      const before = await textOf(older.id);
      if (mine !== seq || s.selected !== version.id) return;
      setChildren(body, h("p", { class: "view-sub" }, "What this version changed from the version before it. Lines marked − were removed; lines marked + were added."),
        diffView(diffLines(before, text)));
    } catch (err) {
      if (mine === seq) setChildren(body, errorBanner(err, drawDetail));
    }
  }

  async function restoreVersion(version, button) {
    button.disabled = true;
    try {
      // Restore against the note's current version, so a change made elsewhere meanwhile is not overwritten blindly.
      const current = await api.get("/api/notes/" + encodeURIComponent(s.id));
      await api.post(`/api/notes/${encodeURIComponent(s.id)}/history/${version.id}/restore`, { base: current.version });
    } catch (err) {
      button.disabled = false;
      showToast(err instanceof ApiError && err.code === "conflict"
        ? "This note changed while you were looking. Reload the history and try again."
        : err instanceof ApiError ? err.message : "Couldn't restore that version.");
      return;
    }
    host.restored(s.id, s.note.title || "the note", version.time);
  }

  return { pane, show };
}

// The comparison as lines, with + and − marks so it does not rely on colour,
// and long unchanged stretches folded away.
function diffView(ops) {
  if (!hasChanges(ops)) return h("p", {}, "There are no differences.");
  const row = (op) => h("div", { class: "diff-line diff-" + op.type },
    h("span", { class: "diff-mark", "aria-hidden": "true" }, op.type === "add" ? "+" : op.type === "del" ? "−" : ""),
    op.type === "same" ? null : h("span", { class: "sr-only" }, op.type === "add" ? "Added: " : "Removed: "),
    h("code", {}, op.text === "" ? " " : op.text));
  const box = h("div", { class: "diff", role: "group", "aria-label": "Changes" });
  for (const item of collapse(ops, 3)) {
    if (item.kind === "lines") {
      box.append(...item.ops.map(row));
    } else {
      const n = item.ops.length;
      const fold = h("button", { class: "diff-fold", type: "button" }, `Show ${n} unchanged ${n === 1 ? "line" : "lines"}`);
      fold.addEventListener("click", () => fold.replaceWith(...item.ops.map(row)));
      box.append(fold);
    }
  }
  return box;
}

function errorBanner(err, retry) {
  return h("div", { class: "banner banner--danger", role: "alert" },
    h("span", { class: "grow" }, err instanceof ApiError ? err.message : "Something went wrong."),
    h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: retry }, "Try again"));
}
