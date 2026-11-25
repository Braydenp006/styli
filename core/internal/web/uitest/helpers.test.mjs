// Unit tests for the UI's pure helper modules. Run with `node --test` (Node 22.7 or newer).
// They sit outside ui/ so they are not built into the binary.
import test from "node:test";
import assert from "node:assert/strict";
import { noteFileName, folderPath, titleOf } from "../ui/static/js/names.js";
import { relativeTime, relativeFuture } from "../ui/static/js/time.js";
import { parseRoute, href } from "../ui/static/js/router.js";

test("noteFileName keeps ordinary names and unicode", () => {
  assert.equal(noteFileName("Lecture 13"), "Lecture 13");
  assert.equal(noteFileName("  Café — notes  "), "Café — notes");
  assert.equal(noteFileName("日本語のノート"), "日本語のノート");
});

test("noteFileName removes what a path cannot hold", () => {
  assert.equal(noteFileName("a/b\\c:d"), "a b c d");
  assert.equal(noteFileName("tab\tand\nnewline\u0000nul"), "tab and newline nul");
  assert.equal(noteFileName("../../etc/passwd"), "etc passwd");
});

test("noteFileName never starts with a dot, is never empty, and is not endless", () => {
  assert.equal(noteFileName(".hidden"), "hidden");
  assert.equal(noteFileName("...  .x"), "x");
  assert.equal(noteFileName(""), "Untitled");
  assert.equal(noteFileName("   "), "Untitled");
  assert.equal(noteFileName("///"), "Untitled");
  assert.equal(noteFileName("a".repeat(500)).length, 120);
});

test("folderPath tidies a typed folder", () => {
  assert.equal(folderPath(""), "");
  assert.equal(folderPath("  "), "");
  assert.equal(folderPath("a/b"), "a/b");
  assert.equal(folderPath("a\\b//c/"), "a/b/c");
  assert.equal(folderPath("/Science / Bio /"), "Science/Bio");
});

test("folderPath refuses hidden folders and colons", () => {
  for (const bad of [".git/x", "a/.b", ".ids", "a/../b", "c:x", "a/b:c", "x\u0000y"]) {
    assert.equal(folderPath(bad), null, bad);
  }
});

test("titleOf finds the first level-one heading only", () => {
  assert.equal(titleOf("# Title\n\ntext"), "Title");
  assert.equal(titleOf("intro\n\n# Later title\n"), "Later title");
  assert.equal(titleOf("#hashtag\n## Sub\n"), null);
  assert.equal(titleOf("# \n# Real"), "Real");
  assert.equal(titleOf(""), null);
  assert.equal(titleOf("no heading here"), null);
});

test("relativeTime reads like a person would say it", () => {
  const now = new Date("2026-03-10T12:00:00Z");
  const ago = (s) => new Date(now - s * 1000);
  assert.equal(relativeTime(ago(5), now), "just now");
  assert.equal(relativeTime(ago(44), now), "just now");
  assert.equal(relativeTime(ago(60), now), "1 min ago");
  assert.equal(relativeTime(ago(125), now), "2 min ago");
  assert.equal(relativeTime(ago(3600 * 3), now), "3 h ago");
  assert.equal(relativeTime(ago(86400), now), "yesterday");
  assert.equal(relativeTime(ago(86400 * 3), now), "3 days ago");
  assert.match(relativeTime(ago(86400 * 40), now), /Jan|Feb/);
  assert.equal(relativeTime("nonsense", now), "");
  assert.equal(relativeTime(ago(-30), now), "just now"); // a clock slightly ahead
});

test("parseRoute", () => {
  assert.deepEqual(parseRoute(""), { name: "home" });
  assert.deepEqual(parseRoute("#"), { name: "home" });
  assert.deepEqual(parseRoute("#/"), { name: "home" });
  assert.deepEqual(parseRoute("#/folder/CISC%20324/Lectures"), { name: "folder", path: "CISC 324/Lectures" });
  assert.deepEqual(parseRoute("#/note/abc123"), { name: "note", id: "abc123" });
  assert.deepEqual(parseRoute("#/trash"), { name: "trash" });
  assert.deepEqual(parseRoute("#/dashboard"), { name: "dashboard" });
  assert.deepEqual(parseRoute("#/settings"), { name: "settings" });
});

