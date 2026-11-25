// The trash: what was moved there, when it will be deleted for good, and the
// two things you can do with each item: bring it back, or delete it for good.
// Above it, recovery candidates: versions a delete everywhere would otherwise
// have thrown away, kept until someone decides.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { href } from "../router.js";
import { relativeTime, relativeFuture, fullDate } from "../time.js";
import { openDialog, textField, UserError } from "../dialogs.js";
import { folderPath } from "../names.js";
import { showToast } from "../toast.js";
import { recoveryWhy } from "../attention.js";

let app = { refreshFolders() {}, folders: () => [] };
let items = [];
let candidates = [];
let retentionDays = null; // null: unknown; 0: kept until deleted by hand
let loadSeq = 0;

const status = h("p", { class: "list-status", role: "status", "aria-live": "polite" });
const list = h("ul", { class: "trash-list" });
const emptyBtn = h("button", { class: "sty-btn sty-btn--danger", type: "button", onclick: () => emptyDialog() }, "Empty trash");
const intro = h("p", { class: "view-sub" });
const recovery = h("section", { class: "recovery-section", "aria-labelledby": "recovery-title" });
export const element = h("section", { class: "trash-view" },
  h("h1", { class: "view-title" }, "Trash"), recovery, intro,
  h("div", { class: "trash-bar" }, status, emptyBtn), list);

// callbacks: refreshFolders() reloads the sidebar; folders() returns the names.
export function init(callbacks) {
  app = callbacks;
}

// Tell the sidebar (which shows a count) that the trash changed.
const changed = () => document.dispatchEvent(new CustomEvent("trash-changed"));

export async function show() {
  document.title = "Trash — Styli";
  await load();
}

async function load() {
  const seq = ++loadSeq;
  status.textContent = "Loading…";
  try {
    const [trash, settings, rec] = await Promise.all([
      api.get("/api/trash"),
      api.get("/api/settings").catch(() => null), // the wording below copes without it
      api.get("/api/recovery"),
    ]);
    if (seq !== loadSeq) return;
    items = trash.trash.sort((a, b) => new Date(b.deleted_at) - new Date(a.deleted_at));
    candidates = rec.candidates;
    retentionDays = settings ? settings.trash_retention_days : null;
    draw();
  } catch (err) {
    if (seq !== loadSeq) return;
    status.textContent = "";
    emptyBtn.hidden = true;
    setChildren(list, h("li", {}, errorBanner(err, load)));
  }
}

function draw() {
  drawRecovery();
  setChildren(intro, retentionText());
  emptyBtn.hidden = items.length === 0;
  status.textContent = items.length ? `${items.length} ${items.length === 1 ? "item" : "items"}` : "";
  if (items.length === 0) {
    setChildren(list, h("li", { class: "empty" }, h("h2", {}, "The trash is empty"), h("p", {}, "Notes you move to the trash show up here, so you can bring them back.")));
    return;
  }
  setChildren(list, items.map(item));
}

function retentionText() {
  if (retentionDays === 0) return "Items stay here until you delete them for good.";
  if (retentionDays) return `Items stay here for ${retentionDays} ${retentionDays === 1 ? "day" : "days"}, then they are deleted for good.`;
  return "Items stay here until they are deleted for good.";
}

function titleOf(t) {
  const path = t.note_path || t.files[0] || "";
  return path.slice(path.lastIndexOf("/") + 1).replace(/\.[^.]+$/, "") || "Untitled";
}

