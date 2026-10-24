#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -d .tools/jdk-25.0.5+8 ]]; then
  .tools/jdk-25.0.5+8/bin/java -version
  exit 0
fi
[[ $(uname -sm) == 'Linux x86_64' ]] || { echo 'This bootstrap supports Linux x86_64; install Temurin JDK 25 for your platform.' >&2; exit 1; }
mkdir -p .tools
archive=$(mktemp .tools/temurin.XXXXXX.tar.gz)
trap 'rm -f "$archive"' EXIT
curl -fL --retry 2 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz' -o "$archive"
echo "68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb  $archive" | sha256sum --check
tar -xzf "$archive" -C .tools
.tools/jdk-25.0.5+8/bin/java -version
