#!/bin/bash
# One-time setup on a fresh Ubuntu VM (Oracle, Azure, AWS...): swap, firewall, Docker, the .env file, then start the
# server. Run it from ~/autoapply-server (GitHub Actions deploys there):  bash setup-vm.sh
# Running it again is safe: it keeps the existing .env and only rebuilds and restarts the containers.
set -euo pipefail
cd "$(dirname "$0")"

say() { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }

# Small VMs have 1 GB of RAM: 2 GB of swap keeps the Docker build from running out of memory.
if ! swapon --show 2>/dev/null | grep -q /swapfile; then
  say "Adding 2 GB of swap"
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

# Oracle Cloud's Ubuntu images reject everything but SSH in iptables: let the web in (ports 80 and 443).
if sudo iptables -S INPUT 2>/dev/null | grep -q -- '-j REJECT'; then
  say "Opening ports 80 and 443 in the firewall"
  for rule in "-p tcp --dport 80" "-p tcp --dport 443" "-p udp --dport 443"; do
    # shellcheck disable=SC2086
    sudo iptables -C INPUT $rule -j ACCEPT 2>/dev/null || sudo iptables -I INPUT $rule -j ACCEPT
  done
  if command -v netfilter-persistent >/dev/null 2>&1; then sudo netfilter-persistent save >/dev/null 2>&1 || true; fi
fi

if ! command -v docker >/dev/null 2>&1; then
  say "Installing Docker"
  curl -fsSL https://get.docker.com | sudo sh
fi

if [ ! -f .env ]; then
  say "Server settings"
  read -rp "Domain (for example 145-241-153-177.sslip.io, your IP with dashes): " DOMAIN
  DOMAIN=${DOMAIN#https://}
  DOMAIN=${DOMAIN%/}
  read -rp "Contact email (shown on the privacy policy): " CONTACT
  read -rp "Your name or company (shown on the privacy policy): " OPERATOR
  ADMIN=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-24)
  cat > .env <<EOF
DOMAIN=$DOMAIN
ADMIN_PASSWORD=$ADMIN
CONTACT_EMAIL=$CONTACT
OPERATOR_NAME=$OPERATOR
APP_NAME=Auto Apply
TIMEZONE=Africa/Cairo
OPENROUTER_KEY=
OPENROUTER_MODEL=openai/gpt-4o-mini
EOF
  chmod 600 .env
  echo "Admin password: $ADMIN"
  echo "(saved in $(pwd)/.env; you can change it there, then run this script again)"
fi

DOMAIN=$(grep '^DOMAIN=' .env | cut -d= -f2-)
MYIP=$(curl -fsS --max-time 5 https://api.ipify.org || true)
DNSIP=$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk 'NR==1 {print $1}' || true)
if [ -n "$MYIP" ] && [ "$DNSIP" != "$MYIP" ]; then
  echo
  echo "Warning: $DOMAIN points to '${DNSIP:-nothing}', but this server's public IP is $MYIP."
  echo "The HTTPS certificate only works once the domain points here (with sslip.io: your IP with dashes, like ${MYIP//./-}.sslip.io)."
fi

say "Building and starting the server"
sudo docker compose up -d --build

say "Waiting for https://$DOMAIN"
for _ in $(seq 1 60); do
  # --resolve: ask Caddy on this machine (some clouds do not route a VM's own public IP back to it)
  if curl -fsS --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/health" >/dev/null 2>&1; then
    echo "Running: https://$DOMAIN/health answers OK"
    echo "Admin page: https://$DOMAIN/admin"
    say "Updates"
    sudo bash auto-update.sh --install
    exit 0
  fi
  sleep 5
done
echo "Not answering over HTTPS yet. Check that ports 80 and 443 are open (Oracle: the subnet's Security List needs"
echo "ingress rules for TCP 80 and 443 from 0.0.0.0/0) and that the domain points here, then look at:"
echo "  sudo docker compose logs caddy"
echo "  sudo docker compose logs app"
exit 1
