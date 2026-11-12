#!/usr/bin/env bash
# PaperDesk installer for a Linux machine (Ubuntu/Debian; a small cloud VM is enough).
#
#   curl -fsSL https://raw.githubusercontent.com/joernmht/PaperDesk/main/install.sh | bash
#
# Safe to run again (updates the code, keeps your library, topics, token and config). It:
#   1. installs what it needs (git, python3 >= 3.11, venv) and fetches PaperDesk into ~/PaperDesk
#   2. creates your desk: a secret token, ~/.config/paperdesk/config.toml, a library folder ~/PaperDesk-library
#   3. installs Tailscale and connects this machine to YOUR tailnet (prints a sign-in link + QR code)
#   4. runs PaperDesk as a service on 127.0.0.1 and publishes it inside your tailnet only (https, port 8447)
#   5. prints a pairing QR code + code for the phone app
# Nothing is reachable from the public internet.
#
# Options:  --no-tailscale   you publish 127.0.0.1:8799 yourself (then set public_url in config.toml)
#           --no-service     do not install the systemd service (start with: paperdesk serve)
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
TS=1; SVC=1; UNINSTALL=0
for a in "$@"; do
  case "$a" in
    --no-tailscale) TS=0 ;;
    --no-service) SVC=0 ;;
    --uninstall) UNINSTALL=1 ;;
    -h|--help) sed -n '2,22p' "$0" 2>/dev/null || true; exit 0 ;;
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
  $SUDO systemctl disable --now paperdesk.service 2>/dev/null || true
  $SUDO rm -f /etc/systemd/system/paperdesk.service; $SUDO systemctl daemon-reload 2>/dev/null || true
  command -v tailscale >/dev/null && { tailscale serve --https=$HTTPS_PORT off 2>/dev/null || $SUDO tailscale serve --https=$HTTPS_PORT off 2>/dev/null || true; }
  rm -f "$HOME/.local/bin/paperdesk"
  ok "done. Your data stays in $CONF, $STATE and your library folder; delete them by hand if you want."
  exit 0
fi

if [[ -f "$CONF/config.toml" ]] && grep -qE '^\s*profile\s*=\s*"box"' "$CONF/config.toml"; then
  die "this machine runs PaperDesk in the \"box\" profile (Joern's research box); the installer leaves it alone"
fi

# ---------------------------------------------------------------- 1. packages + code
step "1/5  System packages and the PaperDesk code"
[[ "$(uname -s)" == Linux ]] || die "PaperDesk's installer supports Linux only"
need=()
command -v git >/dev/null || need+=(git)
command -v curl >/dev/null || need+=(curl)
command -v python3 >/dev/null || need+=(python3)
python3 -c 'import ensurepip, venv' 2>/dev/null || need+=(python3-venv)
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
[[ -d "$VENV" ]] || python3 -m venv "$VENV"
"$VENV/bin/pip" install -q --upgrade pip >/dev/null 2>&1 || true
"$VENV/bin/pip" install -q segno pymupdf >/dev/null 2>&1 && ok "QR codes + PDF full-text search ready" \
  || echo "  (optional extras segno/pymupdf did not install; PaperDesk works without them)"

# ---------------------------------------------------------------- 2. the desk
step "2/5  Your desk"
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

set_public_url() {
  python3 - "$CONF/config.toml" "$1" <<'PY'
import re, sys
f, url = sys.argv[1], sys.argv[2]
s = open(f).read()
s = re.sub(r'(?m)^public_url\s*=.*$', f'public_url = "{url}"', s, count=1) if re.search(r'(?m)^public_url\s*=', s) \
    else f'public_url = "{url}"\n' + s
open(f, "w").write(s)
PY
}

# ---------------------------------------------------------------- 3. tailscale
if [[ $TS == 1 ]]; then
  step "3/5  Tailscale (the private network between your phone and this machine)"
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
  set_public_url "https://$HOST:$HTTPS_PORT"
else
  step "3/5  Tailscale skipped (--no-tailscale): set public_url in $CONF/config.toml yourself"
fi

# ---------------------------------------------------------------- 4. service
if [[ $SVC == 1 ]] && command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
  step "4/5  PaperDesk service"
  $SUDO tee /etc/systemd/system/paperdesk.service >/dev/null <<EOF
[Unit]
Description=PaperDesk server
After=network-online.target
Wants=network-online.target

[Service]
User=$USER_NAME
Environment=HOME=$HOME
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$VENV/bin/python $DIR/server/paperdesk.py serve
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  $SUDO systemctl daemon-reload
  $SUDO systemctl enable -q paperdesk.service
  $SUDO systemctl restart paperdesk.service
  for _ in $(seq 20); do curl -fs -o /dev/null "http://127.0.0.1:$PORT/pair/x" 2>/dev/null; rc=$?
    [[ $rc == 22 || $rc == 0 ]] && break; sleep 0.5; done   # 22 = HTTP 403: it answers
  systemctl is-active -q paperdesk.service || die "service did not start: sudo journalctl -u paperdesk -n 30"
  ok "running on 127.0.0.1:$PORT (restarts on boot)"
else
  step "4/5  Service skipped: start it with  paperdesk serve"
fi

if [[ $TS == 1 ]]; then
  echo "  Publishing it inside your tailnet (https)…"
  echo "  If Tailscale asks you to enable HTTPS/Serve for your tailnet, open its link, enable it, and wait here."
  timeout 600 tailscale serve --bg --https=$HTTPS_PORT "http://127.0.0.1:$PORT" </dev/null >/dev/null \
    || die "tailscale serve failed; run: tailscale serve --bg --https=$HTTPS_PORT http://127.0.0.1:$PORT"
  for _ in $(seq 30); do   # the first request waits for the certificate
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$HOST:$HTTPS_PORT/pair/x" || true)
    [[ "$code" == 403 ]] && break; sleep 2
  done
  [[ "$code" == 403 ]] && ok "reachable at https://$HOST:$HTTPS_PORT (tailnet only)" \
    || echo "  (https not answering yet; the certificate can take a minute. The pairing link below will work once it does.)"
fi

# ---------------------------------------------------------------- 5. pairing
step "5/5  Connect your phone"
echo "  In the PaperDesk app, at 'Connect': scan this QR code, or type the address and code shown."
if [[ $SVC == 1 || $TS == 1 ]]; then "$HOME/.local/bin/paperdesk" pair || true; fi
echo "  Need a new code later?  paperdesk pair      Health check:  paperdesk status"
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) echo "  (open a new shell so ~/.local/bin is on your PATH)";; esac
