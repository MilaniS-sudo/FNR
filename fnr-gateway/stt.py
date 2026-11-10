"""Speech-to-text backends.

Each backend returns a dict:
    {"text": str, "language": str, "model": str,
     "segments": [{"start_ms": int, "end_ms": int, "text": str}, ...]}
"""
from __future__ import annotations

import logging

import httpx

import config

log = logging.getLogger("fnr.stt")

_local_model = None  # loaded once, on first request


class SttError(Exception):
    """Raised when transcription fails; the message is safe to show users."""


def transcribe(path: str, language: str, duration_ms: int | None) -> dict:
    backend = config.STT_BACKEND
    if backend == "local":
        return _local(path, language)
    if backend == "openai":
        return _openai(path, language)
    if backend == "mock":
        return _mock(duration_ms)
    raise SttError(f"Unknown FNR_STT backend '{backend}'")


# ---------------------------------------------------------------- local ----
def _local(path: str, language: str) -> dict:
    global _local_model
    try:
        from faster_whisper import WhisperModel
    except ImportError as exc:  # pragma: no cover - depends on install
        raise SttError(
            "Local speech-to-text is not installed. Run: pip install -r requirements-local.txt"
        ) from exc

    if _local_model is None:
        log.info("Loading Whisper model '%s' (first run downloads it)...", config.LOCAL_WHISPER_MODEL)
        _local_model = WhisperModel(
            config.LOCAL_WHISPER_MODEL,
            device=config.LOCAL_WHISPER_DEVICE,
            compute_type=config.LOCAL_WHISPER_COMPUTE,
        )

    segments_iter, info = _local_model.transcribe(
        path, language=language or None, vad_filter=True, beam_size=5
    )
    segments = [
        {"start_ms": int(s.start * 1000), "end_ms": int(s.end * 1000), "text": s.text.strip()}
        for s in segments_iter
        if s.text.strip()
    ]
    return {
        "text": " ".join(s["text"] for s in segments),
        "language": info.language or language,
        "model": f"faster-whisper-{config.LOCAL_WHISPER_MODEL}",
        "segments": segments,
    }


# --------------------------------------------------------------- openai ----
def _openai(path: str, language: str) -> dict:
    if not config.OPENAI_API_KEY:
        raise SttError("OPENAI_API_KEY is not set on the Gateway")
    with open(path, "rb") as fh:
        files = {"file": (path.rsplit("/", 1)[-1], fh)}
        data = {
            "model": config.OPENAI_STT_MODEL,
            "response_format": "verbose_json",
            "timestamp_granularities[]": "segment",
        }
        if language:
            data["language"] = language
        try:
            resp = httpx.post(
                "https://api.openai.com/v1/audio/transcriptions",
                headers={"Authorization": f"Bearer {config.OPENAI_API_KEY}"},
                data=data,
                files=files,
                timeout=300,
            )
        except httpx.HTTPError as exc:
            raise SttError("Could not reach the speech-to-text service") from exc
    if resp.status_code != 200:
        log.warning("OpenAI STT error %s: %s", resp.status_code, resp.text[:300])
        raise SttError(f"Speech-to-text service returned an error ({resp.status_code})")
    body = resp.json()
    segments = [
        {"start_ms": int(s["start"] * 1000), "end_ms": int(s["end"] * 1000), "text": s["text"].strip()}
        for s in body.get("segments", [])
        if s.get("text", "").strip()
    ]
    return {
        "text": body.get("text", ""),
        "language": body.get("language", language),
        "model": config.OPENAI_STT_MODEL,
        "segments": segments,
    }


# ----------------------------------------------------------------- mock ----
_MOCK_SENTENCES = [
    "Today I am revising the systems development life cycle.",
    "The systems development life cycle is defined as a structured process for planning, creating, testing and deploying an information system.",
    "The first phase is planning, where the scope, feasibility and schedule are decided.",
    "Next comes analysis, which refers to gathering and documenting the requirements of the users.",
    "Normalisation means organising a database to reduce redundancy and improve integrity.",
    "A primary key is a column that uniquely identifies each row in a table.",
    "Testing is important because it shows that the system meets its requirements.",
    "In summary, each phase produces a deliverable that the next phase depends on.",
]


def _mock(duration_ms: int | None) -> dict:
    """Canned transcript spread evenly over the real recording length.

    Lets the whole app be demonstrated without any model or API key.
    """
    total = duration_ms if duration_ms and duration_ms > 0 else 120_000
    step = max(total // len(_MOCK_SENTENCES), 1)
    segments = []
    for i, sentence in enumerate(_MOCK_SENTENCES):
        start = i * step
        end = min(total, start + step)
        if end - start < 1000:
            break
        segments.append({"start_ms": start, "end_ms": end, "text": sentence})
    return {
        "text": " ".join(s["text"] for s in segments),
        "language": "en",
        "model": "mock",
        "segments": segments,
    }
