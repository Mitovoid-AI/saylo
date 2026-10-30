"""Speech to raw text via Groq Whisper. Run directly to test: python asr.py clip.wav"""
import logging
import re
import sys
import time

import httpx

import config

log = logging.getLogger(__name__)

_client = httpx.Client(timeout=30)

def _plain(text: str) -> str:
    return re.sub(r"[^\w\s']", "", text).strip().lower()


# Whisper's stock outputs for silence or noise (learned from video subtitles). Nobody dictates these alone.
# "Thank you." is not here because people do say it; recorder.speech_ms catches it when nothing was said.
_HALLUCINATIONS = {_plain(t) for t in (
    "", "you", "Thanks for watching!", "Thank you for watching.", "Thank you so much for watching.",
    "Please subscribe.", "Subscribe to my channel.", "Like and subscribe.",
    "Subtitles by the Amara.org community", "Sous-titrage Société Radio-Canada",
    "Sous-titres réalisés par la communauté d'Amara.org",
)}


def hint(words: list[str]) -> str:
    """The user's dictionary as Whisper's prompt, which nudges it to these spellings. Whisper reads at most
    224 tokens of prompt, so the list is cut at ~600 characters."""
    out = ""
    for w in words:
        if len(out) + len(w) > 600:
            break
        out += (", " if out else "") + w
    return out + "." if out else ""


def is_hallucination(text: str, prompt: str = "") -> bool:
    """True for Whisper's silence outputs, and for the prompt read back to us (Whisper does that on silence)."""
    plain = _plain(text)
    return plain in _HALLUCINATIONS or bool(prompt) and plain == _plain(prompt)


def transcribe(audio: bytes, prompt: str = "", filename: str = "audio.wav") -> tuple[str, float]:
    """Returns (raw_text, seconds_taken). Groq picks the format from the file name (.wav, .mp3)."""
    key = config.require("GROQ_API_KEY", config.GROQ_API_KEY)
    t0 = time.perf_counter()
    data = {
        "model": config.ASR_MODEL,
        "language": config.LANGUAGE,
        "response_format": "json",
        "temperature": "0",
    }
    if prompt:
        data["prompt"] = prompt
    r = _client.post(
        config.ASR_URL,
        headers={"Authorization": f"Bearer {key}"},
        files={"file": (filename, audio)},
        data=data,
    )
    if r.status_code != 200:
        log.warning("ASR failed %s: %s", r.status_code, r.text[:500])
    r.raise_for_status()
    return r.json().get("text", "").strip(), time.perf_counter() - t0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    text, secs = transcribe(open(sys.argv[1], "rb").read())
    log.info("ASR %.0f ms: %s", secs * 1000, text)
