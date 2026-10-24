#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -d .tools/jdk-25.0.5+8 ]]; then
  export JAVA_HOME="$PWD/.tools/jdk-25.0.5+8"
fi
export MAVEN_USER_HOME="${MAVEN_USER_HOME:-$PWD/.tools/maven}"
exec ./mvnw "$@"
