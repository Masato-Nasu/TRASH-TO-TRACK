@echo off
setlocal
cd /d "%~dp0"
call BUILD_DEBUG.bat
if errorlevel 1 exit /b %errorlevel%
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo adb.exe not found: %ADB%
  exit /b 1
)
"%ADB%" install -r "TRASH-TO-TRACK-v0.6.0-debug.apk"
if errorlevel 1 exit /b %errorlevel%
echo INSTALL OK
