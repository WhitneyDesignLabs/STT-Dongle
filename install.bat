@echo off
REM One-shot install of the STT Keyboard demo APK from Windows.
REM Double-click this file, or run it from a Command Prompt.
setlocal
set ADB=C:\platform-tools\adb.exe
set APK=%~dp0STT-Keyboard-debug.apk
set PKG=com.whitneydesignlabs.sttkeyboard

echo Devices:
"%ADB%" devices
echo Installing %APK% ...
"%ADB%" install -r "%APK%"
if %ERRORLEVEL% NEQ 0 (
  echo.
  echo INSTALL FAILED -- the phone is probably not authorized for USB debugging.
  echo On the phone: Settings ^> Developer options ^> USB debugging = ON,
  echo set USB mode to "File transfer", then accept the "Allow USB debugging?" prompt.
  pause
  exit /b 1
)
"%ADB%" shell am start -n %PKG%/.MainActivity
echo OK: installed and launched %PKG%
pause
