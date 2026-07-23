$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
New-Item -ItemType Directory -Force -Path (Join-Path $root "tools") | Out-Null

Write-Host ""
Write-Host "Orbit Studio setup starting..." -ForegroundColor Cyan

function Get-PythonCommand {
    $candidates = @(@("py", "-3.12"), @("py", "-3.11"), @("py", "-3"), @("python3"), @("python"))
    foreach ($candidate in $candidates) {
        $exe = $candidate[0]
        $exeArgs = @($candidate | Select-Object -Skip 1)
        if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { continue }
        try {
            $versionText = (& $exe @exeArgs --version) 2>&1
            if ($versionText -match "Python 3\.(\d+)" -and [int]$Matches[1] -ge 9) {
                return ,$candidate
            }
        } catch { continue }
    }
    return $null
}

$pythonCmd = Get-PythonCommand
if (-not $pythonCmd) {
    Write-Host "No usable Python 3.9 or newer was found on this machine." -ForegroundColor Red
    Write-Host "Install it with this command, then run setup.ps1 again:" -ForegroundColor Yellow
    Write-Host "    winget install Python.Python.3.12" -ForegroundColor Yellow
    exit 1
}
$pyExe = $pythonCmd[0]
$pyArgs = @($pythonCmd | Select-Object -Skip 1)
Write-Host "Using Python: $pyExe $pyArgs" -ForegroundColor Green

Write-Host "Installing numpy and pillow..." -ForegroundColor Cyan
& $pyExe @pyArgs -m pip install --quiet numpy pillow
if ($LASTEXITCODE -ne 0) {
    Write-Host "System-wide install failed, retrying with --user..." -ForegroundColor Yellow
    & $pyExe @pyArgs -m pip install --quiet --user numpy pillow
}

$ffmpegBin = $null
$existingFfmpeg = Get-Command ffmpeg -ErrorAction SilentlyContinue
if ($existingFfmpeg) {
    $ffmpegBin = Split-Path -Parent $existingFfmpeg.Source
    Write-Host "Found ffmpeg already on PATH." -ForegroundColor Green
} else {
    $localFfmpeg = Get-ChildItem -Path (Join-Path $root "tools\ffmpeg") -Recurse -Filter "ffmpeg.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localFfmpeg) {
        $ffmpegBin = $localFfmpeg.DirectoryName
        Write-Host "Using ffmpeg downloaded in a previous run." -ForegroundColor Green
    } else {
        Write-Host "ffmpeg not found. Downloading a local copy, this takes a minute..." -ForegroundColor Cyan
        $ffmpegDir = Join-Path $root "tools\ffmpeg"
        New-Item -ItemType Directory -Force -Path $ffmpegDir | Out-Null
        $zipPath = Join-Path $root "tools\ffmpeg.zip"
        Invoke-WebRequest -Uri "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip" -OutFile $zipPath
        Expand-Archive -Path $zipPath -DestinationPath $ffmpegDir -Force
        Remove-Item $zipPath
        $found = Get-ChildItem -Path $ffmpegDir -Recurse -Filter "ffmpeg.exe" | Select-Object -First 1
        if ($found) {
            $ffmpegBin = $found.DirectoryName
            Write-Host "ffmpeg installed to $ffmpegBin" -ForegroundColor Green
        } else {
            Write-Host "ffmpeg download did not produce ffmpeg.exe. Video prep may fail later." -ForegroundColor Red
        }
    }
}

$pathsInfo = [ordered]@{
    python_exe  = $pyExe
    python_args = $pyArgs
    ffmpeg_bin  = $ffmpegBin
}
$pathsInfo | ConvertTo-Json | Set-Content -Path (Join-Path $root "tools\paths.json")
Write-Host "Wrote tools\paths.json" -ForegroundColor Green

$demoSplat = Join-Path $root "demo\demo.splat"
$demoScript = Join-Path $root "make_demo.py"
if (-not (Test-Path $demoSplat)) {
    if (Test-Path $demoScript) {
        Write-Host "Building the demo scene..." -ForegroundColor Cyan
        & $pyExe @pyArgs $demoScript
    } else {
        Write-Host "Demo generator not present yet, skipping demo build." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "+----------------------------------------------------+" -ForegroundColor Green
Write-Host "|  Orbit Studio is ready.                             |" -ForegroundColor Green
Write-Host "|  Double-click start.bat to launch the studio.       |" -ForegroundColor Green
Write-Host "|  It opens at http://localhost:7360                  |" -ForegroundColor Green
Write-Host "+----------------------------------------------------+" -ForegroundColor Green
Write-Host ""
