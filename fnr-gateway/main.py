"""FNR AI Gateway.

Sits between the Android app and the AI services so that API keys never
live inside the app (Phase 4, NFR-SEC-03).

    GET  /health       -> which backends are active (no token needed)
    POST /v1/process   -> audio in, timestamped transcript + key points out

Run:  uvicorn main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import hmac
import logging
import os
import tempfile
import threading
import time
from collections import defaultdict, deque

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile

import config
import segmenter
import stt

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("fnr.gateway")

app = FastAPI(title="FNR AI Gateway", version="1.0.0")

_requests: dict[str, deque] = defaultdict(deque)
_lock = threading.Lock()


def _check_token(token: str | None) -> None:
    if not token or not hmac.compare_digest(token, config.APP_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid app token")


def _check_rate(device: str) -> None:
    now = time.time()
    with _lock:
        window = _requests[device]
        while window and now - window[0] > 3600:
            window.popleft()
        if len(window) >= config.RATE_LIMIT_PER_HOUR:
            raise HTTPException(status_code=429, detail="Too many requests. Try again later.")
        window.append(now)


@app.get("/")
def root() -> dict:
    return {"service": "FNR AI Gateway", "status": "ok", "health": "/health", "process": "POST /v1/process"}


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "stt": config.STT_BACKEND,
        "segmenter": config.SEGMENTER_BACKEND,
        "max_upload_mb": config.MAX_UPLOAD_BYTES // (1024 * 1024),
    }


@app.post("/v1/process")
def process(
    audio: UploadFile = File(...),
    language: str = Form("en"),
    duration_ms: int = Form(0),
    x_fnr_token: str | None = Header(default=None),
    x_fnr_device: str = Header(default="unknown"),
) -> dict:
    _check_token(x_fnr_token)
    _check_rate(x_fnr_device[:64])

    filename = audio.filename or "audio.m4a"
    ext = os.path.splitext(filename)[1].lower()
    if ext not in config.ALLOWED_EXTENSIONS:
        raise HTTPException(status_code=400, detail="Unsupported file type. Use MP3, M4A, AAC or WAV")

    started = time.time()
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=ext) as tmp:
            tmp_path = tmp.name
            size = 0
            while chunk := audio.file.read(1024 * 1024):
                size += len(chunk)
                if size > config.MAX_UPLOAD_BYTES:
                    raise HTTPException(status_code=413, detail="File is larger than 25 MB")
                tmp.write(chunk)
        if size == 0:
            raise HTTPException(status_code=400, detail="The audio file is empty")

        try:
            transcript = stt.transcribe(tmp_path, language, duration_ms or None)
        except stt.SttError as exc:
            log.warning("STT failed: %s", exc)
            raise HTTPException(status_code=502, detail=str(exc)) from exc
        except Exception as exc:  # decoding errors, corrupt files ...
            log.exception("STT crashed")
            raise HTTPException(status_code=502, detail="The audio could not be transcribed") from exc

        segments = transcript["segments"]
        if not segments:
            raise HTTPException(status_code=422, detail="No speech was detected in the recording")

        key_points, seg_model = segmenter.find_key_points(segments)
        elapsed = int((time.time() - started) * 1000)
        log.info(
            "Processed %s (%d KB): %d segments, %d key points in %d ms",
            filename, size // 1024, len(segments), len(key_points), elapsed,
        )
        return {
            "text": transcript["text"],
            "language": transcript["language"],
            "segments": segments,
            "key_points": key_points,
            "models": f"{transcript['model']} + {seg_model}",
            "processing_ms": elapsed,
        }
    finally:
        # Uploaded audio is deleted straight after processing (Phase 4, 4.8.3)
        if tmp_path and os.path.exists(tmp_path):
            os.remove(tmp_path)
