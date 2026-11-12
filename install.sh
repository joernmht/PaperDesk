#!/usr/bin/env bash
# PaperDesk installer for a Linux machine (Ubuntu/Debian; a small cloud VM is enough).
#
#   curl -fsSL https://raw.githubusercontent.com/joernmht/PaperDesk/main/install.sh | bash
#
# Safe to run again (updates the code, keeps your library, topics, token and config). It:
#   1. installs what it needs (git, python3 >= 3.11, venv, Docker) and fetches PaperDesk into ~/PaperDesk
#   2. creates your desk: a secret token, ~/.config/paperdesk/config.toml, a library folder ~/PaperDesk-library
#   3. installs LaTeX (TinyTeX, in your home) so the app can build your papers' PDFs
#   4. installs Tailscale and connects this machine to YOUR tailnet (prints a sign-in link + QR code)
#   5. runs PaperDesk and the chat agent as services on 127.0.0.1, published inside your tailnet only
#      (https ports 8447 and 8448)
#   6. prints a pairing QR code + code for the phone app; the app then asks for your keys and papers
# Nothing is reachable from the public internet.
#
# Options:  --no-tailscale   you publish 127.0.0.1:8799 yourself (then set public_url in config.toml)
#           --no-service     do not install the systemd services (start with: paperdesk serve)
#           --no-latex       skip TinyTeX (no PDF builds)
#           --no-agent       skip the chat agent (and Docker)
#           --uninstall      stop and remove the service + the `paperdesk` command (keeps your data)
# Env:      PAPERDESK_REPO (git URL), PAPERDESK_DIR (default ~/PaperDesk), PAPERDESK_BRANCH (default main)
set -euo pipefail

REPO="${PAPERDESK_REPO:-https://github.com/joernmht/PaperDesk.git}"
DIR="${PAPERDESK_DIR:-$HOME/PaperDesk}"
BRANCH="${PAPERDESK_BRANCH:-main}"
CONF="$HOME/.config/paperdesk"
STATE="$HOME/.local/state/paperdesk"
VENV="$HOME/.local/share/paperdesk/venv"
PORT=8799
HTTPS_PORT=8447
AGENT_PORT=8800
AGENT_HTTPS=8448
TS=1; SVC=1; UNINSTALL=0; LATEX=1; AGENT=1
for a in "$@"; do
  case "$a" in
    --no-tailscale) TS=0 ;;
    --no-service) SVC=0 ;;
    --no-latex) LATEX=0 ;;
    --no-agent) AGENT=0 ;;
    --uninstall) UNINSTALL=1 ;;
    -h|--help) sed -n '2,27p' "$0" 2>/dev/null || true; exit 0 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

B=$'\e[1m'; R=$'\e[0m'; RED=$'\e[31m'; BLUE=$'\e[34m'; YEL=$'\e[33m'
[[ -t 1 ]] || { B=; R=; RED=; BLUE=; YEL=; }
step() { echo; echo "${YEL}▲${R} ${B}$*${R}"; }
ok()   { echo "  ${BLUE}●${R} $*"; }
die()  { echo "  ${RED}■${R} $*" >&2; exit 1; }

SUDO=""
if [[ $EUID -ne 0 ]]; then
  command -v sudo >/dev/null || die "needs root or sudo for the system packages"
  SUDO="sudo"
fi
USER_NAME="$(id -un)"

if [[ $UNINSTALL == 1 ]]; then
  step "Removing the PaperDesk service"
  for u in paperdesk paperdesk-agent; do
    $SUDO systemctl disable --now $u.service 2>/dev/null || true
    $SUDO rm -f /etc/systemd/system/$u.service
  done
  $SUDO systemctl daemon-reload 2>/dev/null || true
  if command -v tailscale >/dev/null; then
    for port in $HTTPS_PORT $AGENT_HTTPS; do
      tailscale serve --https=$port off 2>/dev/null || $SUDO tailscale serve --https=$port off 2>/dev/null || true
    done
  fi
  rm -f "$HOME/.local/bin/paperdesk"
  ok "done. Your data stays in $CONF, $STATE and your library folder; delete them by hand if you want."
  exit 0
fi

if [[ -f "$CONF/config.toml" ]] && grep -qE '^\s*profile\s*=\s*"box"' "$CONF/config.toml"; then
  die "this machine runs PaperDesk in the \"box\" profile (Joern's research box); the installer leaves it alone"
fi

# ---------------------------------------------------------------- 1. packages + code
step "1/6  System packages and the PaperDesk code"
[[ "$(uname -s)" == Linux ]] || die "PaperDesk's installer supports Linux only"
need=()
command -v git >/dev/null || need+=(git)
command -v curl >/dev/null || need+=(curl)
command -v python3 >/dev/null || need+=(python3)
python3 -c 'import ensurepip, venv' 2>/dev/null || need+=(python3-venv)
command -v perl >/dev/null || need+=(perl)
if [[ $AGENT == 1 ]]; then
  command -v docker >/dev/null || need+=(docker.io)
  command -v gh >/dev/null || need+=(gh)
