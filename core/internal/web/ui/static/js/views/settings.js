// The settings screen. One form with one Save bar: several of these settings
// decide what gets deleted, so nothing takes effect while you are typing.
// Saving never deletes anything itself; afterwards the screen shows what the
// next cleanup would delete under the new settings.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { href } from "../router.js";
import { openDialog } from "../dialogs.js";
import { showToast } from "../toast.js";
import { toForm, toSettings, sameForm, NAME_MAX } from "../settingsform.js";

let saved = null; // the form values as the hub has them
let form = null; // the form values as they are on screen
let fields = {}; // form key -> its input
let seq = 0;
let nextId = 0;

export const element = h("form", { class: "settings-view", novalidate: true, autocomplete: "off" });
element.addEventListener("submit", (e) => { e.preventDefault(); save(); });

const dirty = () => !!form && !sameForm(form, saved);

// Warn before the tab closes with unsaved changes.
window.addEventListener("beforeunload", (e) => {
  if (element.isConnected && dirty()) { e.preventDefault(); e.returnValue = ""; }
});

// Called by the app before it leaves this screen.
export async function canLeave(nextRoute) {
  if (!dirty() || !element.isConnected || nextRoute?.name === "settings") return true;
  return openDialog({
    title: "Leave without saving?",
    description: "You have changes to your settings that haven't been saved.",
    submitLabel: "Leave without saving",
    onSubmit: async () => {},
  });
}

export async function show() {
  const mine = ++seq;
  document.title = "Settings — Styli";
  setChildren(element, h("h1", { class: "view-title" }, "Settings"), h("p", { class: "view-sub", role: "status" }, "Loading…"));
  try {
    const s = await api.get("/api/settings");
    if (mine !== seq) return;
    saved = toForm(s);
    form = { ...saved };
    draw();
  } catch (err) {
    if (mine !== seq) return;
    saved = form = null;
    setChildren(element, h("h1", { class: "view-title" }, "Settings"), errorBanner(err, show));
  }
}

// ---- building the form ----

function id() { return `setting-${++nextId}`; }

function field({ key, label, help, type = "text", width = "", suffix }) {
  const inputId = id();
  const helpId = id();
  const input = h("input", {
    class: "sty-input" + (width ? " " + width : ""), id: inputId, type: "text", value: form[key], autocomplete: "off", spellcheck: "false",
    inputmode: type === "number" ? "numeric" : false, "aria-describedby": help ? helpId : false, maxlength: type === "number" ? "7" : key.endsWith("Name") ? String(NAME_MAX * 2) : "255",
  });
  input.addEventListener("input", () => { form[key] = input.value; touched(); });
  fields[key] = input;
  const error = h("span", { class: "field-error", id: id(), role: "alert", hidden: true });
  input.dataset.errorId = error.id;
  return h("div", { class: "sty-field setting" },
    h("label", { for: inputId }, label),
    suffix ? h("div", { class: "input-row" }, input, h("span", { class: "input-suffix" }, suffix)) : input,
    help ? h("span", { class: "sty-help", id: helpId }, help) : null,
    error);
}

function toggle({ key, label, help }) {
  const inputId = id();
  const helpId = id();
  const input = h("input", { type: "checkbox", id: inputId, checked: form[key], "aria-describedby": help ? helpId : false });
  input.addEventListener("change", () => { form[key] = input.checked; touched(); });
  fields[key] = input;
  return h("div", { class: "setting" },
    h("label", { class: "sty-toggle", for: inputId }, input, h("span", {}), label),
    help ? h("span", { class: "sty-help", id: helpId }, help) : null);
}

function select({ key, label, options, onChange }) {
  const inputId = id();
  const input = h("select", { class: "sty-input", id: inputId }, options.map(([value, text]) => h("option", { value, selected: form[key] === value }, text)));
  input.addEventListener("change", () => { form[key] = input.value; onChange?.(); touched(); });
  fields[key] = input;
  return h("div", { class: "sty-field setting" }, h("label", { for: inputId }, label), input);
}

function card(title, intro, ...children) {
  const titleId = id();
  return h("section", { class: "dash-card settings-card", "aria-labelledby": titleId },
    h("h2", { id: titleId }, title), intro ? h("p", { class: "hint" }, intro) : null, ...children);
}

let bar;
let notice;
let errorSlot;
let deviceDaysField;
let hubOption; // the "use the hub's setting" choice, whose label shows the hub's number

const hubTrashText = () => (Number(saved.trashDays) === 0 ? "until deleted by hand" : `${saved.trashDays} days`);

