// Tells ChromeDeck when a text field is tapped, or loses focus, so that Android's
// on-screen keyboard opens and closes. Only taps count: fields that pages focus by
// themselves, such as a search box, do not open the keyboard.
(() => {
  const noText = new Set(["button", "checkbox", "color", "file", "hidden", "image",
                          "radio", "range", "reset", "submit"]);
  function editable(el) {
    if (!el || el.disabled || el.readOnly) return false;
    if (el.isContentEditable) return true;
    if (el.tagName === "TEXTAREA") return true;
    if (el.tagName === "INPUT") return !noText.has((el.type || "text").toLowerCase());
    return false;
  }
  function deepActive() {
    let el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
    return el;
  }
  function tell(keyboard) {
    try { chrome.runtime.sendMessage({ keyboard }); } catch (e) { /* extension reloaded */ }
  }
  // With touch, a field takes the focus only after the finger lifts, so a focus that
  // follows a tap closely counts as the tap; a click covers a field that has it already.
  let lastTap = 0;
  addEventListener("pointerdown", () => { lastTap = Date.now(); }, true);
  addEventListener("pointerup", () => { lastTap = Date.now(); }, true);
  addEventListener("focusin", () => {
    if (Date.now() - lastTap < 1000 && editable(deepActive())) tell("show");
  }, true);
  addEventListener("click", () => {
    if (editable(deepActive())) tell("show");
  }, true);
  addEventListener("focusout", () => {
    setTimeout(() => { if (document.hasFocus() && !editable(deepActive())) tell("hide"); }, 0);
  }, true);
})();
