@echo off
setlocal
title Countdown - Build Launcher
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\workspace.ps1" -Action Build %*
set "result=%errorlevel%"
echo.
if not "%result%"=="0" echo Launcher build failed. See the message above.
pause
exit /b %result%
