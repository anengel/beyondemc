@echo off
rem Install Beyond EMC (plus its two required mods) into a Minecraft mods folder.
rem Double-click this file. ASCII only on purpose: cmd.exe mangles UTF-8.
setlocal
set "SCRIPT=%~dp0install-to-mods.ps1"

if not exist "%SCRIPT%" (
  echo ERROR: install-to-mods.ps1 not found next to this file.
  pause
  exit /b 1
)

where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)

echo.
pause
