// Hold Alt + Shift in a text box, speak, let go: Saylo types the cleaned text into that box.
// Runs in every page and frame (and on the setup page's try-it box). A small pill shows the status.
(() => {
  if (window.__saylo) return;
  window.__saylo = true;

  let alt = false, shift = false, other = false, recording = false, target = null, pill = null, hideTimer = null;

  function focused() {
    let el = document.activeElement;
    while (el?.shadowRoot?.activeElement) el = el.shadowRoot.activeElement;
    return el;
  }
  function editable(el) {
    if (!el) return false;
    if (el.isContentEditable || el.tagName === "TEXTAREA") return !el.readOnly && !el.disabled;
    return el.tagName === "INPUT" && /^(text|search|email|url|tel|)$/.test(el.type) && !el.readOnly && !el.disabled;
  }

  // ---------- status pill (shadow DOM so page styles can't touch it) ----------
  function show(text, kind = "busy", ms = 0) {
    if (!pill) {
      const host = document.createElement("saylo-pill");
      const root = host.attachShadow({mode: "closed"});
      root.innerHTML = `<style>
        div{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:2147483647;display:flex;gap:10px;align-items:center;
            background:#16141B;color:#F4F1EC;font:500 14px "Segoe UI",system-ui,sans-serif;padding:10px 16px 10px 12px;border-radius:999px;
            box-shadow:0 12px 32px rgba(0,0,0,.28);pointer-events:none;transition:opacity .2s}
        i{display:flex;gap:3px;align-items:center;height:14px} i b{width:3px;border-radius:2px;background:#7FE0C2;animation:w .9s ease-in-out infinite}
        i b:nth-child(1){height:7px} i b:nth-child(2){height:14px;animation-delay:.12s} i b:nth-child(3){height:10px;animation-delay:.24s}
        .busy i b{animation-duration:.5s} .warn i b{background:#F4A259;animation:none}
        @keyframes w{50%{transform:scaleY(.4)}}
      </style><div><i><b></b><b></b><b></b></i><span></span></div>`;
      pill = {host, box: root.querySelector("div"), label: root.querySelector("span")};
    }
    clearTimeout(hideTimer);
    pill.box.className = kind;
    pill.label.textContent = text;
    if (!pill.host.isConnected) document.documentElement.appendChild(pill.host);
    if (ms) hideTimer = setTimeout(hide, ms);
  }
  function hide() { pill?.host.remove(); }

  // ---------- typing the result ----------
  function insert(el, text) {
    el.focus();
    // insertText keeps the page's undo history and fires the events editors (React, Gmail, Slack) listen for.
    if (document.execCommand("insertText", false, text)) return true;
    if ("value" in el) {
      el.setRangeText(text, el.selectionStart ?? el.value.length, el.selectionEnd ?? el.value.length, "end");
      el.dispatchEvent(new Event("input", {bubbles: true}));
      return true;
    }
    return false;
  }

  // ---------- recording ----------
  async function send(type) {
    try { return await chrome.runtime.sendMessage({type}); }
    catch { return {error: "Saylo was updated. Reload this page to use it."}; }
  }
  async function start() {
    target = focused();
    if (!editable(target)) return show("Click into a text box first", "warn", 2200);
    recording = true;
    show("Listening…", "rec");
    const r = await send("start");
    if (r?.error) { recording = false; show(r.error, "warn", 3500); }
  }
  async function stop() {
    recording = false;
    show("Writing…", "busy");
    const r = await send("stop");
    if (r?.text) {
      if (insert(target, r.text)) hide();
      else { await navigator.clipboard.writeText(r.text).catch(() => {}); show("Copied. Press Ctrl + V to paste", "warn", 3500); }
    } else if (r?.error) show(r.error, "warn", 3500);
    else show(r?.skipped || "Didn’t catch that. Try again.", "warn", 2200);
  }
  function cancel() {
    recording = false;
    send("cancel");
    hide();
  }

  // Alt + Shift held together (and nothing else) records; releasing either one stops.
  addEventListener("keydown", e => {
    if (e.key === "Alt") alt = true;
    else if (e.key === "Shift") shift = true;
    else { other = true; if (recording) cancel(); return; }
    if (alt && shift && !other && !recording && !e.repeat) start();
    if (recording) e.preventDefault();
  }, true);
  addEventListener("keyup", e => {
    if (e.key === "Alt") alt = false;
    else if (e.key === "Shift") shift = false;
    if (recording && (!alt || !shift)) { e.preventDefault(); stop(); }
    if (!alt && !shift) other = false;
  }, true);
  addEventListener("blur", () => { alt = shift = other = false; if (recording) cancel(); });
})();
