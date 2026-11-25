// The tabularium view: a note list with search, the open note, and its details.
// It keeps its state (folder, search, list) while the user moves between notes,
// so the list does not reload or lose its place.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { href } from "../router.js";
import { relativeTime, fullDate } from "../time.js";
import { openDialog, textField, UserError } from "../dialogs.js";
import { noteFileName, folderPath } from "../names.js";
import { showToast } from "../toast.js";
import { createNotePane } from "./note.js";
import { createHistoryPane } from "./history.js";

const PAGE = 50;
// The server marks search matches with these two control characters, so
// brackets and other punctuation in a note can never be mistaken for a match.
const MATCH_START = "\u0001";
const MATCH_END = "\u0002";

let app = { refreshFolders() {}, folders: () => [] };
let state = { folder: "", q: "", hits: [], more: false, loading: false, error: null, loaded: false };
let selectedId = null;
let listSeq = 0;
let searchTimer = null;
let pendingEdit = null; // a note just created: open it in the editor

const searchInput = h("input", { class: "sty-input", id: "note-search", type: "search", autocomplete: "off", spellcheck: "false" });
const status = h("p", { class: "list-status", role: "status", "aria-live": "polite" });
const listEl = h("ul", { class: "note-list" });
const moreBtn = h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: () => loadList(true) }, "Show more");
const newBtn = h("button", { class: "sty-btn sty-btn--primary", type: "button", onclick: () => newNoteDialog() }, "New note");
const listPane = h("section", { class: "vault-list" },
  newBtn,
  h("label", { class: "sty-field", for: "note-search" }, h("span", { class: "sr-only" }, "Search notes"), searchInput),
  status, listEl, moreBtn);

const note = createNotePane({
  folders: () => app.folders(),
  listHref: () => (state.folder ? href.folder(state.folder) : href.home()),
  noteChanged(id, patch) {
    const hit = state.hits.find((x) => x.id === id);
    if (hit) { Object.assign(hit, patch); drawList(); }
  },
  noteMoved() {
    loadList(false);
    app.refreshFolders();
  },
  noteTrashed({ id, title }) {
    location.hash = state.folder ? href.folder(state.folder) : href.home();
    loadList(false);
    app.refreshFolders();
    document.dispatchEvent(new CustomEvent("trash-changed"));
    showToast(`Moved “${title}” to the trash.`, {
      actionLabel: "Undo",
      onAction: async () => {
        try {
          await api.post(`/api/trash/${encodeURIComponent(id)}/restore`, {});
          loadList(false);
          app.refreshFolders();
          document.dispatchEvent(new CustomEvent("trash-changed"));
          location.hash = href.note(id);
        } catch (err) {
          showToast(err instanceof ApiError ? err.message : "Couldn't bring it back.");
        }
      },
    });
  },
});

const historyView = createHistoryPane({
  restored(id, title, when) {
    note.invalidate(); // the note on screen is now out of date
    loadList(false);
    showToast(`Restored “${title}” to the version from ${fullDate(when)}. The text it replaced is still in the history.`);
    location.hash = href.note(id);
  },
});

// data-view says whether the note or its version history is shown beside the list.
export const element = h("div", { class: "vault", "data-pane": "list", "data-view": "note" }, listPane, note.pane, note.details, historyView.pane);

searchInput.addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    state.q = searchInput.value.trim();
    loadList(false);
  }, 250);
});
searchInput.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && searchInput.value) {
    searchInput.value = "";
    state.q = "";
    loadList(false);
  }
});

// callbacks: refreshFolders() reloads the sidebar; folders() returns the names.
export function init(callbacks) {
  app = callbacks;
}

// The folder the list is showing, for the sidebar's highlight.
export const currentFolder = () => state.folder;

// May the app leave the open note? Saves it first; false means it could not be saved.
export const canLeave = (nextRoute) => note.canLeave(nextRoute);

export function show(route) {
  element.dataset.view = route.name === "history" ? "history" : "note";
  if (route.name === "history") {
    element.dataset.pane = "note";
    if (!state.loaded) loadList(false);
    selectedId = route.id;
    markSelected();
    historyView.show(route.id);
    return;
  }
  if (route.name === "note") {
    element.dataset.pane = "note";
    if (!state.loaded) loadList(false);
    const edit = pendingEdit === route.id;
    if (edit) pendingEdit = null;
    selectedId = route.id;
    markSelected();
    note.open(route.id, { edit });
    return;
  }
  element.dataset.pane = "list";
  selectedId = null;
  note.clear();
  const folder = route.name === "folder" ? route.path : "";
  document.title = folder ? `${folder.split("/").pop()} — Styli` : "Tabularium — Styli";
  if (!state.loaded || folder !== state.folder) {
    state.folder = folder;
    state.q = "";
    searchInput.value = "";
    loadList(false);
  } else {
    drawList();
  }
}

// ---- making a note ----

