@echo off
setlocal
cd /d "%~dp0"
if not exist "local.properties" if exist "%LOCALAPPDATA%\Android\Sdk" powershell -NoProfile -Command "$p='%LOCALAPPDATA%\Android\Sdk'.Replace('\','/'); Set-Content -Path 'local.properties' -Value ('sdk.dir=' + $p)"
echo TRASH TO TRACK v0.6.0 - DEBUG BUILD
call gradlew.bat assembleDebug
if errorlevel 1 exit /b %errorlevel%
copy /Y "app\build\outputs\apk\debug\app-debug.apk" "TRASH-TO-TRACK-v0.6.0-debug.apk" >nul
if errorlevel 1 exit /b %errorlevel%
echo.
echo BUILD OK
echo APK: %CD%\TRASH-TO-TRACK-v0.6.0-debug.apk
