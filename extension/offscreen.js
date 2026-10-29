// Hidden page that records while the shortcut is held, then turns the clip into clean text.
// The mic is released after every clip so Chrome's "recording" indicator doesn't stay on.
import {GroqError, cleanWithFallback, transcribe} from "./groq.js";

const MIN_CLIP_SECONDS = 0.3;
let rec = null, stream = null, chunks = [], startedAt = 0, starting = null;

async function start() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({audio: {echoCancellation: true, noiseSuppression: true}});
  } catch (e) {
    return {error: e.name === "NotAllowedError" ? "mic" : `Microphone error: ${e.message}`};
  }
  chunks = [];
  rec = new MediaRecorder(stream, {mimeType: "audio/webm;codecs=opus"});
  rec.ondataavailable = e => chunks.push(e.data);
  rec.start();
  startedAt = performance.now();
  return {ok: true};
}

async function finish() {
  await new Promise(done => { rec.onstop = done; rec.stop(); });
  stream.getTracks().forEach(t => t.stop());
  const clip = {audio: new Blob(chunks, {type: "audio/webm"}), seconds: (performance.now() - startedAt) / 1000};
  rec = stream = null;
  return clip;
}

async function stop(key) {
  await starting;  // the key may be released before the mic finished opening
  if (!rec) return {skipped: "Not recording."};
  const {audio, seconds} = await finish();
  if (seconds < MIN_CLIP_SECONDS) return {skipped: "Hold the keys a little longer."};
  try {
    const raw = await transcribe(audio, key);
    if (!raw) return {skipped: "Didn’t catch that. Try again."};
    return {raw, ...(await cleanWithFallback(raw, key))};
  } catch (e) {
    console.warn("dictation failed", e);
    if (e instanceof GroqError && e.status === 429) return {error: "Rate limit reached. Wait a minute and try again."};
    if (e instanceof GroqError && [401, 403].includes(e.status)) return {error: "Groq rejected your key. Update it in Saylo setup."};
    return {error: "Couldn’t reach Groq. Check your internet connection."};
  }
}

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg.to !== "offscreen") return;
  let job;
  if (msg.type === "start") job = starting = rec ? Promise.resolve({ok: true}) : start();
  else if (msg.type === "stop") job = stop(msg.key);
  else job = Promise.resolve(starting).then(() => rec && finish()).then(() => ({ok: true}));
  job.then(reply, e => reply({error: String(e?.message || e)}));
  return true;
});
