# Builds dist\Saylo.exe (single file, no console window), then dist\Saylo-Setup.exe if Inno Setup 6
# is installed (winget install JRSoftware.InnoSetup). Run from this folder: .\build.ps1
# The GROQ_API_KEY line from .env is packed in as the built-in default key (used when the user skips
# the key step). Anyone with the exe can extract it: only share builds with people you trust.
Set-Location $PSScriptRoot
$addData = ""
$keyLine = if (Test-Path .env) { Select-String -Path .env -Pattern '^GROQ_API_KEY=.+' | Select-Object -First 1 }
if ($keyLine) {
    New-Item -ItemType Directory -Force build | Out-Null
    Set-Content -Path build\bundled.env -Value $keyLine.Line -Encoding ascii
    $addData = "--add-data build\bundled.env;."
} else {
    Write-Warning "No GROQ_API_KEY in .env: building without a built-in key (users must enter their own)."
}
# PyInstaller logs to stderr; cmd /c keeps PowerShell 5.1 from treating that as an error.
cmd /c ".\.venv\Scripts\python.exe -m PyInstaller --noconfirm --onefile --windowed --name Saylo --icon assets\saylo.ico --collect-submodules pynput --collect-submodules pystray --add-data ui;ui $addData app.py 2>&1"
$code = $LASTEXITCODE
Remove-Item -Recurse -Force build, Saylo.spec -ErrorAction SilentlyContinue
if ($code -ne 0) { throw "PyInstaller failed ($code)" }
Write-Host "Built: $PSScriptRoot\dist\Saylo.exe"

$iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
          "${env:ProgramFiles(x86)}\Inno Setup 7\ISCC.exe", "$env:ProgramFiles\Inno Setup 7\ISCC.exe",
          "$env:ProgramFiles\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    Write-Warning "Inno Setup 6 not found: skipped the installer (winget install JRSoftware.InnoSetup)."
    return
}
# SAYLO_VERSION (set by the release workflow) overrides the version in installer.iss.
$verArg = if ($env:SAYLO_VERSION) { "/DAppVersion=$env:SAYLO_VERSION" } else { "" }
cmd /c "`"$iscc`" /Q $verArg installer.iss 2>&1"
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed ($LASTEXITCODE)" }
Write-Host "Built: $PSScriptRoot\dist\Saylo-Setup.exe"
