#!/usr/bin/env bash
# Starts the FNR AI Gateway on this Mac. First run creates a virtual environment,
# installs everything and copies .env.example to .env.
#   ./run_gateway.sh               free local Whisper (default), port 8000
#   ./run_gateway.sh mock          no AI at all, canned transcript, for quick UI tests
#   FNR_PORT=8001 ./run_gateway.sh mock    use another port if 8000 is taken
set -e
cd "$(dirname "$0")"
PORT="${FNR_PORT:-8000}"

# Another program already on this port? Then Safari would show that program, not FNR.
if command -v lsof >/dev/null && lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $PORT is already used by another program:"
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN
  echo ""
  echo "Stop that program, or start FNR on another port, for example:"
  echo "   FNR_PORT=8001 ./run_gateway.sh $1"
  exit 1
fi

if [ ! -d .venv ]; then
  echo "Creating virtual environment..."
  python3 -m venv .venv
fi
source .venv/bin/activate
pip install -q --upgrade pip
pip install -q -r requirements.txt

[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example"; }

if [ "$1" = "mock" ]; then
  export FNR_STT=mock
else
  pip install -q -r requirements-local.txt
fi

IP=$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')
echo ""
echo "Gateway starting. Check it in Safari: http://localhost:${PORT}/health"
echo "In the app (Profile > AI Gateway) use:"
echo "   Emulator:   http://10.0.2.2:${PORT}/"
echo "   Real phone: http://${IP:-YOUR_MAC_IP}:${PORT}/   (same Wi-Fi as this Mac)"
echo "Press Ctrl+C to stop."
echo ""
exec uvicorn main:app --host 0.0.0.0 --port "$PORT"
