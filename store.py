"""Local data in %APPDATA%\\Saylo: settings.json (app state), profile.json (onboarding answers,
kept for future prompt personalisation and the backend), history.jsonl (one dictation per line)."""
import json
from datetime import date, datetime, timedelta

import config

SETTINGS = config.APP_DIR / "settings.json"
PROFILE = config.APP_DIR / "profile.json"
HISTORY = config.APP_DIR / "history.jsonl"

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


def add_history(text: str, raw: str, audio_s: float, total_ms: float) -> dict:
    entry = {"t": datetime.now().isoformat(timespec="seconds"), "text": text, "raw": raw,
             "words": len(text.split()), "audio_s": round(audio_s, 2), "total_ms": round(total_ms)}
    if profile()["keep_history"]:
        config.APP_DIR.mkdir(parents=True, exist_ok=True)
        with HISTORY.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return entry


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
