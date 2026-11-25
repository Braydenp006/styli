// Routes live in the part of the address after "#", so the server needs no
// route table of its own:
//   #/                 all notes
//   #/folder/<path>    one folder
//   #/note/<id>        one note
//   #/note/<id>/history   its saved versions
//   #/trash  #/conflicts  #/dashboard  #/settings
//   #/join/<secret>    a one-time sign-in link (see views/signin.js)
export function parseRoute(hash) {
  const parts = hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeSafe);
  const [name, ...rest] = parts;
  switch (name) {
    case undefined:
      return { name: "home" };
    case "folder":
      return { name: "folder", path: rest.join("/") };
    case "note":
      if (rest.length === 1) return { name: "note", id: rest[0] };
      if (rest.length === 2 && rest[1] === "history") return { name: "history", id: rest[0] };
      return { name: "notfound" };
    case "join":
      return { name: "join", token: rest.length === 1 ? rest[0] : "" };
    case "trash":
    case "conflicts":
    case "dashboard":
    case "settings":
      return rest.length === 0 ? { name } : { name: "notfound" };
    default:
      return { name: "notfound" };
  }
}

function decodeSafe(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

export const href = {
  home: () => "#/",
  folder: (path) => "#/folder/" + path.split("/").map(encodeURIComponent).join("/"),
  note: (id) => "#/note/" + encodeURIComponent(id),
  history: (id) => "#/note/" + encodeURIComponent(id) + "/history",
  trash: () => "#/trash",
  conflicts: () => "#/conflicts",
  dashboard: () => "#/dashboard",
  settings: () => "#/settings",
};

export function onRouteChange(fn) {
  const run = () => fn(parseRoute(location.hash));
  window.addEventListener("hashchange", run);
  run();
}
