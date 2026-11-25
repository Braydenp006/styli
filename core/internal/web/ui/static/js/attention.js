// What needs the person's attention on the hub, and how to say it. Pure
// functions, so they are tested without a browser.

// Turn the maid's last run and the list of missing notes into the things to
// show: { kind, ... } where kind is "missing", "clock", "held" or "error".
// A held-back cleanup is one item however many jobs were held, because one
// approval covers them all.
export function attentionItems({ run, missing = [] }) {
  const items = missing.map((m) => ({ kind: "missing", id: m.id, paths: m.paths }));
  if (!run) return items;
  if (run.time_check === "wrong") items.push({ kind: "clock", offsetSeconds: run.clock_offset_seconds });
  const held = [];
  for (const [job, label] of [["history", "old versions"], ["trash", "items in the trash"]]) {
    const r = run[job];
    if (r.held_back) held.push({ job, label, wouldDelete: r.would_delete, total: r.total });
    if (r.error) items.push({ kind: "error", job, message: r.error });
  }
  if (held.length) items.push({ kind: "held", jobs: held });
  return items;
}

// "about 3 hours behind": how far this computer's clock is from the time
// server's. The server reports the time server's clock minus ours.
export function describeOffset(seconds) {
  const abs = Math.abs(seconds);
  const [n, unit] = abs < 3600 ? [Math.max(1, Math.round(abs / 60)), "minute"] : abs < 48 * 3600 ? [Math.round(abs / 3600), "hour"] : [Math.round(abs / 86400), "day"];
  return `about ${n} ${unit}${n === 1 ? "" : "s"} ${seconds > 0 ? "behind" : "ahead of"}`;
}

const NOUN = { history: ["old version", "old versions"], trash: ["item", "items"] };

// One line about what a cleanup job did on the last run.
export function describeJob(job, r) {
  const [one, many] = NOUN[job];
  const where = job === "history" ? "" : " from the trash";
  if (r.error) return `Failed: ${r.error}`;
  if (r.held_back) return `Held back: it would delete ${r.would_delete} of ${r.total}.`;
  if (r.skipped) return `Skipped: ${r.skipped.replace(/^./, (c) => c.toLowerCase())}.`;
  if (r.deleted) return `Deleted ${r.deleted} ${r.deleted === 1 ? one : many}${where}.`;
  return "Nothing to delete.";
}

// The line about the clock check.
export function describeTimeCheck(run) {
  switch (run?.time_check) {
    case "ok":
      return "This computer's clock was checked against the time server and matches.";
    case "wrong":
      return `This computer's clock is ${describeOffset(run.clock_offset_seconds)} the time server.`;
    case "unreachable":
      return "The time server could not be reached, so the maid relied on its other safety checks.";
    case "off":
      return "The time check is off. Turn it on in Settings to compare this computer's clock with a time server before anything is deleted.";
    default:
      return "";
  }
}

export const baseName = (path) => path.slice(path.lastIndexOf("/") + 1);
