$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
if (-not (Test-Path (Join-Path $PSScriptRoot "local.properties")) -and (Test-Path $sdk)) {
    $sdkProp = ($sdk -replace "\\", "/")
    Set-Content (Join-Path $PSScriptRoot "local.properties") "sdk.dir=$sdkProp"
}

Write-Host "TRASH TO TRACK v0.6.0 - DEBUG BUILD"
& .\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$src = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
$dst = Join-Path $PSScriptRoot "TRASH-TO-TRACK-v0.6.0-debug.apk"
if (-not (Test-Path $src)) { throw "APK not found: $src" }
Copy-Item $src $dst -Force
Write-Host ""
Write-Host "BUILD OK"
Write-Host "APK: $dst"
