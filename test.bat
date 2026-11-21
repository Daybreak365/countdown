@echo off
setlocal
title Countdown - Test
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\workspace.ps1" -Action Test %*
set "result=%errorlevel%"
echo.
if not "%result%"=="0" echo Test startup failed. See the message above.
pause
exit /b %result%
