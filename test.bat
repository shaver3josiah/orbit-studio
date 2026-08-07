@echo off
REM Runs every check Orbit Studio has, in one go.
REM
REM Pure cmd on purpose, matching tour.bat and setup.bat: no PowerShell, so
REM Windows execution policy and the "downloaded from the internet" block never
REM get in the way.
REM
REM WHAT THE CHECKS ARE now lives in tests/run_all.py, not here. There were two
REM places that knew the list - this file, for a person double-clicking, and
REM nothing at all for a machine - so the checks ran when somebody remembered.
REM Twice in one afternoon that turned out not to be often enough. CI runs the
REM same file, so a check added there is a check both of them get.
REM
REM What stayed is what a batch file is actually for: finding Python on a PC
REM that may not have it on PATH, and holding the window open afterwards so a
REM double-click shows its result instead of flashing past.
REM
REM Nothing is piped to nul. When a check fails, the reason has to be on screen
REM on the first run, not after a second diagnostic round trip.
setlocal
cd /d "%~dp0"
title Orbit Studio checks

set "PY="
where python >nul 2>nul && set "PY=python"
if not defined PY (
  where py >nul 2>nul && set "PY=py -3"
)
if not defined PY (
  echo.
  echo   Python 3 is required, but it was not found on this PC.
  echo   Install it from https://www.python.org/downloads/ and tick
  echo   "Add python.exe to PATH" on the first screen.
  echo.
  pause
  exit /b 1
)

%PY% tests\run_all.py
set "RC=%ERRORLEVEL%"

echo.
pause
exit /b %RC%
