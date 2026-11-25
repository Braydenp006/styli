const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

// "just now", "2 min ago", "3 h ago", "yesterday", "Tuesday", or a date.
export function relativeTime(date, now = new Date()) {
  const d = new Date(date);
  if (Number.isNaN(d.getTime())) return "";
  const seconds = Math.round((now - d) / 1000);
  if (seconds < 45) return "just now";
  if (seconds < 3600) return `${Math.max(1, Math.round(seconds / 60))} min ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} h ago`;
  const days = Math.round(seconds / 86400);
  if (days < 7) return rtf.format(-days, "day");
  return d.toLocaleDateString(undefined, { year: d.getFullYear() === now.getFullYear() ? undefined : "numeric", month: "short", day: "numeric" });
}

export function fullDate(date) {
  const d = new Date(date);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleString();
}

// How long until a moment in the future: "tomorrow", "in 3 days", "in 5 h".
// Empty when it has already passed.
export function relativeFuture(date, now = new Date()) {
  const d = new Date(date);
  if (Number.isNaN(d.getTime())) return "";
  const seconds = Math.round((d - now) / 1000);
  if (seconds <= 0) return "";
  if (seconds < 3600) return "in less than an hour";
  if (seconds < 86400) return `in ${Math.round(seconds / 3600)} h`;
  return rtf.format(Math.round(seconds / 86400), "day");
}
