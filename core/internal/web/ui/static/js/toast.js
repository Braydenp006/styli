import { h } from "./dom.js";

let region = null;

// A short message at the bottom of the screen, optionally with one action
// (for example Undo). Screen readers hear it through the live region.
export function showToast(message, { actionLabel, onAction, ms = 8000 } = {}) {
  if (!region) {
    region = h("div", { class: "toast-region", role: "status", "aria-live": "polite" });
    document.body.append(region);
  }
  const toast = h("div", { class: "toast" }, h("span", {}, message));
  const remove = () => toast.remove();
  if (actionLabel) {
    toast.append(h("button", { class: "sty-btn sty-btn--ghost", type: "button", onclick: () => { remove(); onAction?.(); } }, actionLabel));
  }
  region.append(toast);
  setTimeout(remove, ms);
}
