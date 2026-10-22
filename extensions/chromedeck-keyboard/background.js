// Passes text field taps from the pages to ChromeDeck through its native host.
let port = null;
function send(message) {
  if (!port) {
    port = chrome.runtime.connectNative("com.chromedeck.keyboard");
    port.onDisconnect.addListener(() => { port = null; });
  }
  port.postMessage(message);
}
chrome.runtime.onMessage.addListener((message) => {
  if (message && (message.keyboard === "show" || message.keyboard === "hide"))
    send({ keyboard: message.keyboard });
});
