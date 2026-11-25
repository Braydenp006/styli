// A tiny helper for building elements without innerHTML, so text from notes,
// file names and the server can never turn into markup.
//
//   h("a", { class: "sty-nav-item", href: "#/trash" }, "Trash")
//   h("button", { onclick: save }, "Save")
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === false || value == null) continue;
    if (key.startsWith("on") && typeof value === "function") {
      el.addEventListener(key.slice(2), value);
    } else if (key === "class") {
      el.className = value;
    } else if (value === true) {
      el.setAttribute(key, "");
    } else {
      el.setAttribute(key, String(value));
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

// Replace everything inside el.
export function setChildren(el, ...children) {
  el.replaceChildren();
  append(el, children);
}