test("parseRoute sends anything odd to not-found instead of throwing", () => {
  for (const bad of ["#/nope", "#/note", "#/note/a/b", "#/trash/extra", "#/folder%", "#//x"]) {
    const r = parseRoute(bad);
    assert.ok(["notfound", "folder", "home"].includes(r.name), bad);
  }
  assert.deepEqual(parseRoute("#/note/%E0%A4%A"), { name: "note", id: "%E0%A4%A" }); // malformed % is kept as is
});

test("href builds links that parseRoute reads back", () => {
  assert.equal(href.home(), "#/");
  const folder = "Science/Bio & more/100%";
  assert.deepEqual(parseRoute(href.folder(folder)), { name: "folder", path: folder });
  const id = "ejguj4tf6pyapkrwnyoc2ha5gm";
  assert.deepEqual(parseRoute(href.note(id)), { name: "note", id });
  assert.equal(href.trash(), "#/trash");
});

import { buildTree, ancestors } from "../ui/static/js/tree.js";

test("buildTree nests folders and keeps parents above children", () => {
  const tree = buildTree(["Archive", "Archive/2026", "Science", "Science/Bio", "Science/Bio/Cells", "Science/Chem"]);
  assert.deepEqual(tree.map((n) => n.path), ["Archive", "Science"]);
  const science = tree[1];
  assert.deepEqual(science.children.map((n) => n.name), ["Bio", "Chem"]);
  assert.deepEqual(science.children[0].children.map((n) => n.path), ["Science/Bio/Cells"]);
  assert.deepEqual(tree[0].children[0], { name: "2026", path: "Archive/2026", children: [] });
});

test("buildTree sorts siblings by name, ignoring case, with numbers in order", () => {
  const tree = buildTree(["Week 10", "Week 2", "apple", "Banana", "Week 1"]);
  assert.deepEqual(tree.map((n) => n.name), ["apple", "Banana", "Week 1", "Week 2", "Week 10"]);
});

test("buildTree copes with an order the server does not use, and with nothing", () => {
  assert.deepEqual(buildTree([]), []);
  const tree = buildTree(["a/b", "a", "a-b"]); // "a-b" sorts between "a" and "a/b"
  assert.deepEqual(tree.map((n) => n.path), ["a", "a-b"]);
  assert.deepEqual(tree[0].children.map((n) => n.path), ["a/b"]);
});

test("buildTree does not lose a folder whose parent is missing", () => {
  assert.deepEqual(buildTree(["x/y"]).map((n) => n.path), ["x/y"]);
});

test("ancestors lists the folders above a path", () => {
  assert.deepEqual(ancestors("a/b/c"), ["a", "a/b"]);
  assert.deepEqual(ancestors("a"), []);
  assert.deepEqual(ancestors(""), []);
  assert.deepEqual(ancestors("/a//b/"), ["a"]);
});

test("relativeFuture says how long until a moment, and nothing once it has passed", () => {
  const now = new Date("2026-03-10T12:00:00Z");
  const ahead = (s) => new Date(now.getTime() + s * 1000);
  assert.equal(relativeFuture(ahead(600), now), "in less than an hour");
  assert.equal(relativeFuture(ahead(3600 * 5), now), "in 5 h");
  assert.equal(relativeFuture(ahead(86400), now), "tomorrow");
  assert.equal(relativeFuture(ahead(86400 * 28), now), "in 28 days");
  assert.equal(relativeFuture(ahead(0), now), "");
  assert.equal(relativeFuture(ahead(-100), now), "");
  assert.equal(relativeFuture("nonsense", now), "");
});

import { splitLines, diffLines, collapse, hasChanges } from "../ui/static/js/diff.js";

const show = (ops) => ops.map((o) => ({ same: " ", del: "-", add: "+" })[o.type] + o.text).join("|");

test("splitLines treats CRLF as a newline and ignores one final newline", () => {
  assert.deepEqual(splitLines(""), []);
  assert.deepEqual(splitLines("a"), ["a"]);
  assert.deepEqual(splitLines("a\n"), ["a"]);
  assert.deepEqual(splitLines("a\n\n"), ["a", ""]);
  assert.deepEqual(splitLines("a\r\nb\rc\n"), ["a", "b", "c"]);
});

test("diffLines on equal and empty texts", () => {
  assert.equal(show(diffLines("a\nb\n", "a\nb\n")), " a| b");
  assert.equal(show(diffLines("a\r\nb", "a\nb\n")), " a| b"); // line endings alone are not a change
  assert.deepEqual(diffLines("", ""), []);
  assert.equal(show(diffLines("", "x\ny")), "+x|+y");
  assert.equal(show(diffLines("x\ny", "")), "-x|-y");
});

