# ── Nimbus Cloud Installer (Windows) ────────────────────────────
# Usage: irm https://raw.githubusercontent.com/jonax1337/Nimbus/main/install.ps1 | iex
# ────────────────────────────────────────────────────────────────

$ErrorActionPreference = "Stop"

$RepoOwner = "jonax1337"
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
        $pre = if ($rel.prerelease) { " (pre-release)" } else { "" }
        $preColor = if ($rel.prerelease) { "DarkGray" } else { "White" }
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

    # Find controller JAR asset
    $jarAsset = $release.assets | Where-Object { $_.name -like "*controller*.jar" } | Select-Object -First 1
    if (-not $jarAsset) {
        $jarAsset = $release.assets | Where-Object { $_.name -like "*-all.jar" } | Select-Object -First 1
    }
    if (-not $jarAsset) {
        $jarAsset = $release.assets | Where-Object { $_.name -like "nimbus*.jar" } | Select-Object -First 1
    }
    if (-not $jarAsset) {
        Write-Err "No JAR asset found in release $($release.tag_name)"
        exit 1
    }

    # Create install directory
    if (-not (Test-Path $InstallDir)) {
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    }

    $jarPath = Join-Path $InstallDir "nimbus.jar"
    Write-Info "Downloading Nimbus $($release.tag_name)..."
    Invoke-WebRequest -Uri $jarAsset.browser_download_url -OutFile $jarPath -UseBasicParsing
    Write-Success "Downloaded to $jarPath"
}

# ── Create start script ─────────────────────────────────────────

function New-StartScript {
    Write-Info "Creating start scripts..."

    # nimbus.bat
    $batContent = @"
@echo off
cd /d "%~dp0"

set JAVA_OPTS=-Xms512M -Xmx1G
set JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200
set JAVA_OPTS=%JAVA_OPTS% -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC
set JAVA_OPTS=%JAVA_OPTS% -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40
set JAVA_OPTS=%JAVA_OPTS% -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20
set JAVA_OPTS=%JAVA_OPTS% -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15
set JAVA_OPTS=%JAVA_OPTS% -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5
set JAVA_OPTS=%JAVA_OPTS% -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1

java %JAVA_OPTS% -jar nimbus.jar %*
"@
    Set-Content -Path (Join-Path $InstallDir "nimbus.bat") -Value $batContent -Encoding ASCII

    # nimbus.ps1 (PowerShell alternative)
    $ps1Content = @'
Set-Location $PSScriptRoot

$javaOpts = @(
    "-Xms512M", "-Xmx1G",
    "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
    "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC",
    "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
    "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
    "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15",
    "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5",
    "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
    "-jar", "nimbus.jar"
)

& java @javaOpts @args
'@
    Set-Content -Path (Join-Path $InstallDir "nimbus.ps1") -Value $ps1Content -Encoding UTF8

    # Add to PATH if not already there
    $currentPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
    if ($currentPath -notlike "*$InstallDir*") {
        [System.Environment]::SetEnvironmentVariable("Path", "$currentPath;$InstallDir", "User")
        $env:Path = "$env:Path;$InstallDir"
        Write-Success "Added $InstallDir to PATH"
    }

    Write-Success "Created nimbus.bat and nimbus.ps1"
}

# ── Create Windows service (optional) ───────────────────────────

function New-NimbusService {
    $answer = Read-Host "[nimbus] Create Windows service for auto-start? [y/N]"
    if ($answer -notin @("y", "yes", "Y", "Yes")) {
        return
    }

    # Check for admin rights
    $isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        Write-Warn "Creating a Windows service requires administrator rights."
        Write-Warn "Please re-run as Administrator or create the service manually."
        return
    }

    $serviceName = "Nimbus"
    $javaPath = (Get-Command java).Source
    $jarPath = Join-Path $InstallDir "nimbus.jar"

    # Use sc.exe to create service
    & sc.exe create $serviceName binPath= "`"$javaPath`" -Xms512M -Xmx1G -jar `"$jarPath`"" start= auto DisplayName= "Nimbus Cloud"
    & sc.exe description $serviceName "Nimbus Minecraft Cloud System"

    Write-Success "Windows service '$serviceName' created"
    Write-Info "  Start:   sc.exe start Nimbus"
    Write-Info "  Stop:    sc.exe stop Nimbus"
    Write-Info "  Status:  sc.exe query Nimbus"
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

    # Start scripts
    New-StartScript

    # Windows service (optional)
    New-NimbusService

    Write-Host ""
    Write-Host "Nimbus installed successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Installation:  " -ForegroundColor Cyan -NoNewline; Write-Host $InstallDir
    Write-Host "  Start:         " -ForegroundColor Cyan -NoNewline; Write-Host "nimbus.bat"
    Write-Host "  Or:            " -ForegroundColor Cyan -NoNewline; Write-Host "cd $InstallDir && .\nimbus.bat"
    Write-Host ""
    Write-Host "  On first start, Nimbus will run the setup wizard." -ForegroundColor DarkGray
    Write-Host ""
}

Main
