import { api, ApiError } from "./api.js";
import { h, setChildren } from "./dom.js";
import { href, onRouteChange } from "./router.js";
import * as vault from "./views/vault.js";
import * as trash from "./views/trash.js";
import * as dashboard from "./views/dashboard.js";
import * as settings from "./views/settings.js";
import { attentionItems } from "./attention.js";
import { buildTree, ancestors } from "./tree.js";

const app = document.getElementById("app");
const brand = h("a", { class: "brand", href: href.home(), "aria-label": "Styli, tabularium" },
  h("picture", {},
    h("source", { srcset: "/static/img/styli-mark-dark-bg.svg", media: "(prefers-color-scheme: dark)" }),
    h("img", { src: "/static/img/styli-mark-light-bg.svg", alt: "", width: "30", height: "30" })),
  h("span", {}, "STYLI"));
const menuItems = h("div", { class: "menu-items" });
// On a phone the menu folds away behind a button; on a wider screen it is always open.
const menu = h("details", { class: "menu", open: true },
  h("summary", { class: "menu-summary" }, "Menu"), menuItems);
const nav = h("nav", { class: "sty-nav sidebar", "aria-label": "Vault" }, brand, menu);
const main = h("main", { id: "main", class: "main", tabindex: "-1" });
setChildren(app, nav, main);

const phone = window.matchMedia("(max-width: 720px)");
const syncMenu = () => { menu.open = !phone.matches; };
phone.addEventListener("change", syncMenu);
syncMenu();

let folders = [];
let route = { name: "home" };

// Which folders are open in the sidebar is remembered in this browser only.
const NAV_KEY = "styli.nav";
const navState = loadNavState();

function loadNavState() {
  try {
    const saved = JSON.parse(localStorage.getItem(NAV_KEY));
    return { root: saved?.root !== false, open: new Set(Array.isArray(saved?.open) ? saved.open.filter((p) => typeof p === "string") : []) };
  } catch {
    return { root: true, open: new Set() }; // storage may be blocked; the sidebar still works
  }
}

function saveNavState() {
  try {
    localStorage.setItem(NAV_KEY, JSON.stringify({ root: navState.root, open: [...navState.open].filter((p) => folders.includes(p)) }));
  } catch {
    // not remembered; nothing else to do
  }
}

