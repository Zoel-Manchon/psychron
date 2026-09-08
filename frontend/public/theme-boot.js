// Runs before the first paint so a reader who chose dark never sees a white
// flash on reload. It lives in a file rather than inline in the document
// because the panel is served under a Content-Security-Policy of default-src
// 'self': an inline script is refused, and the alternative — allowing it by
// hash — means the policy silently breaks the next time this changes by a byte.
try {
  var t = localStorage.getItem("psychron.theme");
  document.documentElement.dataset.theme = t === "dark" ? "dark" : "light";
} catch (e) {
  document.documentElement.dataset.theme = "light";
}
