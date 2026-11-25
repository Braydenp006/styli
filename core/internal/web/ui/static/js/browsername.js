// A first guess at a browser's name, from its user agent, for the person to
// change: "Safari on iPhone", "Firefox on Linux". Only shown in Settings.
export function guessBrowserName(ua = "") {
  const browser = /Edg\//.test(ua) ? "Edge"
    : /Firefox\/|FxiOS\//.test(ua) ? "Firefox"
      : /Chrome\/|CriOS\//.test(ua) ? "Chrome"
        : /Safari\//.test(ua) ? "Safari"
          : "Browser";
  const device = /iPhone/.test(ua) ? "iPhone"
    : /iPad/.test(ua) ? "iPad"
      : /Android/.test(ua) ? "Android"
        : /Mac OS X|Macintosh/.test(ua) ? "Mac"
          : /Windows/.test(ua) ? "Windows"
            : /Linux|X11/.test(ua) ? "Linux"
              : "";
  return device ? `${browser} on ${device}` : browser;
}
