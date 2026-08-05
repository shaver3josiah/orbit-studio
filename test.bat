@echo off
REM Runs every check Orbit Studio has, in one go.
REM
REM Pure cmd on purpose, matching tour.bat and setup.bat: no PowerShell, so
REM Windows execution policy and the "downloaded from the internet" block never
REM get in the way.
REM
REM There were three checks and no way to run them together, so running "the
REM tests" meant remembering three separate command lines - and test_stdlib_boot
REM was not written down anywhere at all, which is the one guarding the property
REM that the server boots on a machine with no numpy and no Pillow.
REM
REM Nothing here is piped to nul. When a check fails, the reason has to be on
REM screen on the first run, not after a second diagnostic round trip.
REM
REM EnableDelayedExpansion and !FAILED! rather than %FAILED%: cmd expands a
REM %VAR% inside a parenthesised block when it PARSES the block, not when it
REM runs it, so an accumulator written the obvious way quietly forgets every
REM failure recorded inside an if/else and the script exits 0 with checks red.
setlocal EnableDelayedExpansion
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

set "FAILED="
set "SKIPPED="

echo.
echo ==^> pure helpers ^(node tests/tour_pano_test.mjs^)
echo.
REM A missing Node is a SKIP, not a failure. Counting it as one meant a machine
REM without Node - any locked-down work laptop - could never see a clean run, so
REM a real failure had nothing to stand out against.
where node >nul 2>nul
if errorlevel 1 (
  echo   SKIPPED: Node.js was not found on this PC. The panorama and plan-view
  echo   maths are checked by this one; install Node to run it.
  set "SKIPPED=!SKIPPED! pano-no-node"
) else (
  node tests/tour_pano_test.mjs
  if errorlevel 1 set "FAILED=!FAILED! pano"
)

echo.
echo ==^> server API ^(python tests/tour_smoke_test.py^)
echo.
%PY% tests/tour_smoke_test.py
if errorlevel 1 set "FAILED=!FAILED! smoke"

echo.
echo ==^> stdlib boot ^(python tests/test_stdlib_boot.py^)
echo.
%PY% tests/test_stdlib_boot.py
if errorlevel 1 set "FAILED=!FAILED! stdlib"

REM The splat side. This one drives the whole flythrough pipeline end to end -
REM equirect video in, frames, v360 reframing, bundle.zip out - plus keyframe
REM save/load and the .ply to .splat conversion the notebook's result lands on.
REM It was written but never wired in here, so "the tests" only ever meant the
REM tour. It needs numpy and Pillow; the tour checks above deliberately do not.
echo.
echo ==^> splat pipeline ^(python tests/smoke_test.py^)
echo.
%PY% tests/smoke_test.py
if errorlevel 1 set "FAILED=!FAILED! splat"

echo.
if defined SKIPPED echo   SKIPPED:!SKIPPED!
if defined FAILED (
  echo   FAILED:!FAILED!
  echo.
  pause
  exit /b 1
)
if defined SKIPPED (
  echo   Everything that could run here passed.
) else (
  echo   All checks passed.
)
echo.
pause