test("diffLines finds additions, deletions and replacements", () => {
  assert.equal(show(diffLines("a\nc", "a\nb\nc")), " a|+b| c");
  assert.equal(show(diffLines("a\nb\nc", "a\nc")), " a|-b| c");
  assert.equal(show(diffLines("a\nb\nc", "a\nB\nc")), " a|-b|+B| c");
  assert.equal(show(diffLines("start\nold\nend", "start\nnew1\nnew2\nend")), " start|-old|+new1|+new2| end");
});

test("diffLines keeps unchanged lines that move apart", () => {
  assert.equal(show(diffLines("1\n2\n3\n4\n5", "1\nX\n3\nY\n5")), " 1|-2|+X| 3|-4|+Y| 5");
  // The same line twice: the matching is by position, not just by text.
  assert.equal(show(diffLines("a\nb\na", "a\na")), " a|-b| a");
});

test("diffLines output always rebuilds both texts", () => {
  const cases = [["a\nb\nc\nd", "d\nc\nb\na"], ["x", "y"], ["a\n\n\nb", "a\nb"], ["one\ntwo\nthree", "zero\none\nthree\nfour"]];
  for (const [oldText, newText] of cases) {
    const ops = diffLines(oldText, newText);
    assert.deepEqual(ops.filter((o) => o.type !== "add").map((o) => o.text), splitLines(oldText));
    assert.deepEqual(ops.filter((o) => o.type !== "del").map((o) => o.text), splitLines(newText));
  }
});

test("diffLines copes with a big rewrite without hanging, and stays correct", () => {
  const a = Array.from({ length: 3000 }, (_, i) => "old " + i).join("\n");
  const b = Array.from({ length: 3000 }, (_, i) => "new " + i).join("\n");
  const start = Date.now();
  const ops = diffLines(a, b);
  assert.ok(Date.now() - start < 2000);
  assert.deepEqual(ops.filter((o) => o.type !== "add").map((o) => o.text), splitLines(a));
  assert.deepEqual(ops.filter((o) => o.type !== "del").map((o) => o.text), splitLines(b));
});

test("diffLines on a long note with one small edit is exact", () => {
  const lines = Array.from({ length: 20000 }, (_, i) => "line " + i);
  const edited = [...lines];
  edited[10000] = "CHANGED";
  const ops = diffLines(lines.join("\n"), edited.join("\n"));
  assert.equal(ops.filter((o) => o.type === "del").length, 1);
  assert.equal(ops.filter((o) => o.type === "add").length, 1);
});

test("collapse folds long unchanged stretches and keeps the context around changes", () => {
  const same = (n, p) => Array.from({ length: n }, (_, i) => ({ type: "same", text: p + i }));
  const ops = [...same(10, "a"), { type: "add", text: "X" }, ...same(10, "b")];
  const items = collapse(ops, 3);
  assert.deepEqual(items.map((i) => [i.kind, i.ops.length]), [["skip", 7], ["lines", 7], ["skip", 7]]);
  assert.equal(items[1].ops[3].text, "X");
  assert.deepEqual(collapse(same(5, "z"), 3).map((i) => i.kind), ["skip"]);
  assert.deepEqual(collapse([], 3), []);
  assert.deepEqual(collapse([{ type: "add", text: "x" }], 3).map((i) => i.kind), ["lines"]);
  assert.equal(hasChanges(ops), true);
  assert.equal(hasChanges(same(3, "q")), false);
});

test("parseRoute reads the history route", () => {
  assert.deepEqual(parseRoute("#/note/abc/history"), { name: "history", id: "abc" });
  assert.deepEqual(parseRoute("#/note/abc/other"), { name: "notfound" });
  assert.deepEqual(parseRoute("#/note/abc/history/x"), { name: "notfound" });
  const id = "ejguj4tf6pyapkrwnyoc2ha5gm";
  assert.deepEqual(parseRoute(href.history(id)), { name: "history", id });
});

import { attentionItems, describeOffset, describeJob, describeTimeCheck, baseName } from "../ui/static/js/attention.js";

const job = (o = {}) => ({ deleted: 0, would_delete: 0, total: 0, held_back: false, ...o });
const run = (o = {}) => ({ time_check: "off", clock_offset_seconds: 0, history: job(), trash: job(), needs_attention: false, ...o });

