// The "Browsers" card on the Settings screen: the browsers that can open this
// atrium's web UI, and adding one with a one-time link (a QR code and the
// link itself). Only the atrium has it; on a device the card removes itself.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { openDialog } from "../dialogs.js";
import { relativeTime } from "../time.js";
import { showToast } from "../toast.js";

const loopback = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);

export function browsersCard() {
  const list = h("div", { class: "device-list" });
  const body = h("div", { "aria-live": "polite" });
  const card = h("section", { class: "dash-card settings-card", "aria-labelledby": "browsers-title", hidden: true },
    h("h2", { id: "browsers-title" }, "Browsers"),
    h("p", { class: "hint" }, "Browsers that can open this atrium, such as Safari on your iPhone. Add one with a link that works once."),
    list, body);
  load();
  return card;

  async function load() {
    try {
      draw((await api.get("/api/browsers")).browsers);
      card.hidden = false;
    } catch (err) {
      if (err.code === "not_atrium") return card.remove();
      card.hidden = false;
      setChildren(body, h("p", { class: "field-error", role: "alert" }, err instanceof ApiError ? err.message : "Something went wrong."),
        h("div", { class: "banner-actions" }, button("Try again", "secondary", load)));
    }
  }

  function draw(browsers) {
    setChildren(list, h("ul", { class: "device-rows", "aria-label": "Signed-in browsers" }, browsers.map((b) =>
      h("li", { class: "device-row" },
        h("div", { class: "grow" },
          h("strong", {}, b.name), b.this ? [" ", h("span", { class: "sty-chip" }, "This browser")] : null,
          h("div", { class: "hint" }, `Added ${relativeTime(b.added_at)}. Last used ${relativeTime(b.last_seen)}.`)),
        b.this ? button("Sign out", "secondary", () => signOut(b)) : button("Remove", "danger", () => remove(b))))));
    setChildren(body, h("div", { class: "banner-actions" }, button("Add a browser", "secondary", addBrowser)));
  }

  async function addBrowser() {
    let link;
    try {
      link = await api.post("/api/browsers/links");
    } catch (err) {
      setChildren(body, h("p", { class: "field-error", role: "alert" }, err instanceof ApiError ? err.message : "Something went wrong."),
        h("div", { class: "banner-actions" }, button("Try again", "secondary", addBrowser)));
      return;
    }
    const until = new Date(link.expires_at).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    const url = h("input", { class: "sty-input", id: "join-url", type: "text", value: link.url, readonly: true, onfocus: (e) => e.target.select() });
    const here = loopback.has(new URL(link.url).hostname);
    setChildren(body, h("div", { class: "join-link" },
      h("img", { class: "join-qr", src: "data:image/svg+xml;charset=utf-8," + encodeURIComponent(link.qr_svg), alt: "QR code of the sign-in link", width: "200", height: "200" }),
      h("div", { class: "grow" },
        h("p", {}, "Scan the code with the other device's camera, or open the link in the browser you're adding."),
        h("label", { class: "sty-field", for: "join-url" }, h("span", {}, "Sign-in link"), url),
        h("p", { class: "hint" }, `It works once, until ${until}. Anyone who has it can sign in, so only open it on your own device.`),
        here ? h("p", { class: "hint" }, "For now this atrium can only be reached from this computer, so the link only works in a browser here.") : null,
        h("div", { class: "banner-actions" },
          button("Copy link", "secondary", () => copy(link.url, url)),
          button("Done", "ghost", load)))));
  }

  async function copy(text, input) {
    try {
      await navigator.clipboard.writeText(text);
      showToast("Link copied.");
    } catch {
      input.focus(); // selects it, for copying by hand
      showToast("Select the link and copy it.");
    }
  }

  function remove(b) {
    return openDialog({
      title: `Remove “${b.name}”?`,
      description: "It is signed out at once and can't open this atrium again unless you add it with a new link. Nothing is deleted.",
      submitLabel: "Remove", danger: true,
      onSubmit: async () => {
        draw((await api.post(`/api/browsers/${encodeURIComponent(b.id)}/remove`)).browsers);
        showToast(`Removed “${b.name}”.`);
      },
    });
  }

  function signOut(b) {
    return openDialog({
      title: "Sign out this browser?",
      description: "To sign in again you'll need a new link from another signed-in browser. If this is the only one, start the atrium with --new-browser-link.",
      submitLabel: "Sign out", danger: true,
      onSubmit: async () => {
        await api.post(`/api/browsers/${encodeURIComponent(b.id)}/remove`);
        location.reload(); // shows the signed-out screen
      },
    });
  }
}

function button(label, kind, onclick) {
  return h("button", { class: `sty-btn sty-btn--${kind}`, type: "button", onclick }, label);
}
