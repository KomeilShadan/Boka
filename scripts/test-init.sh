#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

test_dir=$(mktemp -d)
trap 'rm -rf -- "$test_dir"' EXIT
mkdir "$test_dir/scripts"
cp scripts/init.sh "$test_dir/scripts/"
cp .env.example "$test_dir/"
cd "$test_dir"

printf '%s\n' 'export APP_PORT=18080' > .env
printf '%s' 'POSTGRES_DB=existing_boka' >> .env
bash scripts/init.sh
[[ $(sed -n '1p' .env) == 'export APP_PORT=18080' ]]
[[ $(sed -n '2p' .env) == 'POSTGRES_DB=existing_boka' ]]
[[ $(grep -Ec '^(export )?APP_PORT=' .env) == 1 ]]
[[ $(grep -c '^POSTGRES_DB=' .env) == 1 ]]
grep -Eq '^POSTGRES_PASSWORD=[0-9a-f]{48}$' .env
grep -Eq '^BOKA_RESERVATION_HOLD_DURATION=PT5M$' .env

# A complete file needs no edits, even when its final newline is missing.
printf '%s' "$(cat .env)" > expected.env
cp expected.env .env
bash scripts/init.sh
cmp .env expected.env
