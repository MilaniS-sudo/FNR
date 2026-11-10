# FNR AI Gateway

Small Python service between the Flash Note Recorder app and the AI. It keeps
API keys off the phone and turns a recording into timestamped key points.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Shows which speech-to-text and key-point backends are active |
| `POST /v1/process` | Multipart `audio` + `duration_ms` → transcript, segments, key points |

## Run it (Mac)

```bash
cd fnr-gateway
chmod +x run_gateway.sh
./run_gateway.sh          # free local Whisper, no API key needed
./run_gateway.sh mock     # canned transcript, for testing the app UI quickly
```

The first real request downloads the Whisper "base" model (about 140 MB).

## Backends (set in `.env`)

| Setting | Options | Cost |
| --- | --- | --- |
| `FNR_STT` | `local` (faster-whisper on this computer), `openai`, `mock` | local and mock are free |
| `FNR_SEGMENTER` | `heuristic` (rule based), `openai`, `gemini` | heuristic is free |

If an online key-point service fails, the Gateway falls back to the heuristic
so the student still gets flash notes.

## Safety rules applied to every result

- Clip times are clamped to the recording, 5–120 seconds, no overlaps.
- Titles 3–100 characters, summaries at most 500, concepts at most 60.
- The `X-FNR-Token` header must match `FNR_APP_TOKEN` (401 otherwise).
- 20 requests per device per hour (429 otherwise); files over 25 MB rejected (413).
- Uploaded audio is deleted straight after processing.

## Tests

```bash
source .venv/bin/activate
python -m pytest -q tests
```
