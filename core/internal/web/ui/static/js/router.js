// Routes live in the part of the address after "#", so the server needs no
// route table of its own:
//   #/                 all notes
//   #/folder/<path>    one folder
//   #/note/<id>        one note
//   #/trash  #/dashboard  #/settings
export function parseRoute(hash) {
  const parts = hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeSafe);
  const [name, ...rest] = parts;
  switch (name) {
    case undefined:
      return { name: "home" };
    case "folder":
      return { name: "folder", path: rest.join("/") };
    case "note":
      return rest.length === 1 ? { name: "note", id: rest[0] } : { name: "notfound" };
    case "trash":
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
  trash: () => "#/trash",
  dashboard: () => "#/dashboard",
  settings: () => "#/settings",
};

export function onRouteChange(fn) {
  const run = () => fn(parseRoute(location.hash));
  window.addEventListener("hashchange", run);
  run();
}