const chevron = () => {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  for (const [k, v] of Object.entries({ viewBox: "0 0 20 20", width: "16", height: "16", fill: "none", stroke: "currentColor", "stroke-width": "1.5", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true" })) svg.setAttribute(k, v);
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", "m5 8 5 5 5-5");
  svg.append(path);
  return svg;
};

function navItem(label, to, current, count = 0, countText = null) {
  return h("a", {
    class: "sty-nav-item" + (current ? " is-active" : ""), href: to, "aria-current": current ? "page" : false,
    "aria-label": count ? `${label}, ${countText ?? `${count} ${count === 1 ? "item" : "items"}`}` : false,
  }, label, count ? h("span", { class: "sty-nav-count", "aria-hidden": "true" }, String(count)) : null);
}

// The sidebar also shows how many things on the atrium need a decision.
let attentionCount = 0;
async function refreshAttention() {
  try {
    const [status, missing] = await Promise.all([api.get("/api/status"), api.get("/api/missing")]);
    attentionCount = attentionItems({ run: status.last_maid_run, missing: missing.missing, failed: status.failed_commits, recovery: status.recovery }).length;
    drawNav();
  } catch {
    // keep the old number; the dashboard shows the real error if there is one
  }
}
document.addEventListener("attention-changed", refreshAttention);
document.addEventListener("attention-count", (e) => {
  if (e.detail !== attentionCount) {
    attentionCount = e.detail;
    drawNav();
  }
});
setInterval(() => { if (document.visibilityState === "visible") refreshAttention(); }, 60000);

// The sidebar shows how many items are in the trash.
let trashCount = 0;
async function refreshTrashCount() {
  try {
    trashCount = (await api.get("/api/trash")).trash.length;
    drawNav();
  } catch {
    // leave the old number; the trash screen shows the real error if there is one
  }
}
document.addEventListener("trash-changed", () => { refreshTrashCount(); refreshAttention(); }); // recovery candidates count as attention

// Which sidebar entry is highlighted. Reading a note keeps its folder highlighted.
function activeFolder() {
  if (route.name === "folder") return route.path;
  if (route.name === "note" || route.name === "history") return vault.currentFolder();
  return "";
}

// One sidebar row: the link, and (if there is something to open) an arrow button beside it.
function navRow({ label, to, current, depth = 0, key, expanded, hasChildren, controls, onToggle }) {
  const link = navItem(label, to, current);
  link.style.setProperty("--depth", depth);
  const toggle = hasChildren
    ? h("button", {
        class: "nav-toggle", type: "button", "data-nav-key": key, "aria-expanded": String(expanded), "aria-controls": controls,
        "aria-label": `${expanded ? "Hide" : "Show"} the folders inside ${label}`, onclick: onToggle,
      }, chevron())
    : null;
  return h("div", { class: "nav-row" }, link, toggle);
}

// Going to a folder (or a note inside it) unfolds the folders above it, so it is
// never hidden. Only on arriving: after that the person can fold them again.
function revealActive() {
  const active = activeFolder();
  if (!active) return;
  navState.root = true;
  for (const p of ancestors(active)) navState.open.add(p);
  saveNavState();
}

function drawNav() {
  const inVault = route.name === "home" || route.name === "folder" || route.name === "note" || route.name === "history";
  const active = activeFolder();
  const tree = buildTree(folders);

  const nodes = (list, depth) => list.map((node) => {
    const open = navState.open.has(node.path);
    const id = "nav-" + node.path.replace(/[^A-Za-z0-9_-]/g, "_");
    return h("div", { class: "nav-node" },
      navRow({
        label: node.name, to: href.folder(node.path), current: inVault && active === node.path, depth, key: node.path,
        expanded: open, hasChildren: node.children.length > 0, controls: id,
        onToggle: () => { if (open) navState.open.delete(node.path); else navState.open.add(node.path); saveNavState(); drawNav(); },
      }),
      open && node.children.length ? h("div", { class: "nav-children", id }, nodes(node.children, depth + 1)) : null);
  });

  const focusedKey = document.activeElement?.dataset?.navKey; // keep the keyboard where it was after redrawing
  setChildren(
    menuItems,
    navRow({
      label: "Tabularium", to: href.home(), current: inVault && active === "", key: "", expanded: navState.root, hasChildren: tree.length > 0, controls: "nav-tree",
      onToggle: () => { navState.root = !navState.root; saveNavState(); drawNav(); },
    }),
    navState.root && tree.length ? h("div", { class: "nav-folders", id: "nav-tree" }, nodes(tree, 1)) : null,
    h("span", { class: "nav-sep", role: "separator" }),
    navItem("Trash", href.trash(), route.name === "trash", trashCount),
    navItem("Atrium dashboard", href.dashboard(), route.name === "dashboard", attentionCount,
      `${attentionCount} ${attentionCount === 1 ? "thing needs" : "things need"} your attention`),
    navItem("Settings", href.settings(), route.name === "settings"),
  );
  if (focusedKey !== undefined) {
    [...menuItems.querySelectorAll("[data-nav-key]")].find((el) => el.dataset.navKey === focusedKey)?.focus();
  }
}

function simpleView(...children) {
  main.classList.remove("main--bleed");
  document.title = "Styli";
  setChildren(main, ...children);
}

function drawMain() {
  if (route.name === "home" || route.name === "folder" || route.name === "note" || route.name === "history") {
    main.classList.add("main--bleed");
    if (main.firstChild !== vault.element) setChildren(main, vault.element);
    vault.show(route);
    return;
  }
  switch (route.name) {
    case "trash":
      simpleView(trash.element);
      return trash.show();
    case "dashboard":
      simpleView(dashboard.element);
      return dashboard.show();
    case "settings":
      simpleView(settings.element);
      return settings.show();
    default:
      return simpleView(h("div", { class: "empty" }, h("h2", {}, "Nothing here"), h("p", {}, "That page doesn't exist. Use the menu to find your notes.")));
  }
}

async function refreshFolders() {
  try {
    folders = (await api.get("/api/folders")).folders;
    drawNav();
  } catch (err) {
    if (!(err instanceof ApiError)) throw err;
    // The note list shows its own error with a retry button; the sidebar just keeps what it had.
  }
}

vault.init({ refreshFolders, folders: () => folders });
trash.init({ refreshFolders, folders: () => folders });
dashboard.init({ refreshFolders });

// Moving away from a note first saves it. If it can't be saved, the person
// stays where they are with their text intact, and the address goes back.
let firstRoute = true;
let routeSeq = 0;
let shownHash = location.hash;
onRouteChange(async (r) => {
  const seq = ++routeSeq;
  const allowed = (await vault.canLeave(r)) && (await settings.canLeave(r));
  if (seq !== routeSeq) return; // the person already went somewhere else
  if (!allowed) {
    history.replaceState(null, "", shownHash || "#/");
    return;
  }
  shownHash = location.hash;
  route = r;
  if (r.name === "folder") revealActive();
  drawNav();
  drawMain();
  if (phone.matches) menu.open = false;
  if (!firstRoute) main.focus({ preventScroll: true });
  firstRoute = false;
});
refreshFolders();
refreshTrashCount();
refreshAttention();
