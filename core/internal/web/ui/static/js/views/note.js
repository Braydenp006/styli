// The open note: reading it, editing it, saving it, and the details panel.
//
// The rule that shapes this file: never lose what someone typed. The text in
// the editor is only replaced when it has been saved or the person chose to
// replace it, a failed save leaves it exactly where it is, and leaving a note
// first saves it (and stays put if that does not work).
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { href } from "../router.js";
import { renderMarkdown } from "../render.js";
import { relativeTime, fullDate } from "../time.js";
import { openDialog, textField, UserError } from "../dialogs.js";
import { folderPath, titleOf } from "../names.js";

const AUTOSAVE_MS = 1500;

// host is the list view, which the note pane tells about changes:
//   host.folders()               the folder names, for suggestions
//   host.listHref()              where "← Notes" goes
//   host.noteChanged(id, patch)  a title or time changed
//   host.noteMoved(id)           moved: refresh the list and sidebar
//   host.noteTrashed(id, note)   moved to the trash
export function createNotePane(host) {
  const backLink = () => h("a", { class: "back-link", href: host.listHref() }, "← Notes");
  const pane = h("article", { class: "vault-note" });
  const details = h("aside", { class: "vault-details", "aria-label": "Note details" });
  let cur = null; // the open note's state, or null
  let loadSeq = 0;

  // ---- opening ----

  async function open(id, { edit = false } = {}) {
    if (cur && cur.id === id) {
      if (edit) setMode(cur, "edit");
      return;
    }
    const seq = ++loadSeq;
    cur = null;
    document.title = "Styli";
    setChildren(pane, backLink(), h("p", { class: "view-sub", role: "status" }, "Loading…"));
    setChildren(details);
    let note;
    try {
      note = await api.get("/api/notes/" + encodeURIComponent(id));
    } catch (err) {
      if (seq === loadSeq) drawLoadError(id, err);
      return;
    }
    if (seq !== loadSeq) return;
    start(note, edit);
  }

  function drawLoadError(id, err) {
    setChildren(pane, backLink(),
      err instanceof ApiError && err.code === "not_found"
        ? emptyBlock("Note not found", "It may have been moved to the trash or deleted from your vault.")
        : err instanceof ApiError && err.code === "missing"
          ? h("div", { class: "empty" }, h("h2", {}, "This note's file is missing"), h("p", {}, "Its file is gone from your vault folder."), h("a", { href: href.dashboard() }, "See the hub dashboard"))
          : errorBanner(err, () => open(id)));
  }

  function start(note, edit) {
    const c = {
      id: note.id, note, text: note.text, draft: note.text, base: note.version,
      mode: "preview", timer: null, inflight: null, conflict: false, keepOld: false, previewSeq: 0, els: {},
    };
    cur = c;
    const name = note.has_note ? note.path : note.files[0]?.path ?? "";
    c.title = note.title || stripExt(basename(name)) || "Untitled";
    document.title = `${c.title} — Styli`;

    const folder = name.includes("/") ? name.slice(0, name.lastIndexOf("/")) : "";
    c.els.modified = h("time", { datetime: note.modified, title: fullDate(note.modified) }, relativeTime(note.modified));
    c.els.status = h("span", { class: "save-status", role: "status", "aria-live": "polite" });
    c.els.banner = h("div", { class: "conflict-slot" });
    c.els.prose = h("div", { class: "prose" });
    c.els.text = h("textarea", { class: "editor-text", id: "note-text", spellcheck: "true", "aria-label": "Note text", hidden: true });
    c.els.text.value = c.draft;
    c.els.text.addEventListener("input", () => {
      c.draft = c.els.text.value;
      if (!c.conflict) markUnsaved(c);
    });
    c.els.text.addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        save(c);
      }
    });
    c.els.editBtn = h("button", { type: "button", onclick: () => setMode(c, "edit") }, "Edit");
    c.els.previewBtn = h("button", { type: "button", onclick: () => setMode(c, "preview") }, "Preview");
    const modes = note.has_note
      ? h("div", { class: "seg", role: "group", "aria-label": "View" }, c.els.editBtn, c.els.previewBtn)
      : null;

    setChildren(pane,
      backLink(),
      h("div", { class: "note-bar" }, modes, c.els.status),
      h("p", { class: "view-sub" }, folder || "Vault", " · Edited ", c.els.modified),
      c.els.banner, c.els.prose, c.els.text);

    if (note.has_note) {
      setMode(c, edit ? "edit" : "preview");
    } else {
      setChildren(c.els.prose, h("h1", {}, c.title), h("p", {}, "This is a file in your vault with no note yet."));
    }
    setChildren(details, ...detailsFor(c));
  }

  // ---- edit and preview ----

  function setMode(c, mode) {
    if (cur !== c || !c.note.has_note) return;
    c.mode = mode;
    const editing = mode === "edit";
    c.els.text.hidden = !editing;
    c.els.prose.hidden = editing;
    c.els.editBtn.setAttribute("aria-pressed", String(editing));
    c.els.previewBtn.setAttribute("aria-pressed", String(!editing));
    if (editing) {
      c.els.text.focus();
    } else {
      drawPreview(c);
    }
  }

  async function drawPreview(c) {
    const seq = ++c.previewSeq;
    try {
      await renderMarkdown(c.els.prose, c.draft);
    } catch (err) {
      if (seq === c.previewSeq && cur === c) setChildren(c.els.prose, errorBanner(err, () => drawPreview(c)));
      return;
    }
    if (seq === c.previewSeq && !c.els.prose.querySelector("h1")) c.els.prose.prepend(h("h1", {}, c.title));
  }

  // ---- saving ----

  function setStatus(c, text, kind = "") {
    if (cur !== c) return;
    c.els.status.className = "save-status" + (kind ? " save-status--" + kind : "");
    setChildren(c.els.status, text);
  }

  function markUnsaved(c) {
    setStatus(c, "Unsaved changes");
    clearTimeout(c.timer);
    c.timer = setTimeout(() => save(c), AUTOSAVE_MS);
  }

  // Saves c.draft. Resolves true when everything typed so far is saved.
  function save(c) {
    clearTimeout(c.timer);
    if (c.inflight) return c.inflight.then((ok) => (ok ? save(c) : false));
    if (c.conflict) return Promise.resolve(false);
    if (c.draft === c.text) return Promise.resolve(true);
    c.inflight = doSave(c).finally(() => { c.inflight = null; });
    return c.inflight;
  }

  async function doSave(c) {
    while (c.draft !== c.text) {
      const sent = c.draft;
      setStatus(c, "Saving…");
      try {
        // After a conflict, "save mine anyway" asks the server to keep the text it replaces as its own version.
        const res = await api.put(`/api/notes/${encodeURIComponent(c.id)}`, { text: sent, base: c.base, keep_old: c.keepOld || undefined });
        c.base = res.version;
        c.text = sent;
        c.keepOld = false;
      } catch (err) {
        if (err instanceof ApiError && err.code === "conflict") {
          c.conflict = true;
          setStatus(c, "Not saved: this note changed", "error");
          drawConflict(c);
        } else {
          setStatus(c, [err instanceof ApiError ? err.message : "Couldn't save.", " Your text is still here. ",
            h("button", { class: "link-button", type: "button", onclick: () => save(c) }, "Try again")], "error");
        }
        return false;
      }
      const now = new Date();
      if (cur === c) {
        c.els.modified.textContent = relativeTime(now);
        c.els.modified.dateTime = now.toISOString();
      }
      const title = titleOf(sent);
      if (title) c.title = title;
      host.noteChanged(c.id, { title: c.title, modified: now.toISOString() });
    }
    setStatus(c, "Saved");
    return true;
  }

  // ---- a conflict: the note changed somewhere else while this one was open ----

  function drawConflict(c) {
    if (cur !== c) return;
    const history = c.note.history_enabled;
    const theirsBox = h("div", { class: "theirs", hidden: true });
    const showBtn = h("button", { class: "sty-btn sty-btn--secondary", type: "button" }, "Show their version");
    showBtn.addEventListener("click", async () => {
      if (!theirsBox.hidden) { theirsBox.hidden = true; showBtn.textContent = "Show their version"; return; }
      try {
        const theirs = await fetchTheirs(c);
        setChildren(theirsBox, h("h3", {}, "Their version"), h("pre", {}, theirs.text));
        theirsBox.hidden = false;
        showBtn.textContent = "Hide their version";
      } catch (err) {
        setChildren(theirsBox, errorBanner(err, () => showBtn.click()));
        theirsBox.hidden = false;
      }
    });
    const useBtn = h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: async () => {
      try {
        const theirs = await fetchTheirs(c);
        c.text = c.draft = theirs.text;
        c.base = theirs.version;
        c.els.text.value = theirs.text;
        resolveConflict(c);
        if (c.mode === "preview") drawPreview(c);
      } catch (err) { setStatus(c, err instanceof ApiError ? err.message : "Couldn't load their version.", "error"); }
    } }, "Use their version");
    const mineBtn = h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: async () => {
      try {
        c.base = (await fetchTheirs(c)).version;
        c.keepOld = true;
        resolveConflict(c);
        await save(c);
      } catch (err) { setStatus(c, err instanceof ApiError ? err.message : "Couldn't save.", "error"); }
    } }, "Save mine anyway");
    setChildren(c.els.banner, h("div", { class: "banner banner--danger", role: "alert" },
      h("div", { class: "grow" },
        h("strong", {}, "This note changed while you were editing."),
        h("p", { class: "banner-text" }, "Your text is still here, but it hasn't been saved. ",
          history ? "Their version stays in this note's version history." : "Version history is off for this note, so their version would be lost if you save yours."),
        h("div", { class: "banner-actions" }, showBtn, useBtn, mineBtn)),
      ), theirsBox);
  }

  async function fetchTheirs(c) {
    const n = await api.get("/api/notes/" + encodeURIComponent(c.id));
    return { text: n.text, version: n.version };
  }

  function resolveConflict(c) {
    c.conflict = false;
    setChildren(c.els.banner);
    setStatus(c, c.draft === c.text ? "Saved" : "Unsaved changes");
  }

  // ---- leaving ----

  // Saves what is pending. False means it could not be saved, so the caller
  // must stay on this note.
  async function flush() {
    if (!cur) return true;
    return save(cur);
  }

  // Called before the app moves away from this note. Moving to another view
  // is only allowed once everything typed is saved.
  async function canLeave(nextRoute) {
    if (!cur) return true;
    if (nextRoute?.name === "note" && nextRoute.id === cur.id) return true;
    const ok = await flush();
    if (!ok && cur) {
      setMode(cur, "edit");
      cur.els.text.focus();
    }
    return ok;
  }

  // Forget the open note, so the next open() loads it again from the server
  // (after a restore, for example). Only call this when nothing is unsaved.
  function invalidate() {
    cur = null;
  }

  const isDirty = () => !!cur && (cur.draft !== cur.text || !!cur.inflight || cur.conflict);

  window.addEventListener("beforeunload", (e) => {
    if (isDirty()) { e.preventDefault(); e.returnValue = ""; }
  });
  // On a phone the tab can disappear without a warning: save as soon as it is hidden.
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden" && cur && !cur.conflict) save(cur);
  });

  function clear() {
    cur = null;
    loadSeq++;
    document.title = "Styli";
    setChildren(pane, emptyBlock("Pick a note", "Choose a note from the list to read it here."));
    setChildren(details);
  }

  // ---- the details panel ----

  function detailsFor(c) {
    const { note } = c;
    const out = [h("div", { class: "chip-row" }, h("span", { class: "sty-chip" }, note.has_note ? "Markdown" : "File only"),
      note.tags.map((t) => h("span", { class: "sty-chip" }, t)))];
    if (note.files.length) {
      out.push(h("section", { class: "detail-section" }, h("h2", {}, "Files"),
        h("ul", { class: "file-list" }, note.files.map((f) =>
          h("li", {}, h("a", { href: `/api/notes/${encodeURIComponent(note.id)}/files/${f.index}`, target: "_blank", rel: "noopener noreferrer" }, f.name))))));
    }
    if (note.has_note) out.push(historyToggle(c));
    out.push(h("div", { class: "detail-actions" },
      h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: () => moveDialog(c) }, "Move…"),
      h("button", { class: "sty-btn sty-btn--danger", type: "button", onclick: () => trashDialog(c) }, "Move to trash")));
    return out;
  }

  function historyToggle(c) {
    const { note } = c;
    const input = h("input", { type: "checkbox", id: "history-toggle", checked: note.history_enabled });
    const msg = h("p", { class: "sty-help", role: "status", "aria-live": "polite" }, historyText(note.history_enabled));
    input.addEventListener("change", async () => {
      const wanted = input.checked;
      input.disabled = true;
      try {
        const res = await api.put(`/api/notes/${encodeURIComponent(note.id)}/history-enabled`, { on: wanted });
        input.checked = note.history_enabled = res.history_enabled;
        msg.textContent = res.history_enabled ? historyText(true) : "Older versions are no longer being kept. The ones already saved stay.";
      } catch (err) {
        input.checked = !wanted;
        msg.textContent = err instanceof ApiError ? err.message : "Couldn't change that.";
      }
      input.disabled = false;
    });
    return h("section", { class: "detail-section" },
      h("label", { class: "sty-toggle", for: "history-toggle" }, input, h("span", {}), "Version history"), msg,
      h("a", { href: href.history(note.id) }, "See saved versions"));
  }

  const historyText = (on) => (on ? "Older versions of this note are being kept." : "Older versions are not being kept.");

  // ---- move and trash ----

  async function saveFirst(c) {
    if (!(await save(c))) throw new UserError("Your changes couldn't be saved yet, so nothing was moved. Fix that first.");
  }

  function moveDialog(c) {
    const here = (c.note.path || c.note.files[0]?.path || "").split("/").slice(0, -1).join("/");
    return openDialog({
      title: "Move this note",
      description: c.note.files.length ? "The note and its attached files move together." : undefined,
      fields: [textField({ label: "Folder", name: "folder", value: here, suggestions: host.folders(),
        help: "Leave it empty for the top level. Type a new name to make a new folder." })],
      submitLabel: "Move",
      onSubmit: async (data) => {
        const folder = folderPath(String(data.get("folder")));
        if (folder === null) throw new UserError("Folder names can't start with a dot or contain a colon.");
        await saveFirst(c);
        await api.post(`/api/notes/${encodeURIComponent(c.id)}/move`, { folder });
        host.noteMoved(c.id);
        if (cur === c) {
          const wasEditing = c.mode === "edit";
          cur = null; // reopen from the server, so the path, details and files are current
          await open(c.id, { edit: wasEditing });
        }
      },
    });
  }

  async function trashDialog(c) {
    let days = null;
    try { days = (await api.get("/api/settings")).trash_retention_days; } catch { /* the wording below copes */ }
    const keep = days === 0 ? "It stays in the trash until you delete it for good."
      : days ? `It stays in the trash for ${days} ${days === 1 ? "day" : "days"}, then it is deleted for good.`
        : "You can bring it back from the trash.";
    const files = c.note.files.length;
    return openDialog({
      title: "Move this note to the trash?",
      description: `${files ? `The note and its ${files === 1 ? "attached file" : files + " attached files"} move together. ` : ""}${keep}`,
      submitLabel: "Move to trash",
      danger: true,
      onSubmit: async () => {
        await saveFirst(c);
        await api.post(`/api/notes/${encodeURIComponent(c.id)}/trash`);
        const trashed = { id: c.id, title: c.title };
        if (cur === c) cur = null;
        host.noteTrashed(trashed);
      },
    });
  }

  return { pane, details, open, clear, invalidate, canLeave, flush, isDirty };
}

function emptyBlock(title, text) {
  return h("div", { class: "empty" }, h("h2", {}, title), h("p", {}, text));
}

function errorBanner(err, retry) {
  return h("div", { class: "banner banner--danger", role: "alert" },
    h("span", { class: "grow" }, err instanceof ApiError ? err.message : "Something went wrong."),
    h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: retry }, "Try again"));
}

const basename = (p) => p.slice(p.lastIndexOf("/") + 1);
const stripExt = (n) => n.replace(/\.[^.]+$/, "");
