#!/usr/bin/env bash
# Install the PaperDesk chat agent on a portable desk. Idempotent; run as the desk user, no sudo.
# Needs (install.sh provides them): python3 with venv, docker with the user in the docker group, gh.
# Creates the venv (pinned requirements.txt) and builds the sandbox image pda-sandbox:1 from sandbox/Dockerfile.
# The agent then starts unconfigured; the phone app sets the provider (agent.toml) and the keys (secrets.env).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${PAPERDESK_CONFIG_DIR:-$HOME/.config/paperdesk}"
VENV="${PAPERDESK_AGENT_VENV:-$HOME/.local/share/paperdesk/agent-venv}"
IMAGE="pda-sandbox:1"
ok()  { echo "  ● $*"; }
die() { echo "  ■ $*" >&2; exit 1; }

if grep -qE '^\s*profile\s*=\s*"box"' "$CONF/config.toml" 2>/dev/null; then
  die "this desk runs the \"box\" profile (venv ~/.venvs/deepagent); install_agent.sh is for portable desks"
fi
command -v python3 >/dev/null || die "python3 missing"

# 1. venv with the pinned packages (reinstalled only when requirements.txt changes)
[[ -x "$VENV/bin/python" ]] || python3 -m venv "$VENV" || die "python3 -m venv failed (install python3-venv)"
req=$(sha256sum "$DIR/requirements.txt" | cut -c1-16)
if [[ "$(cat "$VENV/.pd-requirements" 2>/dev/null)" != "$req" ]]; then
  "$VENV/bin/pip" install -q --upgrade pip >/dev/null
  "$VENV/bin/pip" install -q -r "$DIR/requirements.txt" || die "pip install failed"
  echo "$req" > "$VENV/.pd-requirements"
  ok "agent packages installed in $VENV"
else
  ok "agent packages up to date ($VENV)"
fi
"$VENV/bin/python" -c "import deepagents, fastapi, uvicorn, langchain_openai, langgraph.checkpoint.sqlite" \
  || die "agent packages do not import"

# 2. sandbox image (rebuilt only when the Dockerfile changes). A shell started before the user joined the
#    docker group has no access yet: fall back to `sg docker`.
dk() {
  if docker info >/dev/null 2>&1; then docker "$@"
  else sg docker -c "docker $(printf '%q ' "$@")"; fi
}
command -v docker >/dev/null || die "docker missing (install.sh installs docker.io)"
dk info >/dev/null 2>&1 || die "no access to docker: add yourself to the docker group (sudo usermod -aG docker $USER)"
df=$(sha256sum "$DIR/sandbox/Dockerfile" | cut -c1-16)
have=$(dk image inspect -f '{{index .Config.Labels "pda.dockerfile"}}' "$IMAGE" 2>/dev/null || true)
if [[ "$have" != "$df" ]]; then
  blog="$(mktemp)"
  dk build -q --label "pda.dockerfile=$df" -t "$IMAGE" "$DIR/sandbox" >"$blog" 2>&1 \
    || { tail -20 "$blog" >&2; rm -f "$blog"; die "sandbox image build failed"; }
  rm -f "$blog"
  ok "sandbox image $IMAGE built"
else
  ok "sandbox image $IMAGE up to date"
fi

command -v gh >/dev/null && ok "GitHub CLI found" \
  || echo "  ■ gh (GitHub CLI) not found: the agent's GitHub tools need it" >&2
ok "agent ready: it starts unconfigured; set the provider and keys in the PaperDesk app"
