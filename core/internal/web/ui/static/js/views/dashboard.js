// The atrium dashboard: how the atrium is doing, and anything that needs a decision.
// Nothing here changes by itself. The maid never retries something it held
// back, and a missing note is only ever moved to the trash when asked.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { href } from "../router.js";
import { relativeTime, fullDate } from "../time.js";
import { openDialog, UserError } from "../dialogs.js";
import { showToast } from "../toast.js";
import { attentionItems, describeJob, describeTimeCheck, describeOffset, baseName, recoveryWhy } from "../attention.js";

const POLL_MS = 20000;
let app = { refreshFolders() {} };
let data = null; // { status, missing }
let seq = 0;
let pollTimer = null;
let preview = null; // the last preview result, shown in the maid card until the next load

export const element = h("section", { class: "dashboard" });

export function init(callbacks) {
  app = callbacks;
}

// Tell the sidebar (which shows a count) that what needs attention may have changed.
const changed = () => document.dispatchEvent(new CustomEvent("attention-changed"));

export async function show() {
  document.title = "Atrium dashboard — Styli";
  preview = null;
  setChildren(element, h("h1", { class: "view-title" }, "Atrium dashboard"), h("p", { class: "view-sub", role: "status" }, "Loading…"));
  await load();
  clearInterval(pollTimer);
  pollTimer = setInterval(() => {
    if (!element.isConnected) return clearInterval(pollTimer); // the person went elsewhere
    if (document.visibilityState === "visible") load();
  }, POLL_MS);
}

async function load() {
  const mine = ++seq;
  try {
    const [status, missing] = await Promise.all([api.get("/api/status"), api.get("/api/missing")]);
    if (mine !== seq) return;
    data = { status, missing: missing.missing };
    draw();
  } catch (err) {
    if (mine !== seq) return;
    if (data) return; // keep showing what we had; the next poll tries again
    setChildren(element, h("h1", { class: "view-title" }, "Atrium dashboard"), errorBanner(err, load));
  }
}

function draw() {
  const { status } = data;
  const hub = status.hub;
  const run = status.last_maid_run;
  const items = attentionItems({ run, missing: data.missing, failed: status.failed_commits, recovery: status.recovery });
  document.dispatchEvent(new CustomEvent("attention-count", { detail: items.length })); // keeps the sidebar badge in step

  setChildren(element,
    h("div", { class: "dash-head" },
      h("div", { class: "dash-title" },
        h("h1", { class: "view-title" }, hub.name || "Your atrium"),
        h("span", { class: "view-sub" }, `Atrium · ${hub.os} · running${hub.version ? " · " + hub.version : ""}`)),
      h("span", { class: "dash-state " + (items.length ? "dash-state--bad" : "dash-state--good"), role: "status" },
        items.length ? `${items.length} ${items.length === 1 ? "thing needs" : "things need"} your attention` : "Nothing needs your attention")),
    h("div", { class: "banner", role: "note" },
      h("span", { class: "grow" },
        `This atrium holds the only full copy of your ${hub.notes} ${hub.notes === 1 ? "note" : "notes"}${hub.files ? ` and ${hub.files} attached ${hub.files === 1 ? "file" : "files"}` : ""}. Make sure your backups include the vault folder: `,
        h("code", {}, hub.vault), ".")),
    items.length ? attentionCard(items) : null,
    h("div", { class: "dash-grid" }, maidCard(status), vaultCard(hub)));
}

// ---- what needs attention ----

function attentionCard(items) {
  return h("section", { class: "dash-card dash-card--attention", "aria-labelledby": "attention-title" },
    h("h2", { id: "attention-title" }, "Needs your attention"),
    h("ul", { class: "attention-list" }, items.map((item) => h("li", { class: "attention-item" }, ...itemBody(item)))));
}

