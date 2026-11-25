// A line-by-line comparison of two texts, for the version history. Pure
// functions, so they are tested without a browser.

// The most cells the line table may hold. Past this, the part of the text that
// differs is shown as "all removed, then all added", which is still correct,
// just less precise.
// shortcut: no Myers diff; common start and end lines are trimmed first, so
// ordinary edits are cheap, and only a huge rewrite hits the limit.
const MAX_CELLS = 4_000_000;

// Split into lines. CRLF counts as a newline, and a final newline does not
// make an extra empty line.
export function splitLines(text) {
  if (text === "") return [];
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  if (lines[lines.length - 1] === "") lines.pop();
  return lines;
}

// Returns a list of { type: "same" | "del" | "add", text }, in reading order:
// "del" lines are in oldText only, "add" lines are in newText only.
export function diffLines(oldText, newText) {
  const a = splitLines(oldText);
  const b = splitLines(newText);
  let start = 0;
  while (start < a.length && start < b.length && a[start] === b[start]) start++;
  let endA = a.length;
  let endB = b.length;
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--;
    endB--;
  }
  return [
    ...a.slice(0, start).map((text) => ({ type: "same", text })),
    ...diffMiddle(a.slice(start, endA), b.slice(start, endB)),
    ...a.slice(endA).map((text) => ({ type: "same", text })),
  ];
}

function diffMiddle(a, b) {
  const del = (text) => ({ type: "del", text });
  const add = (text) => ({ type: "add", text });
  if (a.length === 0) return b.map(add);
  if (b.length === 0) return a.map(del);
  if (a.length * b.length > MAX_CELLS) return [...a.map(del), ...b.map(add)];

  // lcs[i][j] is the length of the longest common run of lines in a[i..] and b[j..].
  const w = b.length + 1;
  const lcs = new Uint32Array((a.length + 1) * w);
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i * w + j] = a[i] === b[j] ? lcs[(i + 1) * w + j + 1] + 1 : Math.max(lcs[(i + 1) * w + j], lcs[i * w + j + 1]);
    }
  }
  const ops = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      ops.push({ type: "same", text: a[i] });
      i++;
      j++;
    } else if (lcs[(i + 1) * w + j] >= lcs[i * w + j + 1]) {
      ops.push(del(a[i++]));
    } else {
      ops.push(add(b[j++]));
    }
  }
  while (i < a.length) ops.push(del(a[i++]));
  while (j < b.length) ops.push(add(b[j++]));
  return ops;
}

// Group the comparison into what to show and what to fold away: every change
// with `context` unchanged lines around it is shown, and the long unchanged
// stretches in between become { kind: "skip", ops }.
export function collapse(ops, context = 3) {
  const show = new Array(ops.length).fill(false);
  ops.forEach((op, i) => {
    if (op.type === "same") return;
    for (let k = Math.max(0, i - context); k <= Math.min(ops.length - 1, i + context); k++) show[k] = true;
  });
  const items = [];
  for (let i = 0; i < ops.length; ) {
    let j = i;
    while (j < ops.length && show[j] === show[i]) j++;
    items.push({ kind: show[i] ? "lines" : "skip", ops: ops.slice(i, j) });
    i = j;
  }
  return items;
}

export const hasChanges = (ops) => ops.some((op) => op.type !== "same");
