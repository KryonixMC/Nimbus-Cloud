# ── Nimbus CLI Installer (Windows) ──────────────────────────────
# Usage: irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.ps1 | iex

$ErrorActionPreference = "Stop"

$RepoOwner = "NimbusPowered"
$RepoName = "Nimbus"
$InstallDir = "$env:USERPROFILE\.nimbus"

function Write-Info($msg)    { Write-Host "[nimbus-cli] $msg" -ForegroundColor Cyan }
function Write-Success($msg) { Write-Host "[nimbus-cli] $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "[nimbus-cli] $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "[nimbus-cli] $msg" -ForegroundColor Red }

Write-Host ""
Write-Host "   Nimbus Remote CLI" -ForegroundColor Cyan
Write-Host "   Installer" -ForegroundColor DarkGray
Write-Host ""

# ── Java check ─────────────────────────────────────────────────

try {
    $javaVer = (java -version 2>&1 | Select-Object -First 1) -replace '.*"(\d+).*', '$1'
    if ([int]$javaVer -lt 21) {
        throw "old"
    }
    Write-Info "Java $javaVer found"
} catch {
    Write-Err "Java 21+ is required but not found."
    Write-Info "Download from: https://adoptium.net/temurin/releases/?version=21"
    exit 1
}

# ── Download latest release ────────────────────────────────────

Write-Info "Fetching latest release..."

$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$RepoOwner/$RepoName/releases/latest"
$version = $release.tag_name

if (-not $version) {
    Write-Err "Could not determine latest version."
    exit 1
}

Write-Info "Latest version: $version"

$asset = $release.assets | Where-Object { $_.name -like "nimbus-cli*" } | Select-Object -First 1

if (-not $asset) {
    Write-Err "No CLI JAR found in release $version."
    exit 1
}

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

$jarPath = "$InstallDir\nimbus-cli.jar"
Write-Info "Downloading $($asset.name)..."
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $jarPath

Write-Success "Downloaded to $jarPath"

# ── Create batch wrapper ───────────────────────────────────────

$batPath = "$InstallDir\nimbus-cli.bat"
Set-Content -Path $batPath -Value '@echo off
java -jar "%USERPROFILE%\.nimbus\nimbus-cli.jar" %*'

# ── Add to PATH ────────────────────────────────────────────────

$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notlike "*$InstallDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$InstallDir", "User")
    Write-Info "Added $InstallDir to PATH (restart terminal to take effect)"
}

Write-Host ""
Write-Success "Nimbus CLI installed successfully!"
Write-Host ""
Write-Info "Quick start:"
Write-Host "  nimbus-cli --host <controller-ip> --port 8080 --token <api-token>" -ForegroundColor White
Write-Host ""
Write-Info "Save connection for reuse:"
Write-Host "  nimbus-cli --host <ip> --token <token> --save-profile prod" -ForegroundColor White
Write-Host "  nimbus-cli --profile prod" -ForegroundColor White
Write-Host ""
