"""FNR AI Gateway configuration.

Every setting comes from environment variables (or a .env file next to this
script). Real API keys must never be shared or handed in: copy .env.example
to .env and fill it in locally.
"""
import os

from dotenv import load_dotenv

load_dotenv()


def _get(name: str, default: str) -> str:
    value = os.getenv(name, default)
    return value.strip() if value else default


# Shared secret the Android app sends in the X-FNR-Token header.
APP_TOKEN = _get("FNR_APP_TOKEN", "change-me-demo-token")

# Speech-to-text backend: "local" (free, faster-whisper on this machine),
# "openai" (paid API) or "mock" (no audio processing, canned transcript; for UI testing).
STT_BACKEND = _get("FNR_STT", "local").lower()

# Key-point backend: "heuristic" (free, rule based), "openai" or "gemini".
SEGMENTER_BACKEND = _get("FNR_SEGMENTER", "heuristic").lower()

# Local Whisper settings. "base" is a good balance on a MacBook Air;
# use "small" for better accuracy if processing time allows.
LOCAL_WHISPER_MODEL = _get("FNR_WHISPER_MODEL", "base")
LOCAL_WHISPER_DEVICE = _get("FNR_WHISPER_DEVICE", "cpu")
LOCAL_WHISPER_COMPUTE = _get("FNR_WHISPER_COMPUTE", "int8")

# OpenAI settings (only used when a backend is set to "openai").
OPENAI_API_KEY = _get("OPENAI_API_KEY", "")
OPENAI_STT_MODEL = _get("OPENAI_STT_MODEL", "whisper-1")
OPENAI_TEXT_MODEL = _get("OPENAI_TEXT_MODEL", "gpt-4o-mini")

# Google Gemini settings (only used when FNR_SEGMENTER=gemini).
GEMINI_API_KEY = _get("GEMINI_API_KEY", "")
GEMINI_MODEL = _get("GEMINI_MODEL", "gemini-2.0-flash")

# Limits
MAX_UPLOAD_BYTES = int(_get("FNR_MAX_UPLOAD_MB", "25")) * 1024 * 1024
RATE_LIMIT_PER_HOUR = int(_get("FNR_RATE_LIMIT_PER_HOUR", "20"))

# Clip length rules (must match the Android app, see Phase 4 section 4.6.5)
MIN_CLIP_MS = 5_000
MAX_CLIP_MS = 120_000

ALLOWED_EXTENSIONS = {".m4a", ".mp4", ".mp3", ".aac", ".wav"}
