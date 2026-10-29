"""Speech to raw text via Groq Whisper. Run directly to test: python asr.py clip.wav"""
import logging
import sys
import time

import httpx

import config

log = logging.getLogger(__name__)

_client = httpx.Client(timeout=30)


def transcribe(wav: bytes) -> tuple[str, float]:
    """Returns (raw_text, seconds_taken)."""
    key = config.require("GROQ_API_KEY", config.GROQ_API_KEY)
    t0 = time.perf_counter()
    r = _client.post(
        config.ASR_URL,
        headers={"Authorization": f"Bearer {key}"},
        files={"file": ("audio.wav", wav, "audio/wav")},
        data={
            "model": config.ASR_MODEL,
            "language": config.LANGUAGE,
            "response_format": "json",
            "temperature": "0",
        },
    )
    if r.status_code != 200:
        log.warning("ASR failed %s: %s", r.status_code, r.text[:500])
    r.raise_for_status()
    return r.json().get("text", "").strip(), time.perf_counter() - t0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    text, secs = transcribe(open(sys.argv[1], "rb").read())
    log.info("ASR %.0f ms: %s", secs * 1000, text)
