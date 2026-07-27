@echo off
REM Orbit Studio (splat pipeline) setup that survives a GitHub ZIP download.
REM
REM Windows blocks downloaded .ps1 scripts ("Mark-of-the-Web"), which is why
REM double-clicking setup.ps1 crashes instantly. This .bat is pure cmd, so the
REM PowerShell execution policy never applies to it; it clears the block on the
REM downloaded files and then runs setup.ps1 with the block bypassed.
REM
REM NOTE: for the 360 virtual tours you do NOT need any of this. Just run
REM tour.bat (or: python server.py) and open http://localhost:7360/tour
cd /d "%~dp0"

echo Clearing the "downloaded from the internet" block on these files...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-ChildItem -LiteralPath '%~dp0' -Recurse -File | Unblock-File" 2>nul

echo Running setup...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"

echo.
pause