test("attentionItems is empty when all is well", () => {
  assert.deepEqual(attentionItems({ run: run(), missing: [] }), []);
  assert.deepEqual(attentionItems({ run: null, missing: [] }), []);
  assert.deepEqual(attentionItems({ run: undefined }), []);
});

test("attentionItems lists missing notes even before the maid has run", () => {
  const items = attentionItems({ run: null, missing: [{ id: "a", paths: ["x.md"] }, { id: "b", paths: ["y.md", "y.pdf"] }] });
  assert.deepEqual(items.map((i) => [i.kind, i.id]), [["missing", "a"], ["missing", "b"]]);
  assert.deepEqual(items[1].paths, ["y.md", "y.pdf"]);
});

test("attentionItems turns held-back jobs into one item, whatever number were held", () => {
  const both = attentionItems({ run: run({ history: job({ held_back: true, would_delete: 60, total: 100 }), trash: job({ held_back: true, would_delete: 6, total: 8 }) }) });
  assert.equal(both.length, 1);
  assert.equal(both[0].kind, "held");
  assert.deepEqual(both[0].jobs.map((j) => [j.job, j.wouldDelete, j.total]), [["history", 60, 100], ["trash", 6, 8]]);
  const one = attentionItems({ run: run({ trash: job({ held_back: true, would_delete: 5, total: 9 }) }) });
  assert.deepEqual(one[0].jobs.map((j) => j.job), ["trash"]);
});

test("attentionItems reports a wrong clock and failures", () => {
  const items = attentionItems({ run: run({ time_check: "wrong", clock_offset_seconds: 7200, history: job({ error: "disk full" }) }), missing: [{ id: "a", paths: ["x.md"] }] });
  assert.deepEqual(items.map((i) => i.kind), ["missing", "clock", "error"]);
  assert.equal(items[1].offsetSeconds, 7200);
  assert.deepEqual([items[2].job, items[2].message], ["history", "disk full"]);
  // The time server being unreachable is not something to act on.
  assert.deepEqual(attentionItems({ run: run({ time_check: "unreachable" }) }), []);
});

test("attentionItems lists device changes that could not be finished", () => {
  const items = attentionItems({ run: null, missing: [{ id: "a", paths: ["x.md"] }], failed: [{ name: "n.json", note_path: "x/b.md", at: "2026-11-25T10:00:00Z", reason: "gone" }, { name: "m.json", at: "2026-11-25T11:00:00Z", reason: "unreadable" }] });
  assert.deepEqual(items.map((i) => i.kind), ["missing", "failed", "failed"]);
  assert.deepEqual([items[1].name, items[1].notePath, items[1].reason], ["n.json", "x/b.md", "gone"]);
  assert.equal(items[2].notePath, "");
});

test("describeOffset says which way and how far", () => {
  assert.equal(describeOffset(600), "about 10 minutes behind");
  assert.equal(describeOffset(-600), "about 10 minutes ahead of");
  assert.equal(describeOffset(60), "about 1 minute behind");
  assert.equal(describeOffset(3 * 3600), "about 3 hours behind");
  assert.equal(describeOffset(-5 * 3600), "about 5 hours ahead of");
  assert.equal(describeOffset(86400 * 400), "about 400 days behind");
  assert.equal(describeOffset(-86400 * 2.2), "about 2 days ahead of");
});

test("describeJob in plain words", () => {
  assert.equal(describeJob("history", job({ deleted: 12 })), "Deleted 12 old versions.");
  assert.equal(describeJob("history", job({ deleted: 1 })), "Deleted 1 old version.");
  assert.equal(describeJob("trash", job({ deleted: 3 })), "Deleted 3 items from the trash.");
  assert.equal(describeJob("trash", job({ deleted: 1 })), "Deleted 1 item from the trash.");
  assert.equal(describeJob("history", job()), "Nothing to delete.");
  assert.equal(describeJob("trash", job({ held_back: true, would_delete: 6, total: 8 })), "Held back: it would delete 6 of 8.");
  assert.equal(describeJob("trash", job({ skipped: "Trash is kept forever" })), "Skipped: trash is kept forever.");
  assert.equal(describeJob("history", job({ skipped: "the clock looks wrong" })), "Skipped: the clock looks wrong.");
  assert.equal(describeJob("history", job({ error: "boom" })), "Failed: boom");
});

