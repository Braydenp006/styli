// The folder tree in the sidebar. Pure functions, so they can be tested without a browser.

// Turn the server's flat, sorted folder list ("a", "a/b", "c") into nested
// nodes: { name, path, children }. Siblings are sorted by name, ignoring case
// and with numbers in order ("Week 2" before "Week 10").
export function buildTree(folders) {
  const roots = [];
  const byPath = new Map();
  for (const path of [...folders].sort()) {
    const parts = path.split("/");
    const node = { name: parts[parts.length - 1], path, children: [] };
    byPath.set(path, node);
    const parent = byPath.get(parts.slice(0, -1).join("/"));
    (parent ? parent.children : roots).push(node);
  }
  const sortNodes = (nodes) => {
    nodes.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base", numeric: true }));
    nodes.forEach((n) => sortNodes(n.children));
  };
  sortNodes(roots);
  return roots;
}

// The folders above a path: "a/b/c" gives ["a", "a/b"].
export function ancestors(path) {
  const parts = path.split("/").filter(Boolean);
  return parts.slice(0, -1).map((_, i) => parts.slice(0, i + 1).join("/"));
}
