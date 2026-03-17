#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose.yml"
UPSTREAM_FILE="$ROOT_DIR/deploy/nginx/conf.d/upstream-active.conf"
DRAIN_SECONDS="${DRAIN_SECONDS:-30}"

active_color="$(grep -oE 'pacemeter-(blue|green):8080' "$UPSTREAM_FILE" | sed 's/pacemeter-//; s/:8080//' || true)"
if [[ "$active_color" == "blue" ]]; then
  inactive_color="green"
  inactive_port="18082"
else
  active_color="green"
  inactive_color="blue"
  inactive_port="18081"
fi

echo "[deploy] active=$active_color inactive=$inactive_color"

docker compose -f "$COMPOSE_FILE" up -d --build "pacemeter-$inactive_color"

echo "[deploy] waiting for pacemeter-$inactive_color readiness on localhost:$inactive_port/ready"
for _ in $(seq 1 60); do
  status_code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${inactive_port}/ready" || true)"
  if [[ "$status_code" == "200" ]]; then
    echo "[deploy] new container responded with HTTP $status_code"
    break
  fi
  sleep 2
done

status_code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${inactive_port}/ready" || true)"
if [[ "$status_code" != "200" ]]; then
  echo "[deploy] new container did not become ready" >&2
  exit 1
fi

printf 'server pacemeter-%s:8080;\n' "$inactive_color" > "$UPSTREAM_FILE"
docker compose -f "$COMPOSE_FILE" exec -T nginx nginx -s reload

echo "[deploy] traffic switched to $inactive_color"

echo "[deploy] draining old container for ${DRAIN_SECONDS}s"
sleep "$DRAIN_SECONDS"

docker compose -f "$COMPOSE_FILE" stop "pacemeter-$active_color" >/dev/null 2>&1 || true
echo "[deploy] stopped old container $active_color"