async function newNoteDialog() {
  let created = null;
  const ok = await openDialog({
    title: "New note",
    fields: [
      textField({ label: "Name", name: "name", required: true, placeholder: "Lecture 13" }),
      textField({ label: "Folder", name: "folder", value: state.folder, suggestions: app.folders(),
        help: "Leave it empty for the top level. Type a new name to make a new folder." }),
    ],
    submitLabel: "Create note",
    onSubmit: async (data) => {
      const name = noteFileName(String(data.get("name")));
      const folder = folderPath(String(data.get("folder")));
      if (folder === null) throw new UserError("Folder names can't start with a dot or contain a colon.");
      const path = (folder ? folder + "/" : "") + name + ".md";
      created = await api.post("/api/notes", { path, text: `# ${name}\n\n` });
    },
  });
  if (!ok || !created) return;
  pendingEdit = created.id;
  loadList(false);
  app.refreshFolders();
  location.hash = href.note(created.id);
}

// ---- the list ----

async function loadList(append) {
  const seq = ++listSeq;
  state.loading = true;
  state.error = null;
  if (!append) {
    state.hits = [];
    state.more = false;
  }
  drawList();
  const params = new URLSearchParams({ limit: String(PAGE), offset: String(append ? state.hits.length : 0) });
  if (state.q) params.set("q", state.q);
  if (state.folder) params.set("folder", state.folder);
  try {
    const { notes } = await api.get("/api/notes?" + params);
    if (seq !== listSeq) return; // a newer request has taken over
    state.hits = append ? state.hits.concat(notes) : notes;
    state.more = notes.length === PAGE;
    state.loaded = true;
    app.refreshFolders();
  } catch (err) {
    if (seq !== listSeq) return;
    state.error = err;
  }
  state.loading = false;
  drawList();
}

function drawList() {
  const scope = state.folder ? state.folder.split("/").pop() : "your tabularium";
  searchInput.placeholder = `Search ${scope}`;
  listPane.setAttribute("aria-label", `Notes in ${state.folder || "your tabularium"}`);

  if (state.error) {
    status.textContent = "";
    setChildren(listEl, h("li", {}, errorBanner(state.error, () => loadList(false))));
    moreBtn.hidden = true;
    return;
  }
  if (state.loading && state.hits.length === 0) {
    status.textContent = "Loading…";
    setChildren(listEl);
    moreBtn.hidden = true;
    return;
  }
  if (state.hits.length === 0) {
    status.textContent = "";
    setChildren(listEl, h("li", { class: "list-empty" }, emptyList()));
    moreBtn.hidden = true;
    return;
  }
  status.textContent = `${state.hits.length}${state.more ? "+" : ""} ${state.hits.length === 1 && !state.more ? "note" : "notes"}`;
  setChildren(listEl, state.hits.map(noteItem));
  moreBtn.hidden = !state.more;
  moreBtn.disabled = state.loading;
}

function emptyList() {
  if (state.q) {
    return [h("strong", {}, "No notes match"), h("span", {}, ` “${state.q}”.`), h("span", { class: "view-sub" }, "Try fewer or different words.")];
  }
  if (state.folder) {
    return [h("strong", {}, "No notes in this folder yet"), h("span", { class: "view-sub" }, "Press New note to write one here.")];
  }
  return [h("strong", {}, "Your tabularium is empty"),
    h("span", { class: "view-sub" }, "Press New note to write one, or drop Markdown files into your vault folder and they will appear here.")];
}

function noteItem(hit) {
  const folder = hit.path.includes("/") ? hit.path.slice(0, hit.path.lastIndexOf("/")) : "";
  const isNote = hit.path.toLowerCase().endsWith(".md");
  const selected = hit.id === selectedId;
  return h("li", {},
    h("a", { class: "note-item" + (selected ? " is-selected" : ""), href: href.note(hit.id), "aria-current": selected ? "true" : false, "data-id": hit.id },
      h("span", { class: "note-item-title" }, hit.title || basename(hit.path)),
      hit.snippet ? h("span", { class: "note-item-snippet" }, snippetNodes(hit.snippet)) : null,
      h("span", { class: "note-item-meta" },
        h("span", {}, folder || "Top level", " · ", h("time", { datetime: hit.modified, title: fullDate(hit.modified) }, relativeTime(hit.modified))),
        hit.conflict ? h("span", { class: "sty-chip sty-chip--danger" }, "Disputed") : null,
        isNote ? null : h("span", { class: "sty-chip" }, "File only"))));
}

function markSelected() {
  for (const a of listEl.querySelectorAll("a.note-item")) {
    const on = a.dataset.id === selectedId;
    a.classList.toggle("is-selected", on);
    if (on) a.setAttribute("aria-current", "true");
    else a.removeAttribute("aria-current");
  }
}

// Turn a snippet into text and <mark> nodes, never into HTML.
function snippetNodes(snippet) {
  const out = [];
  let rest = snippet.replace(/\s+/g, " ");
  while (rest) {
    const start = rest.indexOf(MATCH_START);
    if (start < 0) { out.push(rest.replaceAll(MATCH_END, "")); break; }
    if (start > 0) out.push(rest.slice(0, start));
    const end = rest.indexOf(MATCH_END, start);
    if (end < 0) { out.push(rest.slice(start + 1)); break; }
    out.push(h("mark", { class: "hit" }, rest.slice(start + 1, end)));
    rest = rest.slice(end + 1);
  }
  return out;
}

function errorBanner(err, retry) {
  return h("div", { class: "banner banner--danger", role: "alert" },
    h("span", { class: "grow" }, err instanceof ApiError ? err.message : "Something went wrong."),
    h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: retry }, "Try again"));
}

const basename = (p) => p.slice(p.lastIndexOf("/") + 1);
