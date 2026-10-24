#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
failed=0
for tool in git make curl docker; do
  command -v "$tool" || { echo "Missing $tool"; failed=1; }
done
git --version
make --version | head -n 1
curl --version | head -n 1
uname -sm
free -h
scripts/maven.sh --version || failed=1
docker version || failed=1
docker compose version || failed=1
[[ -f .env ]] || { echo 'Missing .env: run make init'; failed=1; }
if [[ -f .env ]]; then
  source .env
  for port in "${APP_PORT:-8080}" "${POSTGRES_PORT:-5432}"; do
    if ss -H -ltn "sport = :$port" | rg -q .; then
      echo "Port $port is occupied (expected if this stack is running)."
    fi
  done
fi
exit "$failed"
