// jarvis-bridge content script — Jarvis 1.13.0 (GeckoView browser).
// Talks ONLY to the background script (runtime.sendMessage /
// runtime.onMessage). It NEVER calls connectNative: the content-script
// native port died silently on the phone in the Phase 0 spike (S-2a/S-2b);
// the background script owns the native port.
// Fixed operations only: extraction (title + innerText <= 2000 chars),
// captcha detection with today's WebView selectors (DOM markers only —
// NEVER a solver, never a challenge token).
// Every response carries the URL it was extracted from (B-C4 guard:
// Kotlin discards any response whose URL != the requested URL).
// No generic "run any JS string" bridge exists by design.
(function () {
  function captchaCheck() {
    function q(s) { try { return !!document.querySelector(s); } catch (e) { return false; } }
    if (q('iframe[src*="challenges.cloudflare.com"],[class*="cf-turnstile"]')) return "turnstile";
    if (q('iframe[src*="recaptcha"],.g-recaptcha')) return "recaptcha";
    if (q('iframe[src*="hcaptcha"],.h-captcha')) return "hcaptcha";
    if (q('[data-sitekey]')) return "unknown";
    return "none";
  }

  function extract(id) {
    var text = "";
    try { text = (document.body ? document.body.innerText : "") || ""; } catch (e) { text = ""; }
    var msg = {
      type: "extract",
      url: location.href,
      title: document.title || "",
      text: text.slice(0, 2000),
      textLen: text.length,
      captcha: captchaCheck()
    };
    if (id) msg.id = id;
    return msg;
  }

  function postToBackground(obj) {
    try { browser.runtime.sendMessage(obj); } catch (e) { /* no channel */ }
  }

  function handleRequest(msg) {
    if (!msg || typeof msg !== "object") return;
    if (msg.type === "extract") {
      postToBackground(extract(msg.id));
    } else if (msg.type === "captcha") {
      var m = { type: "captcha", url: location.href, captcha: captchaCheck() };
      if (msg.id) m.id = msg.id;
      postToBackground(m);
    }
    // Cookie ops are handled in background.js directly (browser.cookies
    // is background-only); unknown types are ignored (enumerable surface
    // — no generic eval).
  }

  browser.runtime.onMessage.addListener(handleRequest);

  // Auto-extract on load: proves the content script ran, via the
  // background relay (background.js forwards to the native port).
  try { postToBackground(extract(null)); } catch (e) { /* no channel */ }
})();
