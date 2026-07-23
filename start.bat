@echo off
cd /d "%~dp0"
powershell -NoLogo -ExecutionPolicy Bypass -Command "& { $cfg = $null; if (Test-Path 'tools\paths.json') { $cfg = Get-Content 'tools\paths.json' -Raw | ConvertFrom-Json }; $exe = 'python'; $exeArgs = @(); if (-not (Get-Command python -ErrorAction SilentlyContinue)) { $exe = 'py'; $exeArgs = @('-3') }; if ($cfg -and $cfg.python_exe) { $exe = $cfg.python_exe; $exeArgs = @($cfg.python_args) }; Start-Process -FilePath $exe -ArgumentList ($exeArgs + 'server.py') -WindowStyle Minimized; Start-Sleep -Seconds 2; Start-Process 'http://localhost:7360' }"
