# Package the Windows native image into a portable zip.
# Run AFTER `mvn -pl desktop-host -Pnative package` (under a GraalVM JDK) on Windows.
#   powershell -ExecutionPolicy Bypass -File desktop-host\dist\package-windows.ps1
# Output: desktop-host\target\QPlayer-windows-x64.zip
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path "$PSScriptRoot\..\.."
Set-Location $repo
$T = "desktop-host\target"
$bin = "$T\qplayer.exe"
if (-not (Test-Path $bin)) { throw "native binary $bin not found - run the native build first" }

$dir = "$T\QPlayer"
if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
New-Item -ItemType Directory -Force -Path $dir | Out-Null

# binary + the JDK native DLLs native-image emits next to it
Copy-Item $bin "$dir\qplayer.exe"
Copy-Item "$T\*.dll" $dir -ErrorAction SilentlyContinue

# Skija + LWJGL native DLLs straight from the local Maven repo (Windows resolves
# DLLs from the exe directory, so everything goes next to qplayer.exe).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$m2 = if ($env:MAVEN_REPO_LOCAL) { $env:MAVEN_REPO_LOCAL } else { "$env:USERPROFILE\.m2\repository" }
$jars = @()
$jars += Get-ChildItem "$m2\io\github\humbleui\skija-windows-x64" -Recurse -Filter 'skija-windows-x64-*.jar' -ErrorAction SilentlyContinue
$jars += Get-ChildItem "$m2\org\lwjgl" -Recurse -Filter '*-natives-windows.jar' -ErrorAction SilentlyContinue
foreach ($j in $jars) {
  if ($j.Name -match 'sources|javadoc') { continue }
  $zip = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
  foreach ($e in $zip.Entries) {
    if ($e.Name -like '*.dll') {
      [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, "$dir\$($e.Name)", $true)
    }
  }
  $zip.Dispose()
}
if (-not (Get-ChildItem "$dir\*.dll" -ErrorAction SilentlyContinue)) { throw "no Skija/LWJGL DLLs found under $m2" }

# launcher: point Skija + LWJGL at the bundled DLLs (Windows finds the JDK DLLs in
# the exe directory automatically).
@"
@echo off
setlocal
set HERE=%~dp0
"%HERE%qplayer.exe" -Dskija.library.path="%HERE%." -Dorg.lwjgl.librarypath="%HERE%." %*
"@ | Set-Content -Encoding ascii "$dir\QPlayer.cmd"

Compress-Archive -Path "$dir\*" -DestinationPath "$T\QPlayer-windows-x64.zip" -Force
Write-Host "-> $T\QPlayer-windows-x64.zip"
