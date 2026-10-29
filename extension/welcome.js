// Setup page: Groq key (checked against Groq, kept in chrome.storage.local) and the microphone
// permission, which must be granted here because the offscreen recorder can't show a prompt.
import {checkKey} from "./groq.js";

const $ = s => document.querySelector(s);

function msg(el, text, kind = "") { el.textContent = text; el.className = `msg ${kind}`; }

async function renderKey() {
  const {groqKey} = await chrome.storage.local.get("groqKey");
  $("#stepKey").classList.toggle("done", !!groqKey);
  if (groqKey) msg($("#keyMsg"), "Key saved ✓ Paste a new one to replace it.", "ok");
}

$("#saveKey").onclick = async () => {
  const key = $("#key").value.trim();
  if (!key) return msg($("#keyMsg"), "Paste your key first.", "err");
  $("#saveKey").disabled = true;
  msg($("#keyMsg"), "Checking key…");
  const r = await checkKey(key);
  $("#saveKey").disabled = false;
  if (!r.ok) return msg($("#keyMsg"), r.error, "err");
  await chrome.storage.local.set({groqKey: key});
  $("#key").value = "";
  renderKey();
};
$("#key").onkeydown = e => { if (e.key === "Enter") $("#saveKey").click(); };

async function renderMic() {
  const state = (await navigator.permissions.query({name: "microphone"})).state;
  $("#stepMic").classList.toggle("done", state === "granted");
  $("#allowMic").style.display = state === "granted" ? "none" : "";
  if (state === "granted") msg($("#micMsg"), "Microphone allowed ✓", "ok");
  else if (state === "denied") msg($("#micMsg"), "Blocked. Click the icon at the right of the address bar to allow the microphone, then reload this page.", "err");
}

$("#allowMic").onclick = async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({audio: true});
    stream.getTracks().forEach(t => t.stop());
  } catch (e) {
    console.warn("mic permission", e);
  }
  renderMic();
};

renderKey();
renderMic();
