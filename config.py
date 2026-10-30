"""Loads settings, first match wins: %APPDATA%\\Saylo\\.env (the user's own key from onboarding),
then .env next to the app, then bundled.env packed into the exe (the built-in default key)."""
import os
import sys
from pathlib import Path

from dotenv import dotenv_values, load_dotenv

APP_DIR = Path(os.getenv("APPDATA", Path.home())) / "Saylo"
USER_ENV = APP_DIR / ".env"
SOURCE_DIR = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).parent
BUNDLED_ENV = Path(getattr(sys, "_MEIPASS", SOURCE_DIR)) / "bundled.env"

load_dotenv(USER_ENV)
load_dotenv(SOURCE_DIR / ".env")
load_dotenv(BUNDLED_ENV)

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
# True when the user entered their own key; otherwise GROQ_API_KEY (if set) is the built-in default.
HAS_USER_KEY = bool(USER_ENV.exists() and dotenv_values(USER_ENV).get("GROQ_API_KEY"))
OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")

HOTKEY = os.getenv("HOTKEY", "ctrl+cmd")

ASR_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
ASR_MODEL = os.getenv("ASR_MODEL", "whisper-large-v3-turbo")
LANGUAGE = os.getenv("LANGUAGE", "en")

# Cleanup LLM: "groq" (fast, default) or "openrouter".
CLEANUP_PROVIDER = os.getenv("CLEANUP_PROVIDER", "groq")
CLEANUP_URLS = {
    "groq": "https://api.groq.com/openai/v1/chat/completions",
    "openrouter": "https://openrouter.ai/api/v1/chat/completions",
}
CLEANUP_URL = CLEANUP_URLS[CLEANUP_PROVIDER]
CLEANUP_MODEL = os.getenv("CLEANUP_MODEL", "qwen/qwen3.8-27b")
# Tried once when the main model fails (rate limit, overload, timeout). Groq limits each model separately.
# gpt-oss-120b, not 20b: in testing 20b dropped spoken words (intro sentences, "first").
FALLBACK_MODEL = os.getenv("FALLBACK_MODEL", "openai/gpt-oss-120b")
# Seconds to wait for a cleanup reply before trying the fallback; normal replies take ~0.3 s.
CLEANUP_TIMEOUT = float(os.getenv("CLEANUP_TIMEOUT", "6"))

BENCHMARK_MODELS = [m.strip() for m in os.getenv("BENCHMARK_MODELS", CLEANUP_MODEL).split(",") if m.strip()]

SAMPLE_RATE = 16000
MIN_CLIP_SECONDS = 0.3
# Clips with less clearly-voiced audio than this are skipped without calling Whisper (see recorder.speech_ms).
MIN_SPEECH_MS = 120


def cleanup_key() -> str:
    if CLEANUP_PROVIDER == "groq":
        return require("GROQ_API_KEY", GROQ_API_KEY)
    return require("OPENROUTER_API_KEY", OPENROUTER_API_KEY)


def require(name: str, value: str) -> str:
    if not value:
        raise SystemExit(f"{name} is not set. Copy .env.example to .env and fill it in.")
    return value
