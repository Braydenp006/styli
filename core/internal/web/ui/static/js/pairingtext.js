// What the Devices card says at each step of pairing. Pure, so it is tested
// without a browser.

// "4 minutes", "1 minute", "less than a minute"
export function minutesLeft(expires, now = new Date()) {
  const ms = new Date(expires) - now;
  if (!(ms > 60000)) return "less than a minute";
  const n = Math.floor(ms / 60000);
  return `${n} minute${n === 1 ? "" : "s"}`;
}

// { headline, detail } for a pairing status from /api/pairing.
export function describePairing(st, now = new Date()) {
  const name = st.device_name ? `“${st.device_name}”` : "The device";
  switch (st.phase) {
    case "idle":
      return { headline: "", detail: "" };
    case "waiting":
      return { headline: "", detail: `The words work once, for the next ${minutesLeft(st.expires, now)}. If they are typed wrong, new words are needed.` };
    case "checking":
      return { headline: `${name} is trying the words…`, detail: "" };
    case "comparing":
      return {
        headline: `The words matched. ${name} wants to join.`,
        detail: st.atrium_approved
          ? "You confirmed here. Now confirm on the device."
          : st.device_approved
            ? "The device has confirmed. If the symbols match, confirm here too."
            : "Confirm on both devices. If the symbols differ, someone may be trying to get in: choose “They don't match”.",
      };
    case "paired":
      return { headline: `${name} is paired and can sync now.`, detail: "" };
    default: // failed, cancelled, expired
      return { headline: st.reason || "The pairing ended.", detail: "Nothing was saved." };
  }
}
