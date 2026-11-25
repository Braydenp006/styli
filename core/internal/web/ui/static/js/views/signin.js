// Signing a browser in to the atrium: the screen a one-time link opens, and
// the screen a browser that isn't signed in sees. Both stand alone, without
// the sidebar: nothing else works until the browser is signed in.
import { api, ApiError } from "../api.js";
import { h, setChildren } from "../dom.js";
import { guessBrowserName } from "../browsername.js";

function frame(root, ...children) {
  document.title = "Sign in · Styli";
  setChildren(root, h("main", { id: "main", class: "signin", tabindex: "-1" },
    h("div", { class: "signin-card dash-card" },
      h("div", { class: "signin-brand", "aria-hidden": "true" },
        h("picture", {},
          h("source", { srcset: "/static/img/styli-mark-dark-bg.svg", media: "(prefers-color-scheme: dark)" }),
          h("img", { src: "/static/img/styli-mark-light-bg.svg", alt: "", width: "32", height: "32" })),
        h("span", {}, "STYLI")),
      ...children)));
}

const howToSignIn = () => [
  h("p", {}, "To sign in, use a browser that is signed in: open Settings, then Browsers, and choose Add a browser. Scan the code with this device, or open the link here."),
  h("p", { class: "hint" }, "On the atrium's own computer, a link is printed when the atrium starts and no browser is signed in. If every signed-in browser is lost, start the atrium with ", h("code", {}, "--new-browser-link"), "."),
];

// showSignedOut is for a browser without a session.
export function showSignedOut(root) {
  frame(root,
    h("h1", { class: "signin-title" }, "This browser isn't signed in"),
    ...howToSignIn());
}

// showJoin is what a one-time link opens. The link's secret is taken out of
// the address at once, so it is not left in the history or on screen.
export function showJoin(root, token) {
  history.replaceState(null, "", "/#/join");
  if (!token) {
    frame(root,
      h("h1", { class: "signin-title" }, "This link was already opened"),
      h("p", {}, "A sign-in link works once, and this page has already read it. If you didn't finish signing in, make a new link."),
      ...howToSignIn());
    return;
  }
  const nameId = "signin-name";
  const errorId = "signin-error";
  const name = h("input", { class: "sty-input", id: nameId, type: "text", value: guessBrowserName(navigator.userAgent),
    maxlength: "60", autocomplete: "off", required: true, "aria-describedby": `${nameId}-help ${errorId}` });
  const error = h("p", { class: "field-error", id: errorId, role: "alert", hidden: true });
  const submit = h("button", { class: "sty-btn sty-btn--primary", type: "submit" }, "Sign in");
  const form = h("form", { class: "signin-form", novalidate: true, onsubmit: async (e) => {
    e.preventDefault();
    error.hidden = true;
    name.removeAttribute("aria-invalid");
    submit.disabled = true;
    try {
      await api.post("/api/join", { token, name: name.value });
      history.replaceState(null, "", "/#/");
      location.reload();
    } catch (err) {
      submit.disabled = false;
      const message = err instanceof ApiError ? err.message : "Something went wrong.";
      if (err.code === "invalid") {
        name.setAttribute("aria-invalid", "true");
        name.focus();
      }
      if (err.code === "bad_link") {
        // The link is used up or expired: nothing on this screen can help now.
        frame(root, h("h1", { class: "signin-title" }, "This link doesn't work any more"), h("p", { role: "alert" }, message), ...howToSignIn());
        return;
      }
      error.textContent = message;
      error.hidden = false;
    }
  } },
  h("label", { class: "sty-field", for: nameId },
    h("span", {}, "Name this browser"),
    name,
    h("span", { class: "sty-help", id: `${nameId}-help` }, "So you can tell it apart from your other browsers in Settings.")),
  error,
  h("div", { class: "banner-actions" }, submit));
  frame(root, h("h1", { class: "signin-title" }, "Sign in to your atrium"), form);
  name.focus();
}
