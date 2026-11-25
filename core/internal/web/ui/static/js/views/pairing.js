// The "Devices" card on the Settings screen: pair a new device with four magic
// words, then check the emoji match on both screens. The device side runs
// `styli join`. Nothing is saved until the person approves on both devices.
import { api } from "../api.js";
import { h, setChildren } from "../dom.js";
import { describePairing } from "../pairingtext.js";

const POLL_MS = 1000;

export function pairingCard() {
  const body = h("div", { class: "pair-body", "aria-live": "polite" });
  const card = h("section", { class: "dash-card settings-card", "aria-labelledby": "pair-title" },
    h("h2", { id: "pair-title" }, "Devices"),
    h("p", { class: "hint" }, "Pair a new device so it can sync with this atrium. You need the device next to you."),
    body);
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
        setChildren(body,
          text.headline ? h("p", { class: st.phase === "paired" ? "" : "field-error", role: st.phase === "paired" ? "status" : "alert" }, text.headline) : null,
          text.detail ? h("p", { class: "hint" }, text.detail) : null,
          h("div", { class: "banner-actions" }, button(st.phase === "idle" ? "Pair new device" : "Pair another device", "secondary", start)));
    }
  }

  call("GET", "/api/pairing");
  return card;
}