test("describeTimeCheck covers every result", () => {
  assert.match(describeTimeCheck(run({ time_check: "ok" })), /matches/);
  assert.match(describeTimeCheck(run({ time_check: "wrong", clock_offset_seconds: 3600 })), /about 1 hour behind the time server/);
  assert.match(describeTimeCheck(run({ time_check: "unreachable" })), /could not be reached/);
  assert.match(describeTimeCheck(run({ time_check: "off" })), /Turn it on in Settings/);
  assert.equal(describeTimeCheck(null), "");
});

test("baseName", () => {
  assert.equal(baseName("a/b/c.md"), "c.md");
  assert.equal(baseName("c.md"), "c.md");
});

import { toForm, toSettings, sameForm, DEFAULT_TIME_SERVER } from "../ui/static/js/settingsform.js";

const stored = (o = {}) => ({
  hub: { name: "Home", history: { on: true, window_minutes: 30, max_versions: 0, max_age_days: 0, per_period: 0, period: "" }, trash_retention_days: 30, time_check: { on: false, server: "pool.ntp.org" }, ...o.hub },
  device: { name: "", ...o.device },
});

test("toForm shows the stored settings as the inputs hold them", () => {
  const f = toForm(stored());
  assert.equal(f.hubName, "Home");
  assert.equal(f.historyOn, true);
  assert.equal(f.windowMinutes, "30");
  assert.equal(f.maxVersions, "0");
  assert.equal(f.period, "day"); // a sensible choice for the dropdown while the limit is off
  assert.equal(f.trashDays, "30");
  assert.equal(f.timeServer, "pool.ntp.org");
  assert.equal(f.deviceMode, "hub");
});

test("toForm reads the device's own trash retention", () => {
  assert.deepEqual([toForm(stored({ device: { trash_retention_days: 7 } })).deviceMode, toForm(stored({ device: { trash_retention_days: 7 } })).deviceDays], ["days", "7"]);
  assert.equal(toForm(stored({ device: { trash_retention_days: 0 } })).deviceMode, "forever");
  assert.equal(toForm(stored({ device: { trash_retention_days: null } })).deviceMode, "hub");
  assert.equal(toForm(stored({ hub: { time_check: { on: false, server: "" } } })).timeServer, DEFAULT_TIME_SERVER);
});

test("an unchanged form turns back into the same settings", () => {
  const s = stored();
  const out = toSettings(toForm(s));
  assert.deepEqual(out.hub, s.hub);
  assert.deepEqual(out.device, { name: "", trash_retention_days: null });
});

test("toSettings turns text into numbers, with blank meaning off", () => {
  const f = { ...toForm(stored()), windowMinutes: " 45 ", maxVersions: "", maxAgeDays: "90", perPeriod: "2", period: "week", trashDays: "0" };
  const { hub, errors } = toSettings(f);
  assert.equal(errors, undefined);
  assert.deepEqual(hub.history, { on: true, window_minutes: 45, max_versions: 0, max_age_days: 90, per_period: 2, period: "week" });
  assert.equal(hub.trash_retention_days, 0);
});

test("the period is only sent while a per-period limit is on", () => {
  const off = toSettings({ ...toForm(stored()), perPeriod: "0", period: "month" });
  assert.equal(off.hub.history.period, "");
  const on = toSettings({ ...toForm(stored()), perPeriod: "1", period: "month" });
  assert.equal(on.hub.history.period, "month");
});

test("toSettings names the field and says what is wrong", () => {
  for (const bad of ["abc", "1.5", "-3", "1e3", "12 months", "٣"]) {
    const { errors } = toSettings({ ...toForm(stored()), trashDays: bad });
    assert.match(errors.trashDays, /whole number/, bad);
  }
  assert.match(toSettings({ ...toForm(stored()), trashDays: "36501" }).errors.trashDays, /between 0 and 36500/);
  assert.match(toSettings({ ...toForm(stored()), windowMinutes: "10081" }).errors.windowMinutes, /between 0 and 10080/);
  assert.match(toSettings({ ...toForm(stored()), maxVersions: "100001" }).errors.maxVersions, /between 0 and 100000/);
  assert.match(toSettings({ ...toForm(stored()), perPeriod: "1001" }).errors.perPeriod, /between 0 and 1000/);
});

test("toSettings reports every problem at once", () => {
  const { errors } = toSettings({ ...toForm(stored()), trashDays: "x", windowMinutes: "y", hubName: "a".repeat(101) });
  assert.deepEqual(Object.keys(errors).sort(), ["hubName", "trashDays", "windowMinutes"]);
});

