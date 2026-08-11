$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$apk = Join-Path $PSScriptRoot "TRASH-TO-TRACK-v0.6.0-debug.apk"
# Always rebuild so INSTALL can never push a stale APK from an older source state.
& (Join-Path $PSScriptRoot "BUILD_DEBUG.ps1")

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb.exe not found: $adb" }

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "ADB install failed." }
Write-Host "INSTALL OK"
