@echo off
REM Type the STT dongle's serial stream into the focused window (software HID).
REM Double-click, then quickly click the field you want the dictation to land in.
REM The dongle must be on a Windows COM port (usbipd-detached from WSL) and running
REM the BLE-test firmware in CLEAN_SERIAL mode.
setlocal
set PY=C:\Python314\python.exe
"%PY%" "%~dp0serial_type.py" %*
pause
