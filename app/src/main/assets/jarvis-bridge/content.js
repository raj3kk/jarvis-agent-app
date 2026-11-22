// jarvis-bridge content script — GeckoView Phase 0 spike ONLY.
// Fixed operations only: extraction (title + innerText <= 2000 chars),
// captcha detection with today's WebView selectors (DOM markers only —
// NEVER a solver, never a challenge token), and cookie get/set relayed
// via the background script (browser.cookies is background-only).
// Every response carries the URL it was extracted from (B-C4 guard:
// Kotlin discards any response whose URL != the requested URL).
// No generic "run any JS string" bridge exists by design.
(function () {
  var APP = "jarvis-bridge";

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

  var port = null;

  function postToNative(obj) {
    if (port) {
      try { port.postMessage(obj); return; } catch (e) { /* fall through */ }
    }
    try { browser.runtime.sendMessage(obj); } catch (e2) { /* no channel */ }
  }

  // Cookie ops must run in the background script (browser.cookies is
  // not available to content scripts). Relay via runtime.sendMessage;
  // background.js returns the result, which we forward to Kotlin.
  function handleCookieOp(msg) {
    try {
      browser.runtime.sendMessage(msg).then(function (result) {
        postToNative(result || { type: msg.type + "-result", id: msg.id, ok: false, error: "empty-result" });
      }, function (err) {
        postToNative({ type: msg.type + "-result", id: msg.id, ok: false, error: String(err && err.message || err) });
      });
    } catch (e) {
      postToNative({ type: msg.type + "-result", id: msg.id, ok: false, error: String(e && e.message || e) });
    }
  }

  function handleRequest(msg) {
    if (!msg || typeof msg !== "object") return;
    if (msg.type === "extract") {
      postToNative(extract(msg.id));
    } else if (msg.type === "captcha") {
      var m = { type: "captcha", url: location.href, captcha: captchaCheck() };
      if (msg.id) m.id = msg.id;
      postToNative(m);
    } else if (msg.type === "cookie-get" || msg.type === "cookie-set") {
      handleCookieOp(msg);
    }
    // Unknown types are ignored (enumerable surface — no generic eval).
  }

  // Primary channel: direct native-messaging port (needs the
  // nativeMessagingFromContent permission in manifest.json).
  try {
    port = browser.runtime.connectNative(APP);
    port.onMessage.addListener(handleRequest);
    port.postMessage(extract(null));
  } catch (e) {
    // Fallback: one-shot via the background relay (background.js
    // forwards with sendNativeMessage; Kotlin answers via onMessage).
    try { browser.runtime.sendMessage(extract(null)); } catch (e2) { /* no channel */ }
  }
})();
