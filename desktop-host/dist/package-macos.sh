#!/usr/bin/env bash
# Package the macOS native image into QPlayer.app + a .dmg.
# Run AFTER `mvn -pl desktop-host -Pnative package` (under a GraalVM JDK) on macOS.
#   bash desktop-host/dist/package-macos.sh
# Output: desktop-host/target/QPlayer.dmg
#
# NOTE: unsigned. For distribution outside your own machine the .app must be
# codesigned + notarized (Apple Developer ID), otherwise Gatekeeper blocks it.
set -euo pipefail
cd "$(dirname "$0")/../.."
T=desktop-host/target
BIN="$T/qplayer"
[ -x "$BIN" ] || { echo "native binary $BIN not found — run the native build first"; exit 1; }

# Version for Info.plist. CI passes QPLAYER_VERSION (the release tag, like the
# Windows installer step); a local build falls back to the latest git tag, then
# to 0.0.0. Leading "v" stripped either way.
VERSION="${QPLAYER_VERSION:-${GITHUB_REF_NAME:-}}"
VERSION="${VERSION#v}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null || true)"
  VERSION="${VERSION#v}"
fi
[ -n "$VERSION" ] || VERSION="0.0.0"
echo "packaging QPlayer $VERSION"

APP="$T/QPlayer.app"
LIBS="$APP/Contents/lib"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$LIBS"

# Pick the natives matching this Mac's architecture.
if [ "$(uname -m)" = "arm64" ]; then SKP=macos-arm64; LWP=natives-macos-arm64; else SKP=macos-x64; LWP=natives-macos; fi

# binary + the JDK native libs (native-image emits *.dylib next to the binary; keep
# them next to the binary in Contents/MacOS where it resolves them).
cp "$BIN" "$APP/Contents/MacOS/qplayer-bin"
cp "$T"/*.dylib "$APP/Contents/MacOS/" 2>/dev/null || true

# Skija + LWJGL dylibs go in a separate Contents/lib (same shim-shadowing reason as
# Linux), pulled from the local Maven repo.
M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
for jar in \
  "$M2"/io/github/humbleui/skija-$SKP/*/skija-$SKP-*.jar \
  "$M2"/org/lwjgl/*/*/*-$LWP.jar; do
  [ -f "$jar" ] || continue
  case "$jar" in *sources*|*javadoc*) continue ;; esac
  unzip -o -j "$jar" '*.dylib' -d "$LIBS" >/dev/null 2>&1 || true
done
[ "$(ls -A "$LIBS" 2>/dev/null)" ] || { echo "no Skija/LWJGL .dylib found under $M2"; exit 1; }

# launcher (CFBundleExecutable): set the dylib path then exec the binary. A native
# image runs main() on thread 0, so GLFW's macOS main-thread rule is already met.
cat > "$APP/Contents/MacOS/qplayer" <<'EOF'
#!/bin/sh
HERE="$(cd "$(dirname "$0")" && pwd)"
export DYLD_LIBRARY_PATH="$HERE/../lib:${DYLD_LIBRARY_PATH:-}"
exec "$HERE/qplayer-bin" \
  -Dskija.library.path="$HERE/../lib" \
  -Dorg.lwjgl.librarypath="$HERE/../lib" \
  "$@"
EOF
chmod +x "$APP/Contents/MacOS/qplayer" "$APP/Contents/MacOS/qplayer-bin"

# App icon: macOS wants a multi-resolution .icns, not a loose PNG, and the bundle
# only shows it when Info.plist names it via CFBundleIconFile. Build the .icns
# from the 512px source with sips + iconutil (both ship with macOS). If that
# fails for any reason, fall back to the bare PNG so packaging still succeeds.
ICON_SRC="docs/icon.png"
ICON_OK=""
if command -v sips >/dev/null && command -v iconutil >/dev/null && [ -f "$ICON_SRC" ]; then
  SET="$T/qplayer.iconset"
  rm -rf "$SET"; mkdir -p "$SET"
  # name:px pairs (16/32/128/256/512 pt, each + @2x, capped at the 512px source).
  for pair in 16x16:16 16x16@2x:32 32x32:32 32x32@2x:64 \
              128x128:128 128x128@2x:256 256x256:256 256x256@2x:512 512x512:512; do
    sips -z "${pair##*:}" "${pair##*:}" "$ICON_SRC" --out "$SET/icon_${pair%%:*}.png" >/dev/null 2>&1
  done
  if iconutil -c icns "$SET" -o "$APP/Contents/Resources/qplayer.icns" 2>/dev/null; then
    ICON_OK=1
  fi
  rm -rf "$SET"
fi
[ -n "$ICON_OK" ] || cp "$ICON_SRC" "$APP/Contents/Resources/qplayer.png" 2>/dev/null || true

cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>QPlayer</string>
  <key>CFBundleExecutable</key><string>qplayer</string>
  <key>CFBundleIdentifier</key><string>dev.t1m3.qplayer</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleIconFile</key><string>qplayer</string>
  <key>CFBundleShortVersionString</key><string>${VERSION}</string>
  <key>CFBundleVersion</key><string>${VERSION}</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
EOF

# .dmg
hdiutil create -volname QPlayer -srcfolder "$APP" -ov -format UDZO "$T/QPlayer.dmg"
echo "→ $T/QPlayer.dmg"