fi
if ((${#need[@]})); then
  command -v apt-get >/dev/null || die "please install: ${need[*]} (no apt-get here)"
  $SUDO apt-get update -qq && DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq "${need[@]}" >/dev/null
fi
python3 -c 'import sys; sys.exit(sys.version_info < (3, 11))' || die "python 3.11 or newer needed (Ubuntu 24.04 has it)"
if [[ -d "$DIR/.git" ]]; then
  git -C "$DIR" pull -q --ff-only || echo "  (could not update $DIR; using what is there)"
else
  git clone -q --depth 1 -b "$BRANCH" "$REPO" "$DIR" || die "could not download $REPO"
fi
ok "code in $DIR"
if [[ $AGENT == 1 ]]; then
  $SUDO systemctl enable --now docker >/dev/null 2>&1 || true
  id -nG "$USER_NAME" | grep -qw docker || $SUDO usermod -aG docker "$USER_NAME"
  ok "Docker ready (the chat agent's sandbox)"
fi
[[ -d "$VENV" ]] || python3 -m venv "$VENV"
"$VENV/bin/pip" install -q --upgrade pip >/dev/null 2>&1 || true
"$VENV/bin/pip" install -q segno pymupdf >/dev/null 2>&1 && ok "QR codes + PDF full-text search ready" \
  || echo "  (optional extras segno/pymupdf did not install; PaperDesk works without them)"

# ---------------------------------------------------------------- 2. the desk
step "2/6  Your desk"
mkdir -p "$CONF" "$STATE" "$HOME/.local/bin"
chmod 700 "$CONF"
if [[ ! -s "$CONF/token" ]]; then
  ( umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$CONF/token" )
  ok "new secret token in $CONF/token"
fi
if [[ ! -f "$CONF/config.toml" ]]; then
  cat > "$CONF/config.toml" <<EOF
# PaperDesk settings. Restart after editing: sudo systemctl restart paperdesk
profile = "portable"
name = "$(hostname -s)"
port = $PORT
public_url = ""          # filled in by install.sh (your tailnet https address)

[library]
dir = "$HOME/PaperDesk-library"   # library.bib + pdfs/, readable by Zotero, JabRef and LaTeX

[remarkable]
enabled = "auto"         # connect it from the app (Settings > reMarkable); "false" hides it
folder = "/Papers/To read"

[watch]
per_topic = 6            # new papers per topic and run
days = 45                # how far back to look
every_hours = 24

# "Include in ..." copies a paper's BibTeX entry into a project's .bib file. One block per project:
# [[targets]]
# id = "thesis"
# label = "My thesis"
# bib = "$HOME/thesis/references.bib"
EOF
  ok "settings in $CONF/config.toml"
else
  ok "keeping your settings in $CONF/config.toml"
fi
cat > "$HOME/.local/bin/paperdesk" <<EOF
#!/usr/bin/env bash
exec "$VENV/bin/python" "$DIR/server/paperdesk.py" "\$@"
EOF
chmod +x "$HOME/.local/bin/paperdesk"
ok "command: paperdesk pair | status"

set_config() {   # set_config KEY VALUE: a top-level string setting in config.toml
  python3 - "$CONF/config.toml" "$1" "$2" <<'PY'
import re, sys
f, key, val = sys.argv[1:4]
s = open(f).read()
line = f'{key} = "{val}"'
if re.search(rf'(?m)^{key}\s*=', s):
    s = re.sub(rf'(?m)^{key}\s*=.*$', line, s, count=1)
else:                       # top-level keys must come before the first [table]
    i = s.find("\n[")
    s = s + "\n" + line + "\n" if i < 0 else s[:i] + "\n" + line + s[i:]
open(f, "w").write(s)
PY
}

# ---------------------------------------------------------------- 3. LaTeX
if [[ $LATEX == 1 ]]; then
  step "3/6  LaTeX (TinyTeX in ~/.TinyTeX, for PDF builds of your papers)"
  if ! ls "$HOME"/.TinyTeX/bin/*/latexmk >/dev/null 2>&1; then
    curl -fsSL https://yihui.org/tinytex/install-bin-unix.sh | sh >/dev/null 2>&1 || die "TinyTeX did not install"
    TLMGR="$(ls "$HOME"/.TinyTeX/bin/*/tlmgr | head -1)"
    "$TLMGR" install latexmk >/dev/null 2>&1 || true
  fi
  ok "LaTeX ready (missing packages install themselves on the first build)"
else
  step "3/6  LaTeX skipped (--no-latex)"
fi

# ---------------------------------------------------------------- 4. tailscale
if [[ $TS == 1 ]]; then
  step "4/6  Tailscale (the private network between your phone and this machine)"
  if ! command -v tailscale >/dev/null; then
    curl -fsSL https://tailscale.com/install.sh | $SUDO sh >/dev/null 2>&1 || die "Tailscale did not install"
  fi
  $SUDO systemctl enable --now tailscaled >/dev/null 2>&1 || true
  if ! tailscale status >/dev/null 2>&1; then
    echo "  Sign in with the SAME Tailscale account as on your phone. Open the link below"
    echo "  (or scan the QR code with your phone's camera):"
    $SUDO tailscale up --qr --operator="$USER_NAME" </dev/null || die "Tailscale sign-in did not finish"
  else
    $SUDO tailscale set --operator="$USER_NAME" >/dev/null 2>&1 || true
  fi
  HOST="$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
  [[ -n "$HOST" ]] || die "Tailscale has no name for this machine (is MagicDNS on in your tailnet?)"
  ok "this machine is $HOST in your tailnet"
  set_config public_url "https://$HOST:$HTTPS_PORT"
else
  step "4/6  Tailscale skipped (--no-tailscale): set public_url in $CONF/config.toml yourself"
fi

# ---------------------------------------------------------------- 5. services
if [[ $SVC == 1 ]] && command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
  step "5/6  PaperDesk services"
  $SUDO tee /etc/systemd/system/paperdesk.service >/dev/null <<EOF
[Unit]
Description=PaperDesk server
After=network-online.target
Wants=network-online.target

[Service]
User=$USER_NAME
Environment=HOME=$HOME
Environment=PATH=$HOME/.local/bin:$HOME/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$VENV/bin/python $DIR/server/paperdesk.py serve
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  $SUDO systemctl daemon-reload
  $SUDO systemctl enable -q paperdesk.service
  $SUDO systemctl restart paperdesk.service
  for _ in $(seq 20); do   # 22 = HTTP 403 from /pair/x: the server answers
    rc=0; curl -fs -o /dev/null "http://127.0.0.1:$PORT/pair/x" 2>/dev/null || rc=$?
    [[ $rc == 22 || $rc == 0 ]] && break; sleep 0.5; done
  systemctl is-active -q paperdesk.service || die "service did not start: sudo journalctl -u paperdesk -n 30"
  ok "running on 127.0.0.1:$PORT (restarts on boot)"
  if [[ $AGENT == 1 ]]; then
    sg docker -c "bash '$DIR/agent/install_agent.sh'" || die "chat agent setup failed (see above)"
    $SUDO tee /etc/systemd/system/paperdesk-agent.service >/dev/null <<UNIT
[Unit]
Description=PaperDesk chat agent
After=network-online.target docker.service paperdesk.service
Wants=network-online.target

[Service]
User=$USER_NAME
SupplementaryGroups=docker
Environment=HOME=$HOME
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
WorkingDirectory=$DIR/agent
ExecStart=$HOME/.local/share/paperdesk/agent-venv/bin/python -I $DIR/agent/agentd.py serve --port $AGENT_PORT
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT
    $SUDO systemctl daemon-reload
    $SUDO systemctl enable -q paperdesk-agent.service
    $SUDO systemctl restart paperdesk-agent.service
    sleep 3
    if systemctl is-active -q paperdesk-agent.service; then
      ok "chat agent running on 127.0.0.1:$AGENT_PORT (the app sets its model)"
    else
      echo "  (chat agent did not start: sudo journalctl -u paperdesk-agent -n 30)"
    fi
  fi
else
  step "5/6  Services skipped: start with  paperdesk serve"
fi

if [[ $TS == 1 ]]; then
  echo "  Publishing it inside your tailnet (https)…"
  echo "  If Tailscale asks you to enable HTTPS/Serve for your tailnet, open its link, enable it, and wait here."
  timeout 600 tailscale serve --bg --https=$HTTPS_PORT "http://127.0.0.1:$PORT" </dev/null >/dev/null \
    || die "tailscale serve failed; run: tailscale serve --bg --https=$HTTPS_PORT http://127.0.0.1:$PORT"
  if [[ $AGENT == 1 && $SVC == 1 ]]; then
    if timeout 120 tailscale serve --bg --https=$AGENT_HTTPS "http://127.0.0.1:$AGENT_PORT" </dev/null >/dev/null; then
      set_config agent_url "https://$HOST:$AGENT_HTTPS"
    else
      echo "  (could not publish the chat agent on :$AGENT_HTTPS)"
    fi
  fi
  for _ in $(seq 30); do   # the first request waits for the certificate
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$HOST:$HTTPS_PORT/pair/x" || true)
    [[ "$code" == 403 ]] && break; sleep 2
  done
  [[ "$code" == 403 ]] && ok "reachable at https://$HOST:$HTTPS_PORT (tailnet only)" \
    || echo "  (https not answering yet; the certificate can take a minute. The pairing link below will work once it does.)"
fi

# ---------------------------------------------------------------- 5. pairing
if [[ $SVC == 1 ]] && systemctl is-active -q paperdesk.service 2>/dev/null; then
  $SUDO systemctl restart paperdesk.service   # picks up public_url / agent_url
  sleep 2
fi
step "6/6  Connect your phone"
echo "  In the PaperDesk app, at 'Connect': scan this QR code, or type the address and code shown."
if [[ $SVC == 1 || $TS == 1 ]]; then "$HOME/.local/bin/paperdesk" pair || true; fi
echo "  Need a new code later?  paperdesk pair      Health check:  paperdesk status"
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) echo "  (open a new shell so ~/.local/bin is on your PATH)";; esac
