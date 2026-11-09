#!/usr/bin/env bash
# Idempotent: start the PaperDesk agent (127.0.0.1:8800) if it is not running; --restart forces it.
# Called by ~/.claude/loops/review_server_start.sh (cron @reboot + */10), which also owns the
# tailscale serve mapping https://paper1.<tailnet>.ts.net:8448 -> 127.0.0.1:8800.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PIDF="$HOME/.local/state/paperdesk/agent.pid"
LOG="$HOME/.local/state/paperdesk/agent.log"
PY="$HOME/.venvs/deepagent/bin/python"
mkdir -p "$(dirname "$PIDF")"
running() { [[ -f "$PIDF" ]] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }
# --restart refuses while agent runs are live (a restart stops them); --restart --force does it anyway.
if [[ "${1:-}" == "--restart" ]] && running; then
  busy=$(curl -s -m 5 http://127.0.0.1:8800/api/agent/health | python3 -c 'import json,sys; print(json.load(sys.stdin).get("running", 0))' 2>/dev/null || echo 0)
  if [[ "$busy" != "0" && "${2:-}" != "--force" ]]; then
    echo "agent has $busy live run(s); not restarting (use --restart --force)" >&2; exit 2
  fi
  kill "$(cat "$PIDF")"; for _ in 1 2 3 4 5 6 7 8; do running || break; sleep 1; done
  running && kill -9 "$(cat "$PIDF")" && sleep 1
fi
running && exit 0
[[ -x "$PY" ]] || { echo "missing venv ~/.venvs/deepagent" >&2; exit 1; }
set -a; . "$HOME/.config/raiLP/secrets.env"; set +a
cd "$DIR" || exit 1
setsid nohup "$PY" -I "$DIR/agentd.py" serve --port 8800 >> "$LOG" 2>&1 < /dev/null &
echo $! > "$PIDF"
