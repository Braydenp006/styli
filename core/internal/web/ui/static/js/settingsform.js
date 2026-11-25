// The settings form and the settings the hub stores, converted both ways.
// Pure functions, so they are tested without a browser.
//
// The form keeps everything as text and booleans, the way the inputs hold it.
// toSettings checks the text and turns it into what the hub accepts, or says
// what is wrong with which field. The limits mirror the hub's own checks, so a
// mistake is caught here with a friendly message before the hub has to refuse it.

// [smallest, largest, what it is, the unit]
const LIMITS = {
  windowMinutes: [0, 10080, "How long to combine saves", " minutes"],
  maxVersions: [0, 100000, "The most versions to keep", ""],
  maxAgeDays: [0, 36500, "The number of days", " days"],
  perPeriod: [0, 1000, "The number of versions per period", ""],
  trashDays: [0, 36500, "How long to keep the trash", " days"],
  deviceDays: [0, 36500, "How long to keep the trash on this device", " days"],
};

export const NAME_MAX = 100;
export const DEFAULT_TIME_SERVER = "pool.ntp.org";

// What the hub returned (GET /api/settings) as form values.
export function toForm(s) {
  const h = s.hub;
  const own = s.device.trash_retention_days;
  return {
    hubName: h.name,
    historyOn: h.history.on,
    windowMinutes: String(h.history.window_minutes),
    maxVersions: String(h.history.max_versions),
    maxAgeDays: String(h.history.max_age_days),
    perPeriod: String(h.history.per_period),
    period: h.history.period || "day",
    trashDays: String(h.trash_retention_days),
    timeOn: h.time_check.on,
    timeServer: h.time_check.server || DEFAULT_TIME_SERVER,
    deviceName: s.device.name,
    deviceMode: own == null ? "hub" : own === 0 ? "forever" : "days",
    deviceDays: own == null || own === 0 ? "30" : String(own),
  };
}

function wholeNumber(text, key, errors) {
  const [min, max, what, unit] = LIMITS[key];
  const t = String(text).trim();
  if (t === "") return 0; // an empty optional limit means off
  if (!/^\d+$/.test(t)) {
    errors[key] = `${what}: enter a whole number, like 30.`;
    return 0;
  }
  const n = Number(t);
  if (n < min || n > max) errors[key] = `${what} must be between ${min} and ${max}${unit}.`;
  return n;
}

// Returns { hub, device } to send, or { errors } keyed by form field.
export function toSettings(f) {
  const errors = {};
  const name = f.hubName.trim();
  const deviceName = f.deviceName.trim();
  if ([...name].length > NAME_MAX) errors.hubName = `The hub name can have at most ${NAME_MAX} characters.`;
  if ([...deviceName].length > NAME_MAX) errors.deviceName = `The device name can have at most ${NAME_MAX} characters.`;

  const windowMinutes = wholeNumber(f.windowMinutes, "windowMinutes", errors);
  const maxVersions = wholeNumber(f.maxVersions, "maxVersions", errors);
  const maxAgeDays = wholeNumber(f.maxAgeDays, "maxAgeDays", errors);
  const perPeriod = wholeNumber(f.perPeriod, "perPeriod", errors);
  const trashDays = wholeNumber(f.trashDays, "trashDays", errors);

  const server = f.timeServer.trim();
  if (f.timeOn && !server) errors.timeServer = "Enter the time server to check against, or turn the time check off.";
  if (server && !/^[A-Za-z0-9.\-:\[\]]{1,255}$/.test(server)) errors.timeServer = "A time server is a name or address like pool.ntp.org, with an optional :port.";

  let deviceDays = null;
  if (f.deviceMode === "days") deviceDays = wholeNumber(f.deviceDays, "deviceDays", errors);
  if (f.deviceMode === "forever") deviceDays = 0;

  if (Object.keys(errors).length) return { errors };
  return {
    hub: {
      name,
      history: {
        on: f.historyOn,
        window_minutes: windowMinutes,
        max_versions: maxVersions,
        max_age_days: maxAgeDays,
        per_period: perPeriod,
        period: perPeriod > 0 ? f.period : "",
      },
      trash_retention_days: trashDays,
      time_check: { on: f.timeOn, server },
    },
    device: { name: deviceName, trash_retention_days: deviceDays },
  };
}

export function sameForm(a, b) {
  return Object.keys(a).every((k) => a[k] === b[k]);
}
