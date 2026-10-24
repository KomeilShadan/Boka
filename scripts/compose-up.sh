#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo 'Run make init first.' >&2; exit 1; }
service=${1:-app}
if [[ "$service" == app ]]; then
  docker compose -p boka up -d --build --force-recreate app
else
  docker compose -p boka up -d db
fi
for ((i=0; i<90; i++)); do
  id=$(docker compose -p boka ps -q "$service")
  if [[ -n "$id" ]]; then
    state=$(docker inspect --format '{{.State.Health.Status}}' "$id")
    if [[ "$state" == healthy ]]; then echo "$service is healthy"; exit 0; fi
    if [[ "$state" == unhealthy ]]; then break; fi
  fi
  sleep 2
done
echo "$service did not become healthy; inspect make logs / docker compose -p boka logs" >&2
exit 1
