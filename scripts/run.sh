#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo 'Run make init first.' >&2; exit 1; }
set -a
source .env
set +a
exec scripts/maven.sh spring-boot:run -Dspring-boot.run.profiles=local
