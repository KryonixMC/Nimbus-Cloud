# ── Nimbus Cloud Installer (Windows) ────────────────────────────
# Usage: irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.ps1 | iex
#
# Downloads the latest Nimbus release and starts it once.
# The built-in setup wizard handles configuration and start script creation.
# ────────────────────────────────────────────────────────────────

$ErrorActionPreference = "Stop"

$RepoOwner = "NimbusPowered"
$RepoName = "Nimbus"
$InstallDir = "C:\Nimbus"
$JavaVersion = 21

function Write-Info($msg)    { Write-Host "[nimbus] " -ForegroundColor Cyan -NoNewline; Write-Host $msg }
function Write-Success($msg) { Write-Host "[nimbus] " -ForegroundColor Green -NoNewline; Write-Host $msg }
function Write-Warn($msg)    { Write-Host "[nimbus] " -ForegroundColor Yellow -NoNewline; Write-Host $msg }
function Write-Err($msg)     { Write-Host "[nimbus] " -ForegroundColor Red -NoNewline; Write-Host $msg }

function Show-Banner {
    Write-Host ""
    Write-Host "   _  __ __ _   __ ___  _ __  ___" -ForegroundColor Cyan
    Write-Host "  / |/ // // \,' // o.)/// /,' _/" -ForegroundColor Cyan
    Write-Host " / || // // \,' // o \/ U /_\ ``." -ForegroundColor Cyan
    Write-Host "/_/|_//_//_/ /_//___,'\_,'/___,'" -ForegroundColor Cyan
    Write-Host "            C L O U D" -ForegroundColor DarkGray
    Write-Host "         Installer" -ForegroundColor DarkGray
    Write-Host ""
}

# ── Check/install Java 21 ───────────────────────────────────────

function Test-Java {
    try {
        $output = & java -version 2>&1 | Select-Object -First 1
        if ($output -match '"(\d+)') {
            $ver = [int]$Matches[1]
            if ($ver -ge $JavaVersion) {
                Write-Success "Java $ver found"
                return $true
            }
            Write-Warn "Java $ver found, but Java $JavaVersion+ is required"
        }
    } catch {
        Write-Warn "Java not found"
    }
    return $false
}

function Install-Java {
    Write-Info "Installing Java $JavaVersion (Eclipse Temurin)..."

    # Detect architecture
    $arch = if ([Environment]::Is64BitOperatingSystem) { "x64" } else { "x86" }

    # Fetch latest Temurin release URL from Adoptium API
    $apiUrl = "https://api.adoptium.net/v3/assets/latest/$JavaVersion/hotspot?architecture=$arch&image_type=msi&os=windows&vendor=eclipse"

    try {
        $releases = Invoke-RestMethod -Uri $apiUrl -UseBasicParsing
        $msiUrl = $releases[0].binary.installer.link
        $msiName = $releases[0].binary.installer.name

        $tempMsi = Join-Path $env:TEMP $msiName
        Write-Info "Downloading Temurin JDK $JavaVersion..."
        Invoke-WebRequest -Uri $msiUrl -OutFile $tempMsi -UseBasicParsing

        Write-Info "Installing (this may take a moment)..."
        Start-Process msiexec.exe -ArgumentList "/i", "`"$tempMsi`"", "ADDLOCAL=FeatureMain,FeatureJavaHome,FeaturePath", "/quiet", "/norestart" -Wait -NoNewWindow

        Remove-Item $tempMsi -Force -ErrorAction SilentlyContinue

        # Refresh PATH
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path", "User")

        if (Test-Java) {
            Write-Success "Java $JavaVersion installed successfully"
        } else {
            Write-Err "Java installation completed but verification failed. You may need to restart your terminal."
        }
    } catch {
        Write-Err "Failed to install Java automatically: $_"
        Write-Err "Please install Java $JavaVersion manually from: https://adoptium.net/temurin/releases/"
        exit 1
    }
}

# ── Download Nimbus release ──────────────────────────────────────

function Install-Nimbus {
    Write-Info "Fetching available releases from GitHub..."

    try {
        $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$RepoOwner/$RepoName/releases?per_page=20" -UseBasicParsing
    } catch {
        Write-Err "Failed to fetch releases from GitHub: $_"
        exit 1
    }

    if ($releases.Count -eq 0) {
        Write-Err "No releases found on GitHub"
        exit 1
    }

    # Display available versions
    Write-Host ""
    Write-Info "Available versions:"
    $i = 1
    foreach ($rel in $releases) {
        Write-Host "    " -NoNewline
        Write-Host "$i)" -ForegroundColor Cyan -NoNewline
        Write-Host "  $($rel.tag_name)" -ForegroundColor White -NoNewline
        if ($rel.prerelease) { Write-Host " (pre-release)" -ForegroundColor DarkGray } else { Write-Host "" }
        $i++
    }
    Write-Host ""

    $selected = Read-Host "[nimbus] Select version [1]"
    if ([string]::IsNullOrEmpty($selected)) { $selected = "1" }

    $idx = [int]$selected - 1
    if ($idx -lt 0 -or $idx -ge $releases.Count) {
        Write-Err "Invalid selection: $selected"
        exit 1
    }

    $release = $releases[$idx]
    Write-Info "Selected: $($release.tag_name)"

    # Find controller JAR asset (nimbus-core-*.jar)
    $jarAsset = $release.assets | Where-Object { $_.name -like "nimbus-core-*.jar" } | Select-Object -First 1
    if (-not $jarAsset) {
        # Fallback: *controller*.jar (future naming)
        $jarAsset = $release.assets | Where-Object { $_.name -like "*controller*.jar" } | Select-Object -First 1
    }
    if (-not $jarAsset) {
        Write-Err "No JAR asset found in release $($release.tag_name)"
        exit 1
    }

    # Create install directory
    if (-not (Test-Path $InstallDir)) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }

    # Keep the original versioned filename (e.g. nimbus-core-0.4.2.jar)
    $script:NimbusJar = $jarAsset.name
    $jarPath = Join-Path $InstallDir $script:NimbusJar
    Write-Info "Downloading Nimbus $($release.tag_name)..."
    Invoke-WebRequest -Uri $jarAsset.browser_download_url -OutFile $jarPath -UseBasicParsing
    Write-Success "Downloaded to $jarPath"
}

# ── Main ────────────────────────────────────────────────────────

function Main {
    Show-Banner

    # Java
    if (-not (Test-Java)) {
        Install-Java
    }

    # Download
    Install-Nimbus

    Write-Host ""
    Write-Host "Nimbus downloaded successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Installation:  " -ForegroundColor Cyan -NoNewline; Write-Host $InstallDir
    Write-Host "  The setup wizard will guide you through configuration on first start." -ForegroundColor DarkGray
    Write-Host ""

    # Start Nimbus (setup wizard runs on first start)
    $startNow = Read-Host "[nimbus] Start Nimbus now? [Y/n]"
    if ($startNow -notin @("n", "no", "N", "No")) {
        Write-Host ""
        Write-Host "  Starting Nimbus..." -ForegroundColor DarkGray
        Write-Host ""
        Set-Location $InstallDir
        & java -Xms256M -Xmx256M "--enable-native-access=ALL-UNNAMED" -jar $script:NimbusJar
    } else {
        Write-Host "  To start manually:" -ForegroundColor DarkGray
        Write-Host "    cd $InstallDir; java -jar $script:NimbusJar" -ForegroundColor DarkGray
    }
    Write-Host ""
}

Main
