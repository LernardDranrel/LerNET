#!/usr/bin/env bash
# Pack x86_64/x86 from the Leadaxe AAR for the box AVD. Does not replace the
# arm64 phone APK (artifacts/LerNet-debug.apk).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

./gradlew --no-daemon :app:assembleDebug -PemuAbis=true "$@"

src="app/build/outputs/apk/debug/app-debug.apk"
dest_repo="artifacts/LerNet-debug-emu.apk"
dest_opt="/opt/cursor/artifacts/LerNet-debug-emu.apk"
mkdir -p artifacts
cp -f "$src" "$dest_repo"
if [[ -d /opt/cursor/artifacts ]]; then
    cp -f "$src" "$dest_opt"
fi
sha256sum "$dest_repo" | awk '{print $1"  /opt/cursor/artifacts/LerNet-debug-emu.apk"}' \
    >artifacts/LerNet-debug-emu.apk.sha256
sha256sum "$dest_repo" "$dest_opt" 2>/dev/null || sha256sum "$dest_repo"
unzip -l "$dest_repo" | grep 'lib/.*/libbox.so'