function draw() {
  fields = {};
  const deviceDays = field({ key: "deviceDays", label: "Days to keep items on this device", type: "number", width: "narrow", suffix: "days" });
  deviceDaysField = deviceDays;
  const deviceMode = select({
    key: "deviceMode", label: "Trash on this device",
    options: [["hub", ""], ["days", "Keep for a number of days"], ["forever", "Keep until I delete them"]],
    onChange: syncDeviceDays,
  });
  hubOption = fields.deviceMode.querySelector("option[value=hub]");

  errorSlot = h("div", { class: "slot" });
  notice = h("div", { class: "slot", role: "status", "aria-live": "polite" });
  bar = h("div", { class: "save-bar", role: "region", "aria-label": "Unsaved changes", hidden: true },
    h("span", {}, "You have unsaved changes."),
    h("div", { class: "save-bar-actions" },
      h("button", { class: "sty-btn sty-btn--ghost", type: "button", onclick: discard }, "Discard"),
      h("button", { class: "sty-btn sty-btn--primary", type: "submit" }, "Save changes")));

  setChildren(element,
    h("h1", { class: "view-title" }, "Settings"),
    h("p", { class: "view-sub" }, "These apply to your whole hub, except the last section, which is only for this device."),
    errorSlot, notice,

    card("This hub", null,
      field({ key: "hubName", label: "Hub name", help: "Shown at the top of the hub dashboard." })),

    card("Version history", "Older versions of a note let you look back and restore. Each note can still turn history off for itself.",
      toggle({ key: "historyOn", label: "Keep older versions of notes" }),
      field({ key: "windowMinutes", label: "Combine saves made within", type: "number", width: "narrow", suffix: "minutes",
        help: "Edits made within this time are saved as one version. 0 keeps every save as its own version." }),
      h("h3", {}, "Limits on old versions"),
      h("p", { class: "hint" }, "Leave a limit empty, or at 0, to switch it off. Saving deletes nothing: the maid applies these limits when it next runs, and asks you first if it would delete a lot. A note's newest version is never deleted."),
      field({ key: "maxVersions", label: "Keep at most this many versions of each note", type: "number", width: "narrow" }),
      field({ key: "maxAgeDays", label: "Delete versions older than", type: "number", width: "narrow", suffix: "days" }),
      h("div", { class: "field-row" },
        field({ key: "perPeriod", label: "Keep at most this many versions for each finished", type: "number", width: "narrow" }),
        select({ key: "period", label: "Period", options: [["day", "day"], ["week", "week"], ["month", "month"]] })),
      h("p", { class: "sty-help" }, "For example, 1 per day: today's versions all stay, and from tomorrow only the last version of each day is kept.")),

    card("Trash", null,
      field({ key: "trashDays", label: "Keep items in the trash for", type: "number", width: "narrow", suffix: "days",
        help: "The maid deletes what is older than this. 0 keeps everything until you delete it yourself." })),

    card("Time check", "Before the maid deletes anything, it can ask a time server whether this computer's clock is right, and delete nothing if it isn't. A wrong clock could otherwise make it delete the wrong things.",
      toggle({ key: "timeOn", label: "Check the clock before deleting anything",
        help: "This contacts the server below. It sends none of your notes: it only tells that server this address asked for the time." }),
      field({ key: "timeServer", label: "Time server", help: "A name or address, like pool.ntp.org. Use your router's address to keep the check on your own network." })),

    card("This device", null,
      field({ key: "deviceName", label: "Device name", help: "How this device is shown in the hub." }),
      deviceMode, deviceDays),
    bar);
  syncDeviceDays();
}

function syncDeviceDays() {
  deviceDaysField.hidden = form.deviceMode !== "days";
  hubOption.textContent = `Use the hub's setting (${hubTrashText()})`;
}

// ---- changing and saving ----

function touched() {
  bar.hidden = !dirty();
  setChildren(notice); // a "Saved" message is about the last save, not this edit
  for (const input of Object.values(fields)) clearError(input);
}

function discard() {
  form = { ...saved };
  setValues();
  touched();
}

function setValues() {
  for (const [key, input] of Object.entries(fields)) {
    if (input.type === "checkbox") input.checked = form[key];
    else input.value = form[key];
  }
  syncDeviceDays();
}

function clearError(input) {
  const slot = input.dataset?.errorId && document.getElementById(input.dataset.errorId);
  if (slot) slot.hidden = true;
  input.removeAttribute("aria-invalid");
}

function showErrors(errors) {
  let first = null;
  for (const [key, message] of Object.entries(errors)) {
    const input = fields[key];
    const slot = document.getElementById(input.dataset.errorId);
    slot.textContent = message;
    slot.hidden = false;
    input.setAttribute("aria-invalid", "true");
    first ||= input;
  }
  first?.focus();
}

async function save() {
  setChildren(errorSlot);
  setChildren(notice);
  const out = toSettings(form);
  if (out.errors) {
    // A hidden "days" box that is not in use must not block saving; toSettings already ignores it.
    return showErrors(out.errors);
  }
  const buttons = bar.querySelectorAll("button");
  buttons.forEach((b) => (b.disabled = true));
  try {
    saved = toForm(await api.put("/api/settings/hub", out.hub)); // the hub part is saved first, and shown as saved even if the device part fails
    bar.hidden = !dirty();
    saved = toForm(await api.put("/api/settings/device", out.device));
  } catch (err) {
    setChildren(errorSlot, errorBanner(err, save, "Your changes haven't been fully saved."));
    buttons.forEach((b) => (b.disabled = false));
    return;
  }
  form = { ...saved };
  setValues();
  bar.hidden = true;
  buttons.forEach((b) => (b.disabled = false));
  showToast("Settings saved.");
  await showPreview();
}

// What the next cleanup would delete under the settings just saved.
async function showPreview() {
  try {
    const p = await api.get("/api/maid/preview");
    const v = p.history.would_delete;
    const t = p.trash.would_delete;
    setChildren(notice, h("div", { class: "banner" },
      h("span", { class: "grow" },
        v + t === 0
          ? "Saved. With these settings, the next cleanup would delete nothing."
          : `Saved. With these settings, the next cleanup would delete ${v} old ${v === 1 ? "version" : "versions"} and ${t} ${t === 1 ? "item" : "items"} from the trash. Nothing is deleted until it runs, and it asks you first if that is a lot.`),
      h("a", { href: href.dashboard() }, "See the dashboard")));
  } catch {
    setChildren(notice, h("div", { class: "banner" }, "Saved."));
  }
}

function errorBanner(err, retry, lead) {
  return h("div", { class: "banner banner--danger", role: "alert" },
    h("span", { class: "grow" }, lead ? `${lead} ` : "", err instanceof ApiError ? err.message : "Something went wrong."),
    h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: retry }, "Try again"));
}
