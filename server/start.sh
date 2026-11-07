#!/usr/bin/env bash
# Idempotent: start the PaperDesk server (127.0.0.1:8799) if it is not running; --restart forces it.
# Called by ~/.claude/loops/review_server_start.sh (cron @reboot + */10), which also owns the
# tailscale serve mapping https://paper1.<tailnet>.ts.net:8447 -> 127.0.0.1:8799.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PIDF="$HOME/.local/state/paperdesk/server.pid"
LOG="$HOME/.local/state/paperdesk/server.log"
mkdir -p "$(dirname "$PIDF")"
running() { [[ -f "$PIDF" ]] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }
if [[ "${1:-}" == "--restart" ]] && running; then
  kill "$(cat "$PIDF")"; for _ in 1 2 3 4 5; do running || break; sleep 1; done
fi
running && exit 0
# no `python3 -I`: PyMuPDF and requests live in the user site-packages
cd "$DIR" || exit 1
setsid nohup python3 "$DIR/paperdesk.py" serve --port 8799 >> "$LOG" 2>&1 < /dev/null &
echo $! > "$PIDF"
