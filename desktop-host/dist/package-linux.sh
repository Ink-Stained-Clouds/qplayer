#!/usr/bin/env bash
# Package the Linux native image into a single-file AppImage.
# Run AFTER `mvn -pl desktop-host -Pnative package` (under a GraalVM JDK).
#   bash desktop-host/dist/package-linux.sh
# Output: desktop-host/target/QPlayer-x86_64.AppImage
set -euo pipefail
cd "$(dirname "$0")/../.."                      # repo root
T=desktop-host/target
APPDIR="$T/AppDir"
BIN="$T/qplayer"
[ -x "$BIN" ] || { echo "native binary $BIN not found — run 'mvn -pl desktop-host -Pnative package' first"; exit 1; }

rm -rf "$APPDIR"
mkdir -p "$APPDIR/native-libs"

# 1) the native binary + the JDK native libs native-image emitted next to it.
# IMPORTANT: the JDK libs (incl. the libjvm/libjava shims) must sit in the SAME
# directory as the binary — that's where the native image resolves them.
cp "$BIN" "$APPDIR/qplayer"
cp "$T"/*.so "$APPDIR/" 2>/dev/null || true

# 2) Skija + LWJGL native libs go in a SEPARATE dir: if libskija.so sits beside the
# libjvm/libjava shims, the loader binds its JVM symbols to the (stub) shim instead
# of the main binary and System.load fails. Keeping them apart mirrors the layout
# that works when running the bare binary. Pulled straight from the local Maven
# repo (already populated by the build).
M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
found=0
for jar in \
  "$M2"/io/github/humbleui/skija-linux-x64/*/skija-linux-x64-*.jar \
  "$M2"/org/lwjgl/*/*/*-natives-linux.jar; do
  [ -f "$jar" ] || continue
  case "$jar" in *sources*|*javadoc*) continue ;; esac
  unzip -o -j "$jar" '*.so' -d "$APPDIR/native-libs" >/dev/null 2>&1 && found=$((found+1))
done
[ "$(ls -A "$APPDIR/native-libs" 2>/dev/null)" ] || { echo "no Skija/LWJGL .so found under $M2"; exit 1; }
echo "bundled $(ls "$APPDIR/native-libs"/*.so | wc -l) Skija/LWJGL libs"

# 3) launcher: JDK libs beside the binary (LD_LIBRARY_PATH), Skija/LWJGL pointed at
# the separate dir via their own properties.
cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
export LD_LIBRARY_PATH="$HERE:${LD_LIBRARY_PATH:-}"
exec "$HERE/qplayer" \
  -Dskija.library.path="$HERE/native-libs" \
  -Dorg.lwjgl.librarypath="$HERE/native-libs" \
  "$@"
EOF
chmod +x "$APPDIR/AppRun" "$APPDIR/qplayer"

# 4) desktop entry + icon
cat > "$APPDIR/qplayer.desktop" <<'EOF'
[Desktop Entry]
Name=QPlayer
Exec=qplayer
Icon=qplayer
Type=Application
Categories=AudioVideo;Audio;Player;
EOF
cp docs/icon.png "$APPDIR/qplayer.png" 2>/dev/null || \
  cp shared-qml/app-icon.png "$APPDIR/qplayer.png" 2>/dev/null || true

# 5) build the AppImage
TOOL="${APPIMAGETOOL:-$T/appimagetool}"
if [ ! -x "$TOOL" ]; then
  curl -fL -o "$TOOL" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
  chmod +x "$TOOL"
fi
# APPIMAGE_EXTRACT_AND_RUN avoids needing FUSE (CI runners lack it).
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$T/QPlayer-x86_64.AppImage"
echo "→ $T/QPlayer-x86_64.AppImage"
