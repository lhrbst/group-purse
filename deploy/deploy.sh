#!/usr/bin/env bash
# Läuft auf dem Server als Benutzer "deploy" in /opt/gruppenkasse. Aufruf: ./deploy.sh <image-tag>
# Setzt den Image-Tag, startet den Stack neu und prüft /actuator/health. Antwortet die App nicht innerhalb von
# 2 Minuten, wird der vorige Tag wiederhergestellt. Die Geheimnisse liegen in secrets.env (von der Pipeline geschrieben).
set -euo pipefail

cd /opt/gruppenkasse
NEW_TAG="${1:?Image-Tag fehlt}"
[[ -f secrets.env ]] || { echo "secrets.env fehlt" >&2; exit 1; }

PREV_TAG=""
if [[ -f .env ]]; then
  PREV_TAG="$(grep -E '^IMAGE_TAG=' .env | head -1 | cut -d= -f2- | tr -d "'" || true)"
fi

write_env() {
  umask 077
  { cat secrets.env; printf "IMAGE_TAG='%s'\n" "$1"; } > .env.tmp
  mv .env.tmp .env
}

healthy() {
  docker compose exec -T app wget -qO- http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'
}

start() {
  docker compose pull --quiet app
  docker compose up -d --remove-orphans
}

echo "Deploy von Tag ${NEW_TAG} (vorher: ${PREV_TAG:-keiner})"
write_env "$NEW_TAG"
start

for _ in $(seq 1 24); do
  if healthy; then
    echo "App ist gesund."
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 5
done

echo "App antwortet nicht innerhalb von 2 Minuten." >&2
docker compose logs --tail=80 app >&2 || true
if [[ -n "$PREV_TAG" && "$PREV_TAG" != "$NEW_TAG" ]]; then
  echo "Setze auf Tag ${PREV_TAG} zurück." >&2
  write_env "$PREV_TAG"
  start || true
  docker image prune -f >/dev/null || true
fi
exit 1
