"""One dictation: WAV clip -> silence check -> ASR -> cleanup -> paste. The console runner uses process();
the app calls the steps itself so it can save each clip before sending it and retry when the network fails."""
import time
from dataclasses import dataclass

import asr
import cleaner
import config
import inserter
import recorder


@dataclass
class Result:
    seconds: float
    raw: str = ""
    text: str = ""
    speech_ms: int = 0
    asr_ms: float = 0
    clean_ms: float = 0
    paste_ms: float = 0
    total_ms: float = 0
    skipped: str = ""
    cleaned_by: str = ""  # model name, or "raw" if cleanup failed and the transcript was pasted as heard


def skip_reason(wav: bytes, res: Result) -> str:
    """Why the clip isn't worth sending (too short, or no speech in it), else "". Fills res.speech_ms."""
    if res.seconds < config.MIN_CLIP_SECONDS:
        return "clip too short"
    res.speech_ms = recorder.speech_ms(wav)
    return "no speech heard" if res.speech_ms < config.MIN_SPEECH_MS else ""


def transcribe(audio: bytes, filename: str, res: Result, tone: str = "Balanced", words: list[str] = ()):
    """Fills res.raw and res.text, or res.skipped when Whisper heard no speech. tone and words (the user's
    dictionary) shape the transcript and the cleanup. Raises httpx errors when the network or Groq fails."""
    prompt = asr.hint(words)
    res.raw, t = asr.transcribe(audio, prompt, filename)
    res.asr_ms = t * 1000
    if asr.is_hallucination(res.raw, prompt):
        res.skipped = "no speech heard"
        return
    res.text, t, res.cleaned_by = cleaner.clean_with_fallback(res.raw, tone, words)
    res.clean_ms = t * 1000
    if not res.text:
        res.skipped = "cleanup returned nothing"


def paste(text: str, any_held) -> float:
    """Pastes into the focused app once the hotkey keys are up, so Win+Ctrl+V is not sent. Returns ms taken."""
    deadline = time.perf_counter() + 2
    while any_held() and time.perf_counter() < deadline:
        time.sleep(0.01)
    t0 = time.perf_counter()
    inserter.insert(text)
    return (time.perf_counter() - t0) * 1000


def process(wav: bytes, seconds: float, released_at: float, any_held, tone: str = "Balanced",
            words: list[str] = ()) -> Result:
    """The whole dictation in one go; any_held() reports whether hotkey keys are still down."""
    res = Result(seconds)
    res.skipped = skip_reason(wav, res)
    if not res.skipped:
        transcribe(wav, "audio.wav", res, tone, words)
    if not res.skipped:
        res.paste_ms = paste(res.text, any_held)
        res.total_ms = (time.perf_counter() - released_at) * 1000
    return res
