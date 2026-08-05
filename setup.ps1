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
    & $pyExe @pyArgs -m pip install --user numpy pillow
}
# Say which of the two actually landed. --quiet hid a failing numpy install on a
# machine where pillow succeeded, and the first sign of it was every photo upload
# failing much later for reasons that looked nothing like a missing library.
foreach ($lib in @("numpy", "PIL")) {
    & $pyExe @pyArgs -c "import $lib" 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  $lib ready" -ForegroundColor Green
    } elseif ($lib -eq "numpy") {
        Write-Host "  numpy did NOT install. 360 photo sets and bundling still work without it;" -ForegroundColor Yellow
        Write-Host "  you need it only to import a trained .splat back in. Retry later with:" -ForegroundColor Yellow
        Write-Host "      $pyExe $pyArgs -m pip install numpy" -ForegroundColor Yellow
    } else {
        Write-Host "  pillow did NOT install - photo capture prep needs it. Retry with:" -ForegroundColor Red
        Write-Host "      $pyExe $pyArgs -m pip install pillow" -ForegroundColor Red
    }
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
        # Non-fatal: an org proxy may block this download. Video prep needs
        # ffmpeg, but the 360 tours and photo lanes do not, so a failure here
        # must not sink the whole setup.
        try {
            Invoke-WebRequest -Uri "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip" -OutFile $zipPath
            Expand-Archive -Path $zipPath -DestinationPath $ffmpegDir -Force
            Remove-Item $zipPath
            $found = Get-ChildItem -Path $ffmpegDir -Recurse -Filter "ffmpeg.exe" | Select-Object -First 1
            if ($found) {
                $ffmpegBin = $found.DirectoryName
                Write-Host "ffmpeg installed to $ffmpegBin" -ForegroundColor Green
            } else {
                Write-Host "ffmpeg download did not produce ffmpeg.exe. Video prep may fail later." -ForegroundColor Yellow
            }
        } catch {
            Write-Host "Could not download ffmpeg ($($_.Exception.Message))." -ForegroundColor Yellow
            Write-Host "That is fine unless you plan to use the video-to-splat lane; tours do not need it." -ForegroundColor Yellow
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

# Downloading ffmpeg is not the same as being allowed to RUN it. On a managed
# machine AppLocker denies execution from user-writable paths, so a copy sitting in
# Downloads fails with WinError 5 while looking perfectly installed - and setup used
# to record that blocked path and call it done. fix_ffmpeg.py proves execution and
# relocates to a permitted folder if it has to, so the ZIP can stay where it landed.
if ($ffmpegBin) {
    Write-Host ""
    Write-Host "Checking ffmpeg is allowed to run here..." -ForegroundColor Cyan
    & $pyExe @pyArgs (Join-Path $root "fix_ffmpeg.py")
}

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
Write-Host "|                                                     |" -ForegroundColor Green
Write-Host "|  Just want the 360 tours? You did not even need     |" -ForegroundColor Green
Write-Host "|  this - run tour.bat and open /tour instead.        |" -ForegroundColor Green
Write-Host "+----------------------------------------------------+" -ForegroundColor Green
Write-Host ""