function itemBody(item) {
  switch (item.kind) {
    case "missing": {
      const name = baseName(item.paths[0]).replace(/\.[^.]+$/, ""); // “Sets”, not “Sets.md”
      return [
        h("div", { class: "attention-text" },
          h("strong", {}, `“${name}” is missing from your vault`),
          h("p", {}, "Gone from your vault folder: ", item.paths.join(", ")),
          h("p", { class: "hint" }, "If you put the file back, this clears by itself. Until you decide, nothing is deleted.")),
        h("div", { class: "attention-actions" },
          h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: () => trashMissing(item, name) }, "Move to trash…")),
      ];
    }
    case "failed": {
      const name = item.notePath ? `“${baseName(item.notePath).replace(/\.[^.]+$/, "")}”` : "a note";
      return [
        h("div", { class: "attention-text" },
          h("strong", {}, `A change from a device to ${name} could not be finished`),
          h("p", {}, `${fullDate(item.at)}. Reason: ${item.reason}.`),
          h("p", { class: "hint" }, "Nothing was lost. The old text is in the note's saved versions, and the device still has its copy and will send it again.")),
        h("div", { class: "attention-actions" },
          h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: (e) => dismissFailed(item, e.currentTarget) }, "Dismiss")),
      ];
    }
    case "recovery": {
      const name = item.notePath ? `“${baseName(item.notePath).replace(/\.[^.]+$/, "")}”` : "a note";
      return [
        h("div", { class: "attention-text" },
          h("strong", {}, `A version of ${name} is kept for you to review`),
          h("p", {}, recoveryWhy(item)),
          h("p", { class: "hint" }, "The delete stands. This version stays until you restore it or delete it for good; nothing removes it on its own.")),
        h("div", { class: "attention-actions" },
          h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: () => { location.hash = href.trash(); } }, "Review in trash")),
      ];
    }
    case "held": {
      const lines = item.jobs.map((j) => `${j.wouldDelete} of ${j.total} ${j.label} (${Math.round((100 * j.wouldDelete) / Math.max(1, j.total))}%)`);
      return [
        h("div", { class: "attention-text" },
          h("strong", {}, item.jobs.length > 1 ? "The maid held back two cleanups" : "The maid held back a cleanup"),
          h("p", {}, "It would delete ", lines.join(" and "), ". That is more than usual, so it stopped and deleted nothing."),
          h("p", { class: "hint" }, "If you do nothing, nothing is deleted. The maid checks again at its next run.")),
        h("div", { class: "attention-actions" },
          h("button", { class: "sty-btn sty-btn--danger", type: "button", onclick: () => approveHeld(item) }, "Delete them now…")),
      ];
    }
    case "clock":
      return [
        h("div", { class: "attention-text" },
          h("strong", {}, "This computer's clock looks wrong"),
          h("p", {}, `It is ${describeOffset(item.offsetSeconds)} the time server, so the maid deleted nothing, to be safe.`),
          h("p", { class: "hint" }, "Fix the clock, then run the cleanup again. Nothing is deleted while the clock is wrong, even if you approve a held-back cleanup.")),
        h("div", { class: "attention-actions" },
          h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: (e) => runMaid(e.currentTarget) }, "Run the cleanup again")),
      ];
    default: // error
      return [
        h("div", { class: "attention-text" },
          h("strong", {}, item.job === "history" ? "Cleaning up old versions failed" : "Emptying the old trash failed"),
          h("p", {}, item.message)),
        h("div", { class: "attention-actions" },
          h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: (e) => runMaid(e.currentTarget) }, "Try again")),
      ];
  }
}

async function dismissFailed(item, button) {
  button.disabled = true;
  try {
    await api.post(`/api/sync/failed/${encodeURIComponent(item.name)}/dismiss`);
  } catch (err) {
    if (!(err instanceof ApiError && err.code === "not_found")) {
      button.disabled = false;
      showToast(err.message);
      return;
    }
  }
  changed();
  load();
}

function trashMissing(item, name) {
  return openDialog({
    title: `Move “${name}” to the trash everywhere?`,
    description: "Its file is already gone from your vault folder. This takes it off your notes list on every device. You can bring it back from the trash.",
    submitLabel: "Move to trash everywhere",
    onSubmit: async () => {
      try {
        await api.post(`/api/notes/${encodeURIComponent(item.id)}/trash`);
      } catch (err) {
        if (!(err instanceof ApiError && err.code === "not_found")) throw err; // already gone is fine
      }
      showToast(`Moved “${name}” to the trash everywhere.`, { actionLabel: "See trash", onAction: () => { location.hash = href.trash(); } });
      app.refreshFolders();
      document.dispatchEvent(new CustomEvent("trash-changed"));
      changed();
      load();
    },
  });
}

