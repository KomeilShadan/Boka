#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .env ]]; then
  echo '.env exists; preserving all values.'
  exit 0
fi
umask 077
secret=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
(set -o noclobber; sed "s/replace-with-generated-local-secret/$secret/" .env.example > .env)
echo 'Created private .env with a generated database password.'
