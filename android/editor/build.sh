#!/usr/bin/env bash
# Builds the CodeMirror bundle into the app's assets. Run after changing src/ (needs node; on the box:
# `source ~/.nvm/nvm.sh`). The output is committed, so a plain gradle build never needs node.
set -euo pipefail
cd "$(dirname "$0")"
[[ -d node_modules ]] || npm ci --no-audit --no-fund
OUT=../app/src/main/assets/editor
mkdir -p "$OUT"
npx esbuild src/editor.js --bundle --minify --format=iife --target=chrome100 --legal-comments=none --outfile="$OUT/editor.js"
cp src/index.html "$OUT/index.html"
ls -l "$OUT"
