// Conflicts: notes a sync merge flagged. Each one shows the lines both devices
// changed side by side, with buttons to keep one copy or both; the note itself
// can also be edited in the note view.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { href } from "../router.js";
import { showToast } from "../toast.js";
import { parseMarkers, clashCount, resolveClash } from "../markers.js";

let items = [];
let loadSeq = 0;
const status = h("p", { class: "list-status", role: "status", "aria-live": "polite" });
const list = h("ul", { class: "trash-list" });
export const element = h("section", { class: "trash-view" },
  h("h1", { class: "view-title" }, "Conflicts"),
  h("p", { class: "view-sub" }, "Two devices changed these notes at once. Where they changed the same lines, both copies are shown here: keep one or both. The text you replace stays in the note's version history."),
  status, list);

const changed = () => document.dispatchEvent(new CustomEvent("conflicts-changed", { detail: items.length }));

export async function show() {
  document.title = "Conflicts — Styli";
  const seq = ++loadSeq;
  status.textContent = "Loading…";
  try {
    const res = await api.get("/api/conflicts");
    if (seq !== loadSeq) return;
    items = res.conflicts;
    draw();
    changed();
  } catch (err) {
    if (seq !== loadSeq) return;
    status.textContent = "";
    setChildren(list, h("li", { class: "banner banner--danger", role: "alert" },
      err instanceof ApiError ? err.message : "Couldn't load the conflicts.",
      h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: show }, "Try again")));
  }
}

function draw() {
  status.textContent = items.length ? `${items.length} ${items.length === 1 ? "note" : "notes"}` : "";
  if (!items.length) {
    setChildren(list, h("li", { class: "empty" }, h("h2", {}, "No conflicts"), h("p", {}, "Nothing is waiting to be settled.")));
    return;
  }
  setChildren(list, items.map(item));
}

const titleOf = (c) => c.title || c.path.slice(c.path.lastIndexOf("/") + 1).replace(/\.[^.]+$/, "") || "Untitled";

function side(label, lines) {
  return h("div", { class: "clash-side" }, h("strong", {}, label), h("pre", {}, lines.join("\n") || "(nothing)"));
}

// settle keeps one copy (or both) of clash i in c's note. It works on the
// text this screen shows: if the note changed since, nothing is written and
// the list is loaded again.
async function settle(c, i, choice) {
  const note = await api.get(`/api/notes/${encodeURIComponent(c.id)}`);
  const next = note.text === c.text ? resolveClash(note.text, i, choice) : null;
  if (next === null) {
    showToast("This note changed. Showing it as it is now.");
    return show();
  }
  await api.put(`/api/notes/${encodeURIComponent(c.id)}`, { text: next, base: note.version, settle: true });
  c.text = next;
  if (!clashCount(parseMarkers(next))) {
    await api.delete(`/api/notes/${encodeURIComponent(c.id)}/conflict`);
    items = items.filter((x) => x.id !== c.id);
    showToast(`“${titleOf(c)}” is settled.`);
    changed();
  }
  draw();
}

function clashActions(c, i) {
  const buttons = [["Keep device copy", "device"], ["Keep atrium copy", "atrium"], ["Keep both", "both"]].map(([label, choice]) =>
    h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: async () => {
      buttons.forEach((b) => { b.disabled = true; });
      try {
        await settle(c, i, choice);
      } catch (err) {
        buttons.forEach((b) => { b.disabled = false; });
        showToast(err instanceof ApiError ? err.message : "Couldn't save the note.");
      }
    } }, label));
  return h("div", { class: "banner-actions clash-actions", role: "group", "aria-label": `Clash ${i + 1}` }, buttons);
}

function item(c) {
  const title = titleOf(c);
  const clashes = parseMarkers(c.text).filter((p) => p.type === "clash");
  const body = clashes.length
    ? clashes.map((p, i) => [h("div", { class: "clash" }, side("Device copy", p.mine), side("Atrium copy", p.theirs)), clashActions(c, i)])
    : [h("p", { class: "view-sub" }, "No text clash: an attached file changed on both sides, so both copies were kept.")];
  const btn = h("button", { class: "sty-btn sty-btn--secondary", type: "button", "aria-label": `Mark ${title} as settled`, onclick: async () => {
    btn.disabled = true;
    try {
      await api.delete(`/api/notes/${encodeURIComponent(c.id)}/conflict`);
      items = items.filter((x) => x.id !== c.id);
      draw();
      changed();
    } catch (err) {
      btn.disabled = false;
      showToast(err instanceof ApiError ? err.message : "Couldn't mark it as settled.");
    }
  } }, "Mark as settled");
  return h("li", { class: "trash-item conflict-item" },
    h("div", { class: "trash-item-text" },
      h("span", { class: "note-item-title" }, title),
      h("span", { class: "note-item-meta" }, clashes.length ? `${clashes.length} ${clashes.length === 1 ? "clash" : "clashes"}` : "Attached file"),
      ...body),
    h("div", { class: "trash-actions" },
      h("a", { class: "sty-btn sty-btn--secondary", href: href.note(c.id) }, "Open note"), btn));
}
