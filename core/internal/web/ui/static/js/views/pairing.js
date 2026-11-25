// The "Devices" card on the Settings screen: pair a new device with four magic
// words, then check the emoji match on both screens. The device side runs
// `styli join`. Nothing is saved until the person approves on both devices.
import { api } from "../api.js";
import { h, setChildren } from "../dom.js";
import { describePairing } from "../pairingtext.js";
import { openDialog } from "../dialogs.js";
import { relativeTime } from "../time.js";
import { showToast } from "../toast.js";

const POLL_MS = 1000;

export function pairingCard() {
  const body = h("div", { class: "pair-body", "aria-live": "polite" });
  const card = h("section", { class: "dash-card settings-card", "aria-labelledby": "pair-title" },
    h("h2", { id: "pair-title" }, "Devices"),
    h("p", { class: "hint" }, "Pair a new device so it can sync with this atrium. You need the device next to you."),
    body);
  const list = h("div", { class: "device-list" });
  card.insertBefore(list, body);
  let timer = null;
  let busy = false;

  async function call(method, path) {
    if (busy) return;
    busy = true;
    try {
      draw(method === "GET" ? await api.get(path) : await api.post(path));
    } catch (err) {
      draw(null, err.message);
    } finally {
      busy = false;
    }
  }

  function poll() {
    clearInterval(timer);
    timer = setInterval(() => {
      if (!card.isConnected) return clearInterval(timer); // the person left the screen
      call("GET", "/api/pairing");
    }, POLL_MS);
  }

  // ---- the devices already paired ----

  function drawDevices(devices) {
    if (!devices.length) return setChildren(list);
    setChildren(list, h("ul", { class: "device-rows", "aria-label": "Paired devices" }, devices.map((d) => {
      const revoked = !!d.revoked_at;
      const name = d.name || "Unnamed device";
      return h("li", { class: "device-row" + (revoked ? " is-revoked" : "") },
        h("div", { class: "grow" },
          h("strong", {}, name), " ", h("code", { title: d.id }, d.id.slice(0, 8)),
          h("div", { class: "hint" }, revoked
            ? `Revoked ${relativeTime(d.revoked_at)}. It can't sync.`
            : `Paired ${relativeTime(d.paired_at)}. ${d.last_seen ? `Last seen ${relativeTime(d.last_seen)}.` : "Not seen yet."}`)),
        revoked
          ? button("Restore access", "secondary", () => restore(d, name))
          : button("Revoke", "danger", () => revoke(d, name)));
    })));
  }

  async function loadDevices() {
    try {
      drawDevices((await api.get("/api/devices")).devices);
    } catch {
      setChildren(list); // sync is off, or the atrium is unreachable: the pairing part says so
    }
  }

  async function change(d, verb, done) {
    const out = await api.post(`/api/devices/${d.id}/${verb}`);
    drawDevices(out.devices);
    showToast(done);
  }

  const revoke = (d, name) => openDialog({
    title: `Revoke “${name}”?`,
    description: "It stops syncing at once and can't sync with this atrium again unless you restore it here or pair it as a new device. Nothing is deleted: its notes stay on it and here.",
    submitLabel: "Revoke", danger: true,
    onSubmit: () => change(d, "revoke", `Revoked “${name}”.`),
  });

  const restore = (d, name) => openDialog({
    title: `Let “${name}” sync again?`,
    description: "It can sync with this atrium again straight away. Its changes from while it was cut off are merged like any other offline edits. If you revoked it because it was lost or stolen, don't restore it.",
    submitLabel: "Restore access",
    onSubmit: () => change(d, "unrevoke", `“${name}” can sync again.`),
  });

  function button(label, kind, onclick) {
    return h("button", { class: `sty-btn sty-btn--${kind}`, type: "button", onclick }, label);
  }

  function draw(st, error) {
    if (error) {
      clearInterval(timer);
      setChildren(body, h("p", { class: "field-error", role: "alert" }, error),
        button("Try again", "secondary", () => call("GET", "/api/pairing")));
      return;
    }
    const text = describePairing(st);
    const start = () => call("POST", "/api/pairing");
    const cancel = () => call("POST", "/api/pairing/cancel");
    switch (st.phase) {
      case "waiting":
        poll();
        setChildren(body,
          h("p", {}, "On the new device, run ", h("code", {}, "styli join"), " and type these words:"),
          h("p", { class: "pair-words" }, st.words.replaceAll("-", " ")),
          h("p", { class: "hint" }, text.detail),
          h("div", { class: "banner-actions" }, button("Cancel", "ghost", cancel)));
        return;
      case "checking":
        poll();
        setChildren(body, h("p", {}, text.headline), h("div", { class: "banner-actions" }, button("Cancel", "ghost", cancel)));
        return;
      case "comparing":
        poll();
        setChildren(body,
          h("p", {}, h("strong", {}, text.headline)),
          h("p", {}, "Check that the device shows these six symbols, in this order:"),
          h("ol", { class: "pair-emoji", "aria-label": st.emoji.map((e) => e.name).join(", ") },
            st.emoji.map((e) => h("li", {}, h("span", { class: "pair-emoji-char", "aria-hidden": "true" }, e.char), h("span", { class: "pair-emoji-name" }, e.name)))),
          h("p", { class: "hint" }, text.detail),
          h("div", { class: "banner-actions" },
            st.atrium_approved ? null : button("They match", "secondary", () => call("POST", "/api/pairing/approve")),
            button("They don't match", "danger", cancel)));
        return;
      default: // idle, or a pairing that has ended
        clearInterval(timer);
        loadDevices();
        setChildren(body,
          text.headline ? h("p", { class: st.phase === "paired" ? "" : "field-error", role: st.phase === "paired" ? "status" : "alert" }, text.headline) : null,
          text.detail ? h("p", { class: "hint" }, text.detail) : null,
          h("div", { class: "banner-actions" }, button(st.phase === "idle" ? "Pair new device" : "Pair another device", "secondary", start)));
    }
  }

  loadDevices();
  call("GET", "/api/pairing");
  return card;
}
