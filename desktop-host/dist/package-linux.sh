#!/usr/bin/env bash
# Package the Linux desktop app with jpackage, then wrap it in a single-file AppImage.
# Run AFTER `mvn -pl desktop-host -Pdist package` (under a JDK 21).
#   bash desktop-host/dist/package-linux.sh
# Output: desktop-host/target/QPlayer-x86_64.AppImage
set -euo pipefail
cd "$(dirname "$0")/../.."                      # repo root
DIST="desktop-host/dist"
T=desktop-host/target
APP="$T/app"
[ -f "$APP/qplayer.jar" ] || { echo "$APP/qplayer.jar not found — run 'mvn -pl desktop-host -Pdist package' first"; exit 1; }

# Version for the bundle metadata. CI passes the release tag; a local build falls
# back to the latest git tag, then 0.0.0. Leading "v" stripped either way.
VERSION="${QPLAYER_VERSION:-${GITHUB_REF_NAME:-}}"
VERSION="${VERSION#v}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  VERSION="${VERSION#v}"
fi
[ -n "$VERSION" ] || VERSION="0.0.0"
echo "packaging QPlayer $VERSION"

# Shared module list (see jre-modules.txt), comments stripped.
MODS=$(sed 's/#.*//' "$DIST/jre-modules.txt" | tr -d '[:blank:]' | grep . | paste -sd, -)

rm -rf "$T/pkg" "$T/AppDir"

# jpackage jlinks the runtime from --add-modules and emits qplayer/{bin,lib}.
# Skija/LWJGL keep extracting their own natives out of the jars in lib/app, so
# there is nothing to hand-place here.
jpackage --type app-image \
  --name qplayer --app-version "$VERSION" --vendor t1m3 --description "QPlayer" \
  --input "$APP" --main-jar qplayer.jar --main-class dev.t1m3.qplayer.desktop.app.Main \
  --dest "$T/pkg" \
  --add-modules "$MODS" \
  --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress=zip-6"

# AppDir = the jpackage image + the three things appimagetool insists on at the
# root: AppRun, a .desktop entry and a matching icon.
mv "$T/pkg/qplayer" "$T/AppDir"
rmdir "$T/pkg"

cat > "$T/AppDir/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/bin/qplayer" "$@"
EOF
chmod +x "$T/AppDir/AppRun"

cat > "$T/AppDir/qplayer.desktop" <<'EOF'
[Desktop Entry]
Name=QPlayer
Exec=qplayer
Icon=qplayer
Type=Application
Categories=AudioVideo;Audio;Player;
EOF
cp docs/icon.png "$T/AppDir/qplayer.png" 2>/dev/null || \
  cp shared-qml/app-icon.png "$T/AppDir/qplayer.png" 2>/dev/null || true

TOOL="${APPIMAGETOOL:-$T/appimagetool}"
if [ ! -x "$TOOL" ]; then
  curl -fL -o "$TOOL" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$TOOL"
fi
# APPIMAGE_EXTRACT_AND_RUN avoids needing FUSE (CI runners lack it).
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$T/AppDir" "$T/QPlayer-x86_64.AppImage"
echo "→ $T/QPlayer-x86_64.AppImage"