function item(t) {
  const title = titleOf(t);
  const due = retentionDays ? new Date(new Date(t.deleted_at).getTime() + retentionDays * 86400000) : null;
  const dueText = due ? (relativeFuture(due) ? `Deleted for good ${relativeFuture(due)}` : "Due to be deleted soon") : null;
  const attached = t.note_path ? t.files.length : Math.max(0, t.files.length - 1);
  return h("li", { class: "trash-item" },
    h("div", { class: "trash-item-text" },
      h("span", { class: "note-item-title" }, title),
      h("span", { class: "note-item-meta" },
        h("span", {}, t.folder && t.folder !== "." ? t.folder : "Top level", " · Deleted ",
          h("time", { datetime: t.deleted_at, title: fullDate(t.deleted_at) }, relativeTime(t.deleted_at)),
          dueText ? ` · ${dueText}` : ""),
        attached ? h("span", { class: "sty-chip" }, `${attached} attached ${attached === 1 ? "file" : "files"}`) : null,
        t.note_path ? null : h("span", { class: "sty-chip" }, "File only"),
        t.local_only ? h("span", { class: "sty-chip" }, "Still on the atrium") : null)),
    h("div", { class: "trash-actions" },
      h("button", { class: "sty-btn sty-btn--secondary", type: "button", "aria-label": `Restore ${title}`, onclick: () => restore(t, title) }, "Restore"),
      h("button", { class: "sty-btn sty-btn--danger", type: "button", "aria-label": `Delete ${title} for good`, onclick: () => deleteDialog(t, title) }, "Delete forever")));
}

// ---- recovery candidates ----

function drawRecovery() {
  recovery.hidden = candidates.length === 0;
  setChildren(recovery,
    h("h2", { id: "recovery-title" }, "Recovery candidates"),
    h("p", { class: "view-sub" }, "These versions would have been lost to a delete everywhere. The delete stands; each one stays here until you restore it or delete it for good."),
    h("ul", { class: "trash-list" }, candidates.map(candidateItem)));
}

function candidateItem(c) {
  const title = titleOf(c);
  return h("li", { class: "trash-item" },
    h("div", { class: "trash-item-text" },
      h("span", { class: "note-item-title" }, title),
      h("span", { class: "note-item-meta" }, recoveryWhy(c)),
      h("span", { class: "note-item-meta" },
        h("span", {}, c.folder && c.folder !== "." ? c.folder : "Top level",
          c.deleted_at ? [" · Deleted ", h("time", { datetime: c.deleted_at, title: fullDate(c.deleted_at) }, relativeTime(c.deleted_at))] : "",
          " · Kept ", h("time", { datetime: c.added_at, title: fullDate(c.added_at) }, relativeTime(c.added_at))))),
    h("div", { class: "trash-actions" },
      h("button", { class: "sty-btn sty-btn--secondary", type: "button", "aria-label": `Restore this version of ${title}`, onclick: () => restoreCandidate(c, title) }, "Restore"),
      h("button", { class: "sty-btn sty-btn--danger", type: "button", "aria-label": `Delete this version of ${title} for good`, onclick: () => deleteCandidateDialog(c, title) }, "Delete forever")));
}

async function restoreCandidate(c, title) {
  try {
    await api.post(`/api/recovery/${encodeURIComponent(c.key)}/restore`, {});
  } catch (err) {
    if (err instanceof ApiError && err.code === "exists") return restoreCandidateElsewhere(c, title);
    if (err instanceof ApiError && err.code === "not_found") { showToast("That version is already gone."); return load(); }
    return showToast(err instanceof ApiError ? err.message : "Couldn't restore it.");
  }
  restored(c, title);
}

async function restoreCandidateElsewhere(c, title) {
  const ok = await openDialog({
    title: `Restore “${title}” to another folder`,
    description: `Something with the same name is already in ${c.folder && c.folder !== "." ? "“" + c.folder + "”" : "the top level"}. Choose where to put it instead.`,
    fields: [textField({ label: "Folder", name: "folder", value: "", suggestions: app.folders(), help: "Leave it empty for the top level. Type a new name to make a new folder." })],
    submitLabel: "Restore",
    onSubmit: async (data) => {
      const folder = folderPath(String(data.get("folder")));
      if (folder === null) throw new UserError("Folder names can't start with a dot or contain a colon.");
      await api.post(`/api/recovery/${encodeURIComponent(c.key)}/restore`, { folder });
    },
  });
  if (ok) restored(c, title);
}

