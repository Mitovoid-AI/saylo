"""One dictation: WAV clip -> ASR -> cleanup -> paste. Shared by the console runner and the app."""
import time
from dataclasses import dataclass

import asr
import cleaner
import config
import inserter


@dataclass
class Result:
    seconds: float
    raw: str = ""
    text: str = ""
    asr_ms: float = 0
    clean_ms: float = 0
    paste_ms: float = 0
    total_ms: float = 0
    skipped: str = ""
    cleaned_by: str = ""  # model name, or "raw" if cleanup failed and the transcript was pasted as heard


def process(wav: bytes, seconds: float, released_at: float, any_held) -> Result:
    """any_held() reports whether hotkey keys are still down; we wait so Win+Ctrl+V is not sent."""
    res = Result(seconds)
    if seconds < config.MIN_CLIP_SECONDS:
        res.skipped = "clip too short"
        return res
    res.raw, t = asr.transcribe(wav)
    res.asr_ms = t * 1000
    if not res.raw:
        res.skipped = "no speech heard"
        return res
    res.text, t, res.cleaned_by = cleaner.clean_with_fallback(res.raw)
    res.clean_ms = t * 1000
    if not res.text:
        res.skipped = "cleanup returned nothing"
        return res
    deadline = time.perf_counter() + 2
    while any_held() and time.perf_counter() < deadline:
        time.sleep(0.01)
    t0 = time.perf_counter()
    inserter.insert(res.text)
    res.paste_ms = (time.perf_counter() - t0) * 1000
    res.total_ms = (time.perf_counter() - released_at) * 1000
    return res
