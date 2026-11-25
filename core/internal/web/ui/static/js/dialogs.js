import { ApiError } from "./api.js";
import { h } from "./dom.js";

// An error whose message is meant for the person using the app.
export class UserError extends Error {}

let nextId = 0;

// A labelled text input with optional help and a list of suggestions.
export function textField({ label, name, value = "", help, suggestions, required = false, placeholder }) {
  const id = `field-${++nextId}`;
  const listId = suggestions ? `${id}-list` : null;
  const input = h("input", { class: "sty-input", id, name, value, type: "text", autocomplete: "off", spellcheck: "false", required, placeholder, list: listId, maxlength: "200" });
  return h("label", { class: "sty-field", for: id },
    label,
    input,
    suggestions ? h("datalist", { id: listId }, suggestions.map((s) => h("option", { value: s }))) : null,
    help ? h("span", { class: "sty-help" }, help) : null);
}

// A modal dialog (the browser's own <dialog>, so focus is kept inside it and
// Esc closes it). onSubmit(formData) runs when the form is submitted; if it
// throws, the message is shown in the dialog and it stays open. Resolves to
// true if the action went through, false if it was cancelled.
export function openDialog({ title, description, fields = [], submitLabel, danger = false, onSubmit }) {
  return new Promise((resolve) => {
    const titleId = `dialog-title-${++nextId}`;
    const error = h("p", { class: "dialog-error", role: "alert", hidden: true });
    const submit = h("button", { class: "sty-btn " + (danger ? "sty-btn--danger" : "sty-btn--primary"), type: "submit" }, submitLabel);
    const cancel = h("button", { class: "sty-btn sty-btn--ghost", type: "button", onclick: () => dlg.close() }, "Cancel");
    const form = h("form", { class: "dialog-form", method: "dialog" },
      h("h2", { id: titleId }, title),
      description ? h("p", { class: "dialog-text" }, description) : null,
      ...fields, error,
      h("div", { class: "dialog-actions" }, cancel, submit));
    const dlg = h("dialog", { class: "dialog", "aria-labelledby": titleId }, form);
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      submit.disabled = cancel.disabled = true;
      error.hidden = true;
      try {
        await onSubmit(new FormData(form));
        dlg.close("ok");
        return;
      } catch (err) {
        error.textContent = err instanceof ApiError || err instanceof UserError ? err.message : "Something went wrong.";
        error.hidden = false;
      }
      submit.disabled = cancel.disabled = false;
    });
    dlg.addEventListener("close", () => {
      dlg.remove();
      resolve(dlg.returnValue === "ok");
    });
    document.body.append(dlg);
    dlg.showModal();
  });
}
