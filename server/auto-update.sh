#!/bin/bash
# Keeps this server up to date by itself. Every 5 minutes it asks GitHub for the newest commit whose server tests
# passed (the "Server" workflow on main); when that is not what runs here, it copies that commit's server/ folder over
# this one and rebuilds the containers. .env and the database (a Docker volume) are never touched.
#
#   sudo bash auto-update.sh --install     once: a systemd timer runs it every 5 minutes (setup-vm.sh does this)
#   sudo bash auto-update.sh               update now
#   journalctl -u autoapply-update -n 30   what it did
set -euo pipefail
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
cd "$DIR"
REPO=${REPO:-AhmedSabet0/JSTB}

if [ "${1:-}" = "--install" ]; then
  cat > /etc/systemd/system/autoapply-update.service <<EOF
[Unit]
Description=Auto Apply server: update from GitHub
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/bin/bash $DIR/auto-update.sh
EOF
  cat > /etc/systemd/system/autoapply-update.timer <<'EOF'
[Unit]
Description=Auto Apply server: look for a new version every 5 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable --now autoapply-update.timer >/dev/null
  echo "The server now updates itself from github.com/$REPO every 5 minutes (log: journalctl -u autoapply-update)."
  exit 0
fi

command -v rsync >/dev/null 2>&1 || { apt-get update -qq && apt-get install -y -qq rsync >/dev/null; }

RUNS="https://api.github.com/repos/$REPO/actions/workflows/server.yml/runs?branch=main&status=success&per_page=1"
SHA=$(curl -fsS --max-time 20 -H "Accept: application/vnd.github+json" "$RUNS" \
  | python3 -c 'import json, sys; r = json.load(sys.stdin).get("workflow_runs") or []; print(r[0]["head_sha"] if r else "")')
[ -n "$SHA" ] || { echo "No tested server version on GitHub yet."; exit 0; }
[ "$SHA" = "$(cat .deployed 2>/dev/null || true)" ] && exit 0
[ "$SHA" = "$(cat .deploy_failed 2>/dev/null || true)" ] && exit 0   # that version did not start; wait for the next

echo "Updating the server to ${SHA:0:7}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --max-time 120 "https://codeload.github.com/$REPO/tar.gz/$SHA" | tar -xz -C "$TMP"
SRC=$(find "$TMP" -mindepth 2 -maxdepth 2 -type d -name server | head -n 1)
[ -n "$SRC" ] && [ -f "$SRC/docker-compose.yml" ] || { echo "No server folder in ${SHA:0:7}" >&2; exit 1; }
rsync -a --delete --exclude .env --exclude .deployed --exclude .deploy_failed --exclude tests --filter='P /auto-update.sh' \
  "$SRC"/ "$DIR"/

# Then restart the app on the new image for sure (Compose can keep a container when only build metadata changed).
if docker compose up -d --build --remove-orphans && docker compose up -d --no-deps --force-recreate app; then
  echo "$SHA" > .deployed
  rm -f .deploy_failed
  docker image prune -f >/dev/null 2>&1 || true
  echo "Running ${SHA:0:7}"
  ok=0
else
  echo "$SHA" > .deploy_failed
  echo "Version ${SHA:0:7} did not start; the running one stays." >&2
  ok=1
fi
chown -R "$(stat -c %u:%g "$DIR")" "$DIR"   # the files stay the folder owner's (ubuntu), as before
exit $ok
