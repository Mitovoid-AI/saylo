// Routes the content script's start / stop / cancel to the offscreen page, which owns the microphone
// (service workers can't record). The Groq key lives in chrome.storage.local, set on the setup page.
const OFFSCREEN = "offscreen.html";

async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  await chrome.offscreen.createDocument({
    url: OFFSCREEN, reasons: ["USER_MEDIA"], justification: "Record dictation while the shortcut is held",
  });
}

async function handle(msg) {
  const {groqKey} = await chrome.storage.local.get("groqKey");
  if (msg.type === "start") {
    if (!groqKey) {
      chrome.runtime.openOptionsPage();
      return {error: "Add your Groq key in Saylo setup first."};
    }
    await ensureOffscreen();
  } else if (!(await chrome.offscreen.hasDocument())) {
    return {skipped: "Not recording."};
  }
  const r = await chrome.runtime.sendMessage({to: "offscreen", type: msg.type, key: groqKey});
  if (r?.error === "mic") {
    chrome.runtime.openOptionsPage();
    return {error: "Allow the microphone in Saylo setup."};
  }
  return r;
}

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg.to === "offscreen") return;  // for the offscreen page, not us
  handle(msg).then(reply, e => reply({error: String(e?.message || e)}));
  return true;
});

chrome.runtime.onInstalled.addListener(d => { if (d.reason === "install") chrome.runtime.openOptionsPage(); });
chrome.action.onClicked.addListener(() => chrome.runtime.openOptionsPage());
