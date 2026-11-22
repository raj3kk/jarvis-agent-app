// jarvis-bridge background script — GeckoView Phase 0 spike ONLY.
// Two jobs:
//  1. Cookie ops (browser.cookies is background-only): content.js relays
//     {type:"cookie-get"|"cookie-set"} here via runtime.sendMessage;
//     this script runs the cookies API and returns the result, which the
//     content script forwards to Kotlin over the native port.
//  2. Fallback relay: if a content script could not open a native port
//     (connectNative), its one-shot sendMessage is forwarded here with
//     sendNativeMessage, and Kotlin's reply is returned to the sender.
// No solver code, no token handling, no network calls of its own.
browser.runtime.onMessage.addListener(function (msg, sender) {
  if (!msg || typeof msg !== "object") return Promise.resolve(null);

  if (msg.type === "cookie-get") {
    return browser.cookies.getAll({ url: msg.url }).then(function (cookies) {
      return { type: "cookie-get-result", id: msg.id, url: msg.url, ok: true, cookies: cookies };
    }, function (err) {
      return { type: "cookie-get-result", id: msg.id, url: msg.url, ok: false, error: String(err && err.message || err) };
    });
  }

  if (msg.type === "cookie-set") {
    return browser.cookies.set({ url: msg.url, name: msg.name, value: msg.value }).then(function (cookie) {
      return { type: "cookie-set-result", id: msg.id, url: msg.url, ok: true, cookie: cookie };
    }, function (err) {
      return { type: "cookie-set-result", id: msg.id, url: msg.url, ok: false, error: String(err && err.message || err) };
    });
  }

  // Fallback one-shot relay for extraction/captcha messages.
  return browser.runtime.sendNativeMessage("jarvis-bridge", msg);
});
