# Desktop release (jpackage + jlink)

The desktop app ships as a **jpackage bundle**: the app jars plus a jlinked JRE
trimmed to the modules in [`jre-modules.txt`](jre-modules.txt). No JVM install
required on the user's machine, and — unlike the GraalVM native image this
replaced — it's an ordinary JVM at runtime, so the QML engine generates its
classes normally, Rhino JITs, AWT/Swing work, and Skija/LWJGL extract their own
natives out of the jars exactly like a dev run does. There is no reachability
metadata to maintain.

`jpackage` only targets the OS it runs on, so each platform is built on its own
machine / CI runner.

## Build (per platform, under a full JDK 21 — not a JRE)

```sh
# install the shared modules once
mvn -DskipTests -pl player-core -am install
# stage target/app: qplayer.jar (Class-Path -> lib/) + every runtime dep in lib/
mvn -DskipTests -pl desktop-host -Pdist package
```

## Package

```sh
bash       desktop-host/dist/package-linux.sh     # → target/QPlayer-x86_64.AppImage (single file)
bash       desktop-host/dist/package-macos.sh     # → target/QPlayer.dmg  (intel or apple-silicon by arch)
pwsh -File desktop-host/dist/package-windows.ps1  # → target/QPlayer/ + QPlayer-windows-x64.zip
```

Each script runs `jpackage` over `target/app`, then wraps the result in the
platform's usual container. `QPLAYER_VERSION` (or the latest git tag) sets the
version baked into the bundle metadata.

### Platform notes

- **macOS** needs `-XstartOnFirstThread` (GLFW must own thread 0; the java
  launcher otherwise runs `main` on a thread it spawns). The script passes it via
  `--java-options`. It also bumps a `0.x` version to `1.x` for `CFBundleVersion`
  only — Apple rejects a zero major, and the version the app *reports* comes from
  `version.properties`, not the bundle. The `.app`/`.dmg` is **unsigned**; for
  distribution it must be codesigned + notarized or Gatekeeper blocks it.
- **Windows** gets a GUI-subsystem launcher from jpackage, so double-clicking
  shows no console; `WinConsole` re-attaches to a parent terminal's console when
  started from a shell. CI additionally builds an Inno Setup installer
  (`qplayer.iss`) over the packaged `QPlayer/` folder.
- **Building with a GraalVM JDK** works, but its `jvmcicompiler.dll`/`.so` adds
  ~48 MB to the runtime. CI uses Temurin.
- The jpackage launcher passes the command line to `main()` rather than the JVM,
  so `Main` re-reads `-Dkey=value` args itself. The packaged app therefore takes
  the same knobs as a dev run: `qplayer.exe -Dqplayer.gfx=vulkan -Dqplayer.width=900`.

## Runtime modules

`jre-modules.txt` is the single source of truth, read by all three scripts. Most
of it comes from `jdeps --print-module-deps` over `target/app`; the rest is the
runtime-only set jdeps can't see. Regenerate the first part with:

```sh
jdeps --multi-release 21 --ignore-missing-deps --print-module-deps \
      desktop-host/target/app/qplayer.jar desktop-host/target/app/lib/*.jar
```

## CI release

`.github/workflows/release.yml` is the unified release pipeline: a `v*` tag push
builds the Android APK and the desktop bundle on an `ubuntu` / `windows` /
`macos-14` (Apple Silicon) matrix in parallel, and attaches every artifact to the
same GitHub Release.

> **Intel Mac**: GitHub retired the free Intel `macos-13` runner, so CI ships an
> Apple-Silicon `.dmg` only. Intel users build locally with `package-macos.sh`
> (it picks up the natives for the host arch through the Maven os profiles).
