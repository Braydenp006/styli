// Reads the conflict markers a sync merge writes into a note:
//   <<<<<<< this device / ======= / >>>>>>> atrium
// Returns [{ type: "same", lines } | { type: "clash", mine, theirs }].
// A marker without its other two halves is treated as plain text, so a note
// that only mentions "=======" is never mangled.
import { splitLines } from "./diff.js";

export function parseMarkers(text) {
  const lines = splitLines(text);
  const parts = [];
  let same = [];
  for (let i = 0; i < lines.length; i++) {
    const clash = lines[i].startsWith("<<<<<<<") ? readClash(lines, i) : null;
    if (!clash) { same.push(lines[i]); continue; }
    if (same.length) parts.push({ type: "same", lines: same });
    same = [];
    parts.push({ type: "clash", mine: clash.mine, theirs: clash.theirs });
    i = clash.end;
  }
  if (same.length) parts.push({ type: "same", lines: same });
  return parts;
}

function readClash(lines, start) {
  const mid = lines.findIndex((l, i) => i > start && l === "=======");
  if (mid < 0) return null;
  const end = lines.findIndex((l, i) => i > mid && l.startsWith(">>>>>>>"));
  if (end < 0) return null;
  return { mine: lines.slice(start + 1, mid), theirs: lines.slice(mid + 1, end), end };
}

export const clashCount = (parts) => parts.filter((p) => p.type === "clash").length;
