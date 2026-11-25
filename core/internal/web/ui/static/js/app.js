import { api, ApiError } from "./api.js";
import { h, setChildren } from "./dom.js";
import { href, onRouteChange } from "./router.js";

const app = document.getElementById("app");
const nav = h("nav", { class: "sty-nav sidebar", "aria-label": "Vault" });
const main = h("main", { id: "main", class: "main", tabindex: "-1" });
setChildren(app, nav, main);

let folders = [];
let route = { name: "home" };

function navItem(label, to, current) {
  return h("a", { class: "sty-nav-item" + (current ? " is-active" : ""), href: to, "aria-current": current ? "page" : false }, label);
}

function folderItem(path) {
  const depth = path.split("/").length - 1;
  const el = navItem(path.split("/").pop(), href.folder(path), route.name === "folder" && route.path === path);
  el.style.setProperty("--depth", depth);
  return el;
}

function drawNav() {
  setChildren(
    nav,
    h(
      "a",
      { class: "brand", href: href.home(), "aria-label": "Styli, all notes" },
      h("picture", {},
        h("source", { srcset: "/static/img/styli-mark-dark-bg.svg", media: "(prefers-color-scheme: dark)" }),
        h("img", { src: "/static/img/styli-mark-light-bg.svg", alt: "", width: "30", height: "30" })),
      h("span", {}, "STYLI"),
    ),
    navItem("All notes", href.home(), route.name === "home"),
    h("div", { class: "nav-folders" }, folders.map(folderItem)),
    h("span", { class: "nav-sep", role: "separator" }),
    navItem("Trash", href.trash(), route.name === "trash"),
    navItem("Hub dashboard", href.dashboard(), route.name === "dashboard"),
    navItem("Settings", href.settings(), route.name === "settings"),
  );
}

function emptyState(title, text) {
  return h("div", { class: "empty" }, h("h2", {}, title), h("p", {}, text));
}

function drawMain() {
  document.title = "Styli";
  switch (route.name) {
    case "home":
      return setChildren(main, emptyState("Your notes", "The notes in your vault will show up here."));
    case "folder":
      return setChildren(main, h("h1", { class: "view-title" }, route.path.split("/").pop()), h("p", { class: "view-sub" }, route.path));
    case "trash":
      return setChildren(main, h("h1", { class: "view-title" }, "Trash"));
    case "dashboard":
      return setChildren(main, h("h1", { class: "view-title" }, "Hub dashboard"));
    case "settings":
      return setChildren(main, h("h1", { class: "view-title" }, "Settings"));
    default:
      return setChildren(main, emptyState("Nothing here", "That page doesn't exist. Use the menu to find your notes."));
  }
}

function showError(err) {
  const message = err instanceof ApiError ? err.message : "Something went wrong.";
  main.prepend(
    h("div", { class: "banner banner--danger", role: "alert" },
      h("span", { class: "grow" }, message),
      h("button", { class: "sty-btn sty-btn--secondary", type: "button", onclick: start }, "Try again")),
  );
}

async function loadFolders() {
  folders = (await api.get("/api/folders")).folders;
}

async function start() {
  try {
    await loadFolders();
  } catch (err) {
    drawNav();
    drawMain();
    return showError(err);
  }
  drawNav();
  drawMain();
}

let firstRoute = true;
onRouteChange((r) => {
  route = r;
  drawNav();
  drawMain();
  if (!firstRoute) main.focus({ preventScroll: true });
  firstRoute = false;
});
start();
