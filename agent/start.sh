#!/usr/bin/env bash
# Idempotent: start the PaperDesk agent (127.0.0.1:8800) if it is not running; --restart forces it.
# Box: called by ~/.claude/loops/review_server_start.sh (cron @reboot + */10), which also owns the
# tailscale serve mapping https://paper1.<tailnet>.ts.net:8448 -> 127.0.0.1:8800.
# Portable installs normally run the agent as the systemd service paperdesk-agent (install.sh); this script
# works there too (venv from install_agent.sh; keys are read by agentd itself from secrets.env).
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
PIDF="$HOME/.local/state/paperdesk/agent.pid"
LOG="$HOME/.local/state/paperdesk/agent.log"
CONF="${PAPERDESK_CONFIG_DIR:-$HOME/.config/paperdesk}"
if grep -qE '^\s*profile\s*=\s*"box"' "$CONF/config.toml" 2>/dev/null; then
  PY="$HOME/.venvs/deepagent/bin/python"; SECRETS="$HOME/.config/raiLP/secrets.env"
else
  PY="${PAPERDESK_AGENT_VENV:-$HOME/.local/share/paperdesk/agent-venv}/bin/python"; SECRETS=""
fi
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
[[ -x "$PY" ]] || { echo "missing venv for $PY (portable: run agent/install_agent.sh)" >&2; exit 1; }
if [[ -n "$SECRETS" ]]; then set -a; . "$SECRETS"; set +a; fi
cd "$DIR" || exit 1
setsid nohup "$PY" -I "$DIR/agentd.py" serve --port 8800 >> "$LOG" 2>&1 < /dev/null &
echo $! > "$PIDF"
