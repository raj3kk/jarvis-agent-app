// jarvis-bridge background script — Jarvis 1.13.0 (GeckoView browser).
// Owns the SINGLE native port (Mozilla's documented pattern: the background
// script connects to the native app; content scripts NEVER call
// connectNative — the content-script native port died silently on the phone
// in the Phase 0 spike (S-2a/S-2b, two bridge designs; see
// expansion/browser/BRIDGE_ROOT_CAUSE_AUDIT.md).
// Jobs:
//  1. Native port: connectNative("jarvis-bridge") at startup; onMessage
//     routes app requests to tabs; onDisconnect retries once after 1 s.
//  2. bg-alive ping: one-shot sendNativeMessage at startup so Kotlin can
//     prove the background script runs and the one-shot path works, even
//     if the port never attaches.
//  3. Content relay: content.js talks only via runtime.sendMessage /
//     runtime.onMessage. Auto-extracts and replies are forwarded to the
//     native port (or via one-shot sendNativeMessage if the port is down).
//  4. Cookies: browser.cookies is background-only — cookie-get/set arrive
//     on the native port and are answered here, with no content relay.
// No solver code, no token handling, no network calls of its own.
// VERSION RULE (C1 mitigation): this manifest's "version" MUST equal the
// app versionName on EVERY release (CI gate enforces it). A version bump
// forces ADDON_UPGRADE + updateReason, which makes Gecko build the
// background directly instead of relying on the APP_STARTUP fallback
// that silently never started it in the spike's persisted runs.

var APP = "jarvis-bridge";
var nativePort = null;
var tabByUrl = {};   // url -> tabId, learned from sender.tab on every content message
var lastTabId = null;

function forwardToNative(msg) {
  if (nativePort) {
    try { nativePort.postMessage(msg); return true; } catch (e) { nativePort = null; }
  }
  try { browser.runtime.sendNativeMessage(APP, msg); return true; } catch (e2) { return false; }
}

function connectNativePort() {
  try {
    var port = browser.runtime.connectNative(APP);
    port.onMessage.addListener(function (msg) {
      // App -> content request (extract / captcha) or cookie op.
      if (!msg || typeof msg !== "object") return;
      if (msg.type === "cookie-get") {
        browser.cookies.getAll({ url: msg.url }).then(function (cookies) {
          forwardToNative({ type: "cookie-get-result", id: msg.id, url: msg.url, ok: true, cookies: cookies });
        }, function (err) {
          forwardToNative({ type: "cookie-get-result", id: msg.id, url: msg.url, ok: false, error: String(err && err.message || err) });
        });
        return;
      }
      if (msg.type === "cookie-set") {
        browser.cookies.set({ url: msg.url, name: msg.name, value: msg.value }).then(function (cookie) {
          forwardToNative({ type: "cookie-set-result", id: msg.id, url: msg.url, ok: true, cookie: cookie });
        }, function (err) {
          forwardToNative({ type: "cookie-set-result", id: msg.id, url: msg.url, ok: false, error: String(err && err.message || err) });
        });
        return;
      }
      // extract / captcha: route to the tab that owns this URL.
      var tabId = (msg.url && tabByUrl[msg.url]) || lastTabId;
      if (tabId == null) {
        // No tab known yet — send to the first tab we can find.
        browser.tabs.query({}).then(function (tabs) {
          if (tabs && tabs.length) browser.tabs.sendMessage(tabs[0].id, msg);
        });
        return;
      }
      browser.tabs.sendMessage(tabId, msg);
    });
    port.onDisconnect.addListener(function () {
      nativePort = null;
      // One reconnect attempt after 1 s; if it fails, the one-shot
      // fallback in forwardToNative keeps messages flowing.
      setTimeout(function () {
        if (!nativePort) connectNativePort();
      }, 1000);
    });
    nativePort = port;
  } catch (e) {
    nativePort = null;
  }
}

browser.runtime.onMessage.addListener(function (msg, sender) {
  if (!msg || typeof msg !== "object") return Promise.resolve(null);
  if (sender && sender.tab && sender.tab.id != null) {
    lastTabId = sender.tab.id;
    if (msg.url) tabByUrl[msg.url] = sender.tab.id;
  }
  // Content -> native: auto-extracts and replies to app requests.
  forwardToNative(msg);
  return Promise.resolve(null);
});

// Startup: open the native port, then prove the one-shot path with bg-alive.
connectNativePort();
try {
  browser.runtime.sendNativeMessage(APP, { type: "bg-alive", url: "", at: Date.now() });
} catch (e) { /* port path is the primary channel; bg-alive is best-effort */ }
