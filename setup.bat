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

REM Finish with the preflight verdict rather than leaving the user to wonder.
REM Setup can succeed at every step it attempts and still leave a machine that
REM cannot run this - a blocked ffmpeg, a numpy wheel that never existed for the
REM installed Python - and none of that is visible until a capture fails much
REM later for reasons that look like bad photos.
set "PY="
where python >nul 2>nul && set "PY=python"
if not defined PY (
  where py >nul 2>nul && set "PY=py -3"
)
if defined PY (
  echo.
  echo ==^> Checking this machine can actually run Orbit Studio
  echo.
  %PY% "%~dp0preflight.py"
)

echo.
pause