function deleteCandidateDialog(c, title) {
  return openDialog({
    title: `Delete this version of “${title}” for good?`,
    description: "This can't be undone. This version and its attached files are deleted from your vault. The note itself stays deleted.",
    submitLabel: "Delete for good",
    danger: true,
    onSubmit: async () => {
      try {
        await api.post(`/api/recovery/${encodeURIComponent(c.key)}/delete-forever`);
      } catch (err) {
        if (!(err instanceof ApiError && err.code === "not_found")) throw err; // already gone is fine
      }
      changed();
      load();
    },
  });
}

// ---- actions ----

async function restore(t, title) {
  try {
    await api.post(`/api/trash/${encodeURIComponent(t.id)}/restore`, {});
  } catch (err) {
    if (err instanceof ApiError && err.code === "exists") return restoreElsewhere(t, title);
    if (err instanceof ApiError && err.code === "not_found") { showToast("That item is already gone."); return load(); }
    return showToast(err instanceof ApiError ? err.message : "Couldn't restore it.");
  }
  restored(t, title);
}

// Something with the same name is already where it came from.
async function restoreElsewhere(t, title) {
  const ok = await openDialog({
    title: `Restore “${title}” to another folder`,
    description: `Something with the same name is already in ${t.folder && t.folder !== "." ? "“" + t.folder + "”" : "the top level"}. Choose where to put it instead.`,
    fields: [textField({ label: "Folder", name: "folder", value: "", suggestions: app.folders(), help: "Leave it empty for the top level. Type a new name to make a new folder." })],
    submitLabel: "Restore",
    onSubmit: async (data) => {
      const folder = folderPath(String(data.get("folder")));
      if (folder === null) throw new UserError("Folder names can't start with a dot or contain a colon.");
      await api.post(`/api/trash/${encodeURIComponent(t.id)}/restore`, { folder });
    },
  });
  if (ok) restored(t, title);
}

function restored(t, title) {
  showToast(`Restored “${title}”.`, { actionLabel: "Open", onAction: () => { location.hash = href.note(t.id); } });
  app.refreshFolders();
  changed();
  load();
}

function deleteDialog(t, title) {
  return openDialog({
    title: `Delete “${title}” for good?`,
    description: "This can't be undone. The note, its attached files and its saved versions are deleted from your vault.",
    submitLabel: "Delete for good",
    danger: true,
    onSubmit: async () => {
      try {
        await api.post(`/api/trash/${encodeURIComponent(t.id)}/delete-forever`);
      } catch (err) {
        if (!(err instanceof ApiError && err.code === "not_found")) throw err; // already gone is fine
      }
      changed();
      load();
    },
  });
}

function emptyDialog() {
  const all = [...items];
  return openDialog({
    title: "Empty the trash?",
    description: `This deletes ${all.length === 1 ? "the 1 item" : `all ${all.length} items`} in the trash for good. It can't be undone.`,
    submitLabel: "Delete everything",
    danger: true,
    onSubmit: async () => {
      let failed = 0;
      let firstError = "";
      for (const t of all) {
        try {
          await api.post(`/api/trash/${encodeURIComponent(t.id)}/delete-forever`);
        } catch (err) {
          if (err instanceof ApiError && err.code === "not_found") continue;
          failed++;
          firstError ||= err instanceof ApiError ? err.message : "Something went wrong.";
        }
      }
      changed();
      await load();
      if (failed) throw new UserError(`${failed} of ${all.length} couldn't be deleted. ${firstError}`);
    },
  });
}

function errorBanner(err, retry) {
  return h("div", { class: "banner banner--danger", role: "alert" },
    h("span", { class: "grow" }, err instanceof ApiError ? err.message : "Something went wrong."),
    h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: retry }, "Try again"));
}
