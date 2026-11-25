// Turn what someone typed into a safe file name (without the .md). The server
// checks again; this just avoids sending names it would refuse.
export function noteFileName(typed) {
  const name = typed
    .replace(/[\u0000-\u001f\u007f\\/:]+/g, " ") // control characters and the characters a path can't hold
    .replace(/\s+/g, " ")
    .replace(/^[.\s]+/, "") // a leading dot would make the file hidden, and hidden files are ignored
    .slice(0, 120)
    .trim();
  return name || "Untitled";
}

// Normalise a typed folder: "a\b//c/" becomes "a/b/c". Returns null for
// anything that can't be a folder name here (a part that starts with a dot).
export function folderPath(typed) {
  const parts = typed.trim().replace(/\\/g, "/").split("/").map((s) => s.trim()).filter(Boolean);
  if (parts.some((p) => p.startsWith(".") || /[\u0000-\u001f\u007f:]/.test(p))) return null;
  return parts.join("/");
}

// The first "# Heading" line of a note, if it has one.
export function titleOf(text) {
  for (const line of text.split("\n", 200)) {
    if (line.startsWith("# ") && line.slice(2).trim()) return line.slice(2).trim();
  }
  return null;
}
