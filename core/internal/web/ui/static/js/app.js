// The first thing the page runs: it decides between signing in and the app.
// The atrium's web UI is for trusted browsers only; the page itself holds no
// notes, so it loads for anyone and asks the atrium who this is first.
import { api } from "./api.js";
import { parseRoute } from "./router.js";
import { showJoin, showSignedOut } from "./views/signin.js";

const root = document.getElementById("app");
const route = parseRoute(location.hash);

// A link pasted into a tab that already shows the atrium changes only the part
// after "#", which loads nothing: start the page over, so the link opens.
window.addEventListener("hashchange", () => {
  if (parseRoute(location.hash).name === "join") location.reload();
});

if (route.name === "join") {
  showJoin(root, route.token);
} else {
  let signedIn = true;
  try {
    await api.get("/api/session");
  } catch (err) {
    // 401: not signed in. Anything else (a device, whose page needs no
    // sign-in, or an atrium that can't be reached) goes on to the app, which
    // shows its own errors.
    signedIn = err.code !== "login_required";
  }
  if (signedIn) {
    // Signed out while using the app (removed from another browser, or the
    // session ended): every later call fails, so show how to sign in again.
    window.addEventListener("styli:signed-out", () => showSignedOut(root), { once: true });
    await import("./shell.js");
  } else {
    showSignedOut(root);
  }
}
