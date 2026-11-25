import { api } from "./api.js";

// Show Markdown in el. The server turns it into HTML (see mdrender in the Go
// code): raw HTML in a note is dropped there, and the page's Content-Security-
// Policy would stop any script anyway. That is why setting innerHTML here is
// acceptable, and it is the only place in the UI that does.
export async function renderMarkdown(el, text) {
  const { html } = await api.post("/api/render", { text });
  el.innerHTML = html;
  for (const a of el.querySelectorAll("a[href]")) {
    // The server empties links it will not allow (javascript: and the like).
    // An empty href would reload this page when clicked, so show the text only.
    if (a.getAttribute("href") === "") {
      const span = document.createElement("span");
      span.className = "dead-link";
      span.title = "This link was removed because it isn't safe";
      span.append(...a.childNodes);
      a.replaceWith(span);
      continue;
    }
    const url = new URL(a.getAttribute("href"), location.href);
    if (url.origin !== location.origin) {
      a.target = "_blank";
      a.rel = "noopener noreferrer";
    }
  }
  renderMath(el);
}

// The server marks math as <span class="math ..."> holding the TeX as text;
// KaTeX turns it into typeset math. If KaTeX did not load, or a formula is
// broken, the TeX source stays visible instead of disappearing.
export function renderMath(root) {
  if (!window.katex) return;
  for (const el of root.querySelectorAll(".math")) {
    try {
      window.katex.render(el.textContent, el, {
        displayMode: el.classList.contains("math-display"),
        throwOnError: false,
        trust: false,
        strict: "ignore",
        maxExpand: 1000,
      });
    } catch {
      // leave the source text as it is
    }
  }
}
