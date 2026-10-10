#!/usr/bin/env bash
# Richtet einen frischen Debian/Ubuntu-Server für die Gruppenkasse ein. Idempotent, als root ausführen:
#   ssh root@SERVER 'bash -s -- "ssh-ed25519 AAAA... deploy@github"' < deploy/setup.sh
# Das Argument ist der öffentliche SSH-Schlüssel des Deploy-Benutzers (der private liegt als GitHub Secret SSH_KEY).
set -euo pipefail

DEPLOY_KEY="${1:-}"
APP_DIR=/opt/gruppenkasse

if [[ $EUID -ne 0 ]]; then
  echo "Bitte als root ausführen." >&2
  exit 1
fi
if [[ -z "$DEPLOY_KEY" ]]; then
  echo "Fehlt: öffentlicher SSH-Schlüssel für den Benutzer 'deploy' als erstes Argument." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl ufw unattended-upgrades fail2ban

echo "== Docker =="
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

echo "== Deploy-Benutzer =="
if ! id deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash deploy
fi
usermod -aG docker deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
touch /home/deploy/.ssh/authorized_keys
grep -qxF "$DEPLOY_KEY" /home/deploy/.ssh/authorized_keys || echo "$DEPLOY_KEY" >> /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 600 /home/deploy/.ssh/authorized_keys
install -d -m 750 -o deploy -g deploy "$APP_DIR"

echo "== Swap-Datei 1 GB =="
if ! swapon --show | grep -q '/swapfile'; then
  if [[ ! -f /swapfile ]]; then
    fallocate -l 1G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=1024
    chmod 600 /swapfile
    mkswap /swapfile
  fi
  swapon /swapfile
fi
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf
sysctl -p /etc/sysctl.d/99-swappiness.conf >/dev/null

echo "== Firewall: nur 22, 80, 443 =="
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 443/udp
ufw --force enable

echo "== SSH nur per Schlüssel =="
if [[ -s /root/.ssh/authorized_keys || -s /home/deploy/.ssh/authorized_keys ]]; then
  cat > /etc/ssh/sshd_config.d/99-gruppenkasse.conf <<'SSHD'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
SSHD
  sshd -t
  systemctl reload ssh || systemctl reload sshd
else
  echo "WARNUNG: Es ist kein SSH-Schlüssel hinterlegt, die Passwortanmeldung bleibt aus Sicherheitsgründen für den Notfall an." >&2
fi

echo "== Automatische Sicherheitsupdates =="
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
APT
systemctl enable --now unattended-upgrades
systemctl enable --now fail2ban

echo
echo "Fertig. Nächste Schritte: DNS-A-Record auf diese IP setzen, GitHub Secrets hinterlegen, auf main pushen."
