// The "Browsers" card on the Settings screen: the browsers that can open this
// atrium's web UI, and adding one with a one-time link (a QR code and the
// link itself). Only the atrium has it; on a device the card removes itself.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { openDialog, UserError } from "../dialogs.js";
import { relativeTime } from "../time.js";
import { showToast } from "../toast.js";
import { accountField } from "./signin.js";

const loopback = new Set(["127.0.0.1", "localhost", "[::1]", "::1"]);

export function browsersCard() {
  const list = h("div", { class: "device-list" });
  const body = h("div", { "aria-live": "polite" });
  const pw = h("div");
  let passwordSet = false;
  const card = h("section", { class: "dash-card settings-card", "aria-labelledby": "browsers-title", hidden: true },
    h("h2", { id: "browsers-title" }, "Browsers"),
    h("p", { class: "hint" }, "Browsers that can open this atrium, such as Safari on your iPhone. Add one with a link that works once."),
    list, body, pw);
  load();
  return card;

  async function load() {
    try {
      const [{ browsers }, session] = await Promise.all([api.get("/api/browsers"), api.get("/api/session")]);
      passwordSet = session.password_set;
      draw(browsers);
      drawPassword();
      card.hidden = false;
    } catch (err) {
      if (err.code === "not_atrium") return card.remove();
      card.hidden = false;
      setChildren(body, h("p", { class: "field-error", role: "alert" }, err instanceof ApiError ? err.message : "Something went wrong."),
        h("div", { class: "banner-actions" }, button("Try again", "secondary", load)));
    }
  }

  function draw(browsers) {
    setChildren(list, h("ul", { class: "device-rows", "aria-label": "Trusted browsers" }, browsers.map((b) =>
      h("li", { class: "device-row" },
        h("div", { class: "grow" },
          h("strong", {}, b.name), b.this ? [" ", h("span", { class: "sty-chip" }, "This browser")] : null,
          h("div", { class: "hint" }, `Added ${relativeTime(b.added_at)}. Last used ${relativeTime(b.last_seen)}.`)),
        b.this ? button("Sign out", "secondary", signOut) : button("Remove", "danger", () => remove(b))))));
    setChildren(body, h("div", { class: "banner-actions" }, button("Add a browser", "secondary", addBrowser)));
  }

  function drawPassword() {
    setChildren(pw,
      h("h3", {}, "Password"),
      h("p", { class: "hint" }, passwordSet
        ? "A browser in this list can sign back in with the password after it signs out or goes 30 days unused. A password never adds a new browser: that still takes a link."
        : "Set a password so a browser in this list can sign back in after it signs out or goes 30 days unused, without a new link. A password never adds a new browser: that still takes a link."),
      h("div", { class: "banner-actions" }, passwordSet
        ? [button("Change password", "secondary", () => setPassword(true)), button("Remove password", "ghost", removePassword)]
        : button("Set a password", "secondary", () => setPassword(false))));
  }

  function setPassword(change) {
    const fields = [
      accountField(),
      change ? passwordField("Current password", "current", "current-password") : null,
      passwordField(change ? "New password" : "Password", "new", "new-password", "At least 8 characters."),
      passwordField("Type it again", "again", "new-password"),
    ].filter(Boolean);
    return openDialog({
      title: change ? "Change the atrium's password" : "Set a password",
      description: change ? "Every browser in the list uses the new password from now on. None is signed out." : "One password for this atrium, for every browser in the list.",
      fields, submitLabel: change ? "Change password" : "Set password",
      onSubmit: async (data) => {
        if (data.get("new") !== data.get("again")) throw new UserError("The two passwords don't match.");
        await api.post("/api/password", { current: data.get("current") || "", new: data.get("new") });
        passwordSet = true;
        drawPassword();
        showToast(change ? "Password changed." : "Password set.");
      },
    });
  }

  function removePassword() {
    return openDialog({
      title: "Remove the password?",
      description: "A browser that signs out, or goes 30 days unused, will then need a new link to sign back in.",
      fields: [accountField(), passwordField("Current password", "current", "current-password")],
      submitLabel: "Remove password", danger: true,
      onSubmit: async (data) => {
        await api.post("/api/password", { current: data.get("current"), new: "" });
        passwordSet = false;
        drawPassword();
        showToast("Password removed.");
      },
    });
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
    const signIn = h("div", { class: "join-link" },
      qr(link.qr_svg, "QR code of the sign-in link"),
      h("div", { class: "grow" },
        h("p", {}, "Scan the code with the other device's camera, or open the link in the browser you're adding."),
        h("label", { class: "sty-field", for: "join-url" }, h("span", {}, "Sign-in link"), url),
        h("p", { class: "hint" }, `It works once, until ${until}. Anyone who has it can sign in, so only open it on your own device.`),
        here ? h("p", { class: "hint" }, "For now this atrium can only be reached from this computer, so the link only works in a browser here.") : null,
        h("div", { class: "banner-actions" },
          button("Copy link", "secondary", () => copy(link.url, url)),
          button("Done", "ghost", load))));
    if (!link.ca) return setChildren(body, signIn);
    // The atrium is its own certificate authority: the other device installs
    // its certificate first (once), then signs in.
    setChildren(body,
      h("h3", {}, "1. Install this atrium's certificate"),
      h("div", { class: "join-link" },
        qr(link.ca.qr_svg, "QR code of the certificate download"),
        h("div", { class: "grow" },
          h("p", {}, "Do this once on each phone or laptop, so it trusts this atrium. Scan the code, or open ", h("code", {}, link.ca.url), ". Until the certificate is installed the browser warns that the connection isn't private; that is expected here, so continue to the page."),
          h("p", {}, "Before you trust the certificate, check that its SHA-256 fingerprint is exactly this:"),
          h("p", { class: "fingerprint" }, link.ca.fingerprint),
          h("details", { class: "how-to" },
            h("summary", {}, "How to install it"),
            h("p", {}, h("strong", {}, "iPhone or iPad: "), "allow the download, then open Settings, General, VPN & Device Management, choose the Styli atrium profile and install it (its fingerprint is under More Details). Then open Settings, General, About, Certificate Trust Settings, and turn on full trust for it."),
            h("p", {}, h("strong", {}, "Android: "), "open Settings, search for “CA certificate”, choose Install, and pick the downloaded file."),
            h("p", {}, h("strong", {}, "Mac: "), "open the downloaded file, then in Keychain Access set the certificate to Always Trust. ", h("strong", {}, "Windows: "), "open the file and install it in Trusted Root Certification Authorities. ", h("strong", {}, "Linux: "), "add it to the system's or the browser's trusted certificates.")))),
      h("h3", {}, "2. Sign in"),
      signIn);
  }

  function qr(svg, alt) {
    return h("img", { class: "join-qr", src: "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg), alt, width: "200", height: "200" });
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

  function signOut() {
    return openDialog({
      title: "Sign out this browser?",
      description: passwordSet
        ? "You can sign back in here with the atrium's password."
        : "To sign in again you'll need a new link from another signed-in browser. If this is the only one, start the atrium with --new-browser-link.",
      submitLabel: "Sign out", danger: !passwordSet,
      onSubmit: async () => {
        await api.post("/api/signout");
        location.reload(); // shows the signed-out screen
      },
    });
  }
}

let nextField = 0;

function passwordField(label, name, autocomplete, help) {
  const id = `browsers-pw-${++nextField}`;
  return h("label", { class: "sty-field", for: id },
    label,
    h("input", { class: "sty-input", id, name, type: "password", autocomplete, required: true }),
    help ? h("span", { class: "sty-help" }, help) : null);
}

function button(label, kind, onclick) {
  return h("button", { class: `sty-btn sty-btn--${kind}`, type: "button", onclick }, label);
}
