#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ ! -e .env ]]; then
  (set -o noclobber; : > .env)
fi
added=0
while IFS='=' read -r key value; do
  [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || continue
  if ! grep -q "^${key}=" .env; then
    if [[ "$key" == *PASSWORD ]]; then
      value=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
    fi
    printf '%s=%s\n' "$key" "$value" >> .env
    added=$((added + 1))
  fi
done < .env.example
printf 'Added %s missing local settings; existing values preserved.\n' "$added"
