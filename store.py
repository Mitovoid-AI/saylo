"""Local data in %APPDATA%\\Saylo: settings.json (app state), profile.json (onboarding answers,
kept for future prompt personalisation and the backend), history.jsonl (one dictation per line),
audio\\*.mp3 (each dictation's recording, named in its history entry, deleted after AUDIO_DAYS),
pending\\*.mp3 + .json (clips saved but not sent yet, e.g. while offline; never pruned),
dictionary.json (the user's words, sent to Whisper and the cleanup as spelling hints)."""
import json
import os
import time
from datetime import date, datetime, timedelta
from pathlib import Path

import config

SETTINGS = config.APP_DIR / "settings.json"
PROFILE = config.APP_DIR / "profile.json"
HISTORY = config.APP_DIR / "history.jsonl"
AUDIO = config.APP_DIR / "audio"
AUDIO_DAYS = 30  # older recordings are deleted; their text stays in the history
PENDING = config.APP_DIR / "pending"
DICTIONARY = config.APP_DIR / "dictionary.json"
MAX_WORD_CHARS = 60

DEFAULT_PROFILE = {
    "name": "", "email": "", "role": "", "places": [], "tone": "Balanced", "source": "",
    "keep_history": True, "share_usage": False,
}


def _read(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _write(path, data):
    config.APP_DIR.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")


def settings() -> dict:
    return _read(SETTINGS, {})


def update_settings(**patch) -> dict:
    s = {**settings(), **patch}
    _write(SETTINGS, s)
    return s


def profile() -> dict:
    return {**DEFAULT_PROFILE, **_read(PROFILE, {})}


def update_profile(patch: dict) -> dict:
    p = {**profile(), **{k: v for k, v in patch.items() if k in DEFAULT_PROFILE}}
    _write(PROFILE, p)
    return p


def dictionary() -> list[str]:
    return _read(DICTIONARY, [])


def set_dictionary(words: list[str]) -> list[str]:
    """Saves the words trimmed, without blanks or case-insensitive duplicates, in the order given."""
    out, seen = [], set()
    for w in words:
        w = " ".join(str(w).split())[:MAX_WORD_CHARS]
        if w and w.lower() not in seen:
            seen.add(w.lower())
            out.append(w)
    _write(DICTIONARY, out)
    return out


def add_pending(mp3: bytes, audio_s: float) -> str:
    """Saves a clip before it is sent, so a dropped network or a crash can't lose it; returns its name.
    It stays in PENDING until finish_pending or drop_pending."""
    PENDING.mkdir(parents=True, exist_ok=True)
    now = datetime.now()
    # Microseconds in the name: two dictations can land in the same second, and names sort oldest first.
    name = now.strftime("%Y-%m-%d_%H-%M-%S_%f") + ".mp3"
    (PENDING / name).write_bytes(mp3)
    meta = {"t": now.isoformat(timespec="seconds"), "audio_s": round(audio_s, 2), "wall": time.time()}
    (PENDING / name).with_suffix(".json").write_text(json.dumps(meta), encoding="utf-8")
    return name


def pending() -> list[dict]:
    """Clips waiting to be sent, oldest first, shaped like history entries (empty text, "pending": True)."""
    out = []
    for p in sorted(PENDING.glob("*.mp3")):
        meta = _read(p.with_suffix(".json"), {})
        wall = meta.get("wall") or p.stat().st_mtime
        out.append({"t": meta.get("t") or datetime.fromtimestamp(wall).isoformat(timespec="seconds"),
                    "text": "", "raw": "", "words": 0, "audio_s": meta.get("audio_s", 0), "total_ms": 0,
                    "audio": p.name, "pending": True, "wall": wall})
    return out


def finish_pending(name: str, text: str, raw: str, total_ms: float, failed: bool = False) -> dict:
    """A sent clip becomes a history entry and its recording moves to AUDIO (deleted if history is off).
    failed marks a recording Groq couldn't transcribe, kept so it can be tried again from History."""
    clip = PENDING / name
    meta = _read(clip.with_suffix(".json"), {})
    entry = {"t": meta.get("t") or datetime.now().isoformat(timespec="seconds"), "text": text, "raw": raw,
             "words": len(text.split()), "audio_s": meta.get("audio_s", 0), "total_ms": round(total_ms)}
    if failed:
        entry["failed"] = True
    if profile()["keep_history"]:
        AUDIO.mkdir(parents=True, exist_ok=True)
        os.replace(clip, AUDIO / name)
        entry["audio"] = name
        with HISTORY.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        prune_audio()
    else:
        clip.unlink(missing_ok=True)
    clip.with_suffix(".json").unlink(missing_ok=True)
    return entry


def drop_pending(name: str):
    """Deletes a saved clip that turned out to have no speech in it."""
    for p in (PENDING / name, (PENDING / name).with_suffix(".json")):
        p.unlink(missing_ok=True)


def update_history(audio: str, text: str, raw: str) -> dict | None:
    """Replaces the text of the entry that owns this recording (after a retry); returns the entry."""
    try:
        lines = HISTORY.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    found = None
    for i, line in enumerate(lines):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("audio") == audio:
            e.pop("failed", None)
            found = {**e, "text": text, "raw": raw, "words": len(text.split())}
            lines[i] = json.dumps(found, ensure_ascii=False)
    if found:
        tmp = HISTORY.with_suffix(".tmp")
        tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
        os.replace(tmp, HISTORY)  # all or nothing, so a crash can't leave half a history
    return found


def audio_path(name: str) -> Path | None:
    """The recording of a history entry or of a clip waiting to be sent, or None once it is deleted. Only
    plain .mp3 names inside AUDIO or PENDING are accepted, since the name comes from the page."""
    for folder in (AUDIO, PENDING):
        p = folder / name
        if p.name == name and p.suffix == ".mp3" and p.is_file():
            return p
    return None


def prune_audio():
    cutoff = time.time() - AUDIO_DAYS * 86400
    for p in AUDIO.glob("*.mp3"):
        if p.stat().st_mtime < cutoff:
            p.unlink(missing_ok=True)


def history(query: str = "", limit: int = 200) -> list[dict]:
    try:
        lines = HISTORY.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    out = []
    for line in reversed(lines):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if query.lower() in e["text"].lower():
            out.append(e)
            if len(out) >= limit:
                break
    return out


def clear_history():
    HISTORY.unlink(missing_ok=True)
    for p in AUDIO.glob("*.mp3"):
        p.unlink(missing_ok=True)


def stats() -> dict:
    entries = history(limit=10**9)
    words = sum(e["words"] for e in entries)
    audio = sum(e["audio_s"] for e in entries)
    days = {datetime.fromisoformat(e["t"]).date() for e in entries}
    streak, day = 0, date.today()
    if day not in days:
        day -= timedelta(days=1)  # a streak survives until the end of today
    while day in days:
        streak += 1
        day -= timedelta(days=1)
    return {"words": words, "wpm": round(words / audio * 60) if audio > 5 else 0, "streak": streak,
            "dictations": len(entries)}