test("names are trimmed and counted in characters, not bytes", () => {
  assert.equal(toSettings({ ...toForm(stored()), hubName: "  Home  " }).hub.name, "Home");
  assert.equal(toSettings({ ...toForm(stored()), hubName: "日".repeat(100) }).errors, undefined);
  assert.ok(toSettings({ ...toForm(stored()), hubName: "日".repeat(101) }).errors.hubName);
});

test("the time check needs a server only while it is on", () => {
  assert.ok(toSettings({ ...toForm(stored()), timeOn: true, timeServer: "  " }).errors.timeServer);
  assert.equal(toSettings({ ...toForm(stored()), timeOn: false, timeServer: "" }).hub.time_check.server, "");
  assert.equal(toSettings({ ...toForm(stored()), timeOn: true, timeServer: " time.local:123 " }).hub.time_check.server, "time.local:123");
  for (const bad of ["http://x/y", "pool ntp org", "a/b", "ntp;rm"]) {
    assert.ok(toSettings({ ...toForm(stored()), timeOn: true, timeServer: bad }).errors.timeServer, bad);
  }
  for (const ok of ["pool.ntp.org", "192.168.1.1", "::1", "[fe80::1]:123", "time.local:123"]) {
    assert.equal(toSettings({ ...toForm(stored()), timeOn: true, timeServer: ok }).errors, undefined, ok);
  }
});

test("device trash retention: the atrium's, a number of days, or forever", () => {
  const base = toForm(stored());
  assert.equal(toSettings({ ...base, deviceMode: "hub" }).device.trash_retention_days, null);
  assert.equal(toSettings({ ...base, deviceMode: "forever" }).device.trash_retention_days, 0);
  assert.equal(toSettings({ ...base, deviceMode: "days", deviceDays: "14" }).device.trash_retention_days, 14);
  assert.ok(toSettings({ ...base, deviceMode: "days", deviceDays: "nope" }).errors.deviceDays);
  // A bad number in the hidden days box does not block saving when it is not in use.
  assert.equal(toSettings({ ...base, deviceMode: "hub", deviceDays: "nope" }).errors, undefined);
});

test("sameForm notices any change", () => {
  const a = toForm(stored());
  assert.equal(sameForm(a, { ...a }), true);
  assert.equal(sameForm(a, { ...a, historyOn: false }), false);
  assert.equal(sameForm(a, { ...a, hubName: "Other" }), false);
});

import { describePairing, minutesLeft } from "../ui/static/js/pairingtext.js";

test("minutesLeft rounds down and never says zero", () => {
  const now = new Date("2026-11-25T12:00:00Z");
  assert.equal(minutesLeft("2026-11-25T12:05:00Z", now), "5 minutes");
  assert.equal(minutesLeft("2026-11-25T12:01:30Z", now), "1 minute");
  assert.equal(minutesLeft("2026-11-25T12:00:30Z", now), "less than a minute");
  assert.equal(minutesLeft("2026-11-25T11:59:00Z", now), "less than a minute");
});

test("describePairing says where pairing stands, in plain words", () => {
  const now = new Date("2026-11-25T12:00:00Z");
  assert.deepEqual(describePairing({ phase: "idle" }), { headline: "", detail: "" });
  assert.match(describePairing({ phase: "waiting", expires: "2026-11-25T12:04:10Z" }, now).detail, /work once, for the next 4 minutes/);
  assert.equal(describePairing({ phase: "checking", device_name: "Laptop" }).headline, "“Laptop” is trying the words…");
  const cmp = (o) => describePairing({ phase: "comparing", device_name: "Laptop", ...o });
  assert.equal(cmp({}).headline, "The words matched. “Laptop” wants to join.");
  assert.match(cmp({}).detail, /They don't match/);
  assert.match(cmp({ atrium_approved: true }).detail, /Now confirm on the device/);
  assert.match(cmp({ device_approved: true }).detail, /confirm here too/);
  assert.equal(describePairing({ phase: "paired", device_name: "Laptop" }).headline, "“Laptop” is paired and can sync now.");
  const failed = describePairing({ phase: "failed", reason: "The words did not match. Start again for new words." });
  assert.equal(failed.headline, "The words did not match. Start again for new words.");
  assert.equal(failed.detail, "Nothing was saved.");
  assert.equal(describePairing({ phase: "expired" }).headline, "The pairing ended.");
});