function approveHeld(item) {
  const what = item.jobs.map((j) => j.job === "history"
    ? `${j.wouldDelete} older versions of your notes (the notes themselves are not touched)`
    : `${j.wouldDelete} items from the trash`);
  return openDialog({
    title: "Delete them now?",
    description: `This deletes ${what.join(" and ")} for good. It can't be undone.`,
    submitLabel: "Delete for good",
    danger: true,
    onSubmit: async () => {
      const result = await api.post("/api/maid/run", { force: true });
      if (result.time_check === "wrong") throw new UserError("The clock looks wrong, so nothing was deleted. Fix the clock first.");
      preview = null;
      document.dispatchEvent(new CustomEvent("trash-changed"));
      changed();
      load();
    },
  });
}

// ---- the maid ----

function maidCard(status) {
  const run = status.last_maid_run;
  const busy = status.maid_running;
  const lines = run
    ? [
        h("p", {}, "Last ran ", h("time", { datetime: run.time, title: fullDate(run.time) }, relativeTime(run.time)), "."),
        h("ul", { class: "plain-list" },
          h("li", {}, h("strong", {}, "Old versions: "), describeJob("history", run.history)),
          h("li", {}, h("strong", {}, "Trash: "), describeJob("trash", run.trash))),
        h("p", { class: "hint" }, describeTimeCheck(run)),
      ]
    : [h("p", {}, "It has not run yet. The maid runs about a minute after the atrium starts, then once a day.")];
  const previewBox = h("div", { class: "preview-box", role: "status", "aria-live": "polite" });
  if (preview) setChildren(previewBox, previewText(preview));
  const previewBtn = h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: async (e) => {
    const button = e.currentTarget; // currentTarget is cleared once the handler yields, so keep it
    button.disabled = true;
    try { preview = await api.get("/api/maid/preview"); setChildren(previewBox, previewText(preview)); }
    catch (err) { setChildren(previewBox, err instanceof ApiError ? err.message : "Couldn't check."); }
    button.disabled = false;
  } }, "See what it would delete");
  return h("section", { class: "dash-card", "aria-labelledby": "maid-title" },
    h("h2", { id: "maid-title" }, "Cleanup"),
    h("p", { class: "hint" }, "The maid prunes old versions and empties old trash, following your settings."),
    ...lines,
    previewBox,
    h("div", { class: "card-actions" }, previewBtn,
      h("button", { class: "sty-btn sty-btn--secondary", type: "button", disabled: busy, onclick: (e) => runMaid(e.currentTarget) }, busy ? "Running…" : "Run now")),
    h("p", { class: "hint" }, "Change what it deletes in ", h("a", { href: href.settings() }, "Settings"), "."));
}

function previewText(p) {
  const v = p.history.would_delete;
  const t = p.trash.would_delete;
  if (p.trash.skipped && !v) return "Nothing to delete right now. " + p.trash.skipped.replace(/^./, (c) => c.toUpperCase()) + ".";
  if (!v && !t) return "Nothing to delete right now.";
  return `It would delete ${v} old ${v === 1 ? "version" : "versions"} and ${t} ${t === 1 ? "item" : "items"} from the trash.`;
}

async function runMaid(button) {
  button.disabled = true;
  try {
    await api.post("/api/maid/run", { force: false });
    preview = null;
    document.dispatchEvent(new CustomEvent("trash-changed"));
    changed();
    await load();
  } catch (err) {
    showToast(err instanceof ApiError ? err.message : "Couldn't run the cleanup.");
    button.disabled = false;
  }
}

// ---- the vault ----

function vaultCard(hub) {
  return h("section", { class: "dash-card", "aria-labelledby": "vault-title" },
    h("h2", { id: "vault-title" }, "Your vault"),
    h("dl", { class: "facts" },
      h("dt", {}, "Notes"), h("dd", {}, String(hub.notes)),
      h("dt", {}, "Attached files"), h("dd", {}, String(hub.files)),
      h("dt", {}, "Vault folder"), h("dd", {}, h("code", {}, hub.vault)),
      h("dt", {}, "System"), h("dd", {}, hub.os),
      hub.version ? [h("dt", {}, "Version"), h("dd", {}, hub.version)] : null));
}

function errorBanner(err, retry) {
  return h("div", { class: "banner banner--danger", role: "alert" },
    h("span", { class: "grow" }, err instanceof ApiError ? err.message : "Something went wrong."),
    h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: retry }, "Try again"));
}
