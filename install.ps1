# ── Nimbus Cloud Installer (Windows) ────────────────────────────
# Usage: irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.ps1 | iex
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

    # Keep the original versioned filename (e.g. nimbus-core-0.1.2.jar)
    $jarName = $jarAsset.name
    $jarPath = Join-Path $InstallDir $jarName
    Write-Info "Downloading Nimbus $($release.tag_name)..."
    Invoke-WebRequest -Uri $jarAsset.browser_download_url -OutFile $jarPath -UseBasicParsing
    Write-Success "Downloaded to $jarPath"
}

# ── Create start script ─────────────────────────────────────────

function New-StartScript {
    Write-Info "Creating start scripts..."

    # nimbus.bat — finds latest versioned JAR by semver, restart loop on exit code 10
    $batContent = @"
@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set JAVA_OPTS=-Xms512M -Xmx1G
set JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200
set JAVA_OPTS=%JAVA_OPTS% -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC
set JAVA_OPTS=%JAVA_OPTS% -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40
set JAVA_OPTS=%JAVA_OPTS% -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20
set JAVA_OPTS=%JAVA_OPTS% -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15
set JAVA_OPTS=%JAVA_OPTS% -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5
set JAVA_OPTS=%JAVA_OPTS% -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1

:start
REM Find latest nimbus JAR by semver
set "NIMBUS_JAR="
set "BEST_MAJOR=0"
set "BEST_MINOR=0"
set "BEST_PATCH=0"
for %%F in (nimbus-core-*.jar) do call :check_jar "%%F"
if not defined NIMBUS_JAR (
    if exist nimbus.jar (
        set "NIMBUS_JAR=nimbus.jar"
    ) else (
        echo Error: No nimbus JAR found in %~dp0
        exit /b 1
    )
)
echo Starting %NIMBUS_JAR%...
java %JAVA_OPTS% -jar %NIMBUS_JAR% %*
if %ERRORLEVEL% equ 10 (
    echo Update detected, restarting with latest version...
    goto start
)
exit /b %ERRORLEVEL%

:check_jar
set "JAR_NAME=%~1"
REM Extract version digits from filename (e.g. nimbus-core-0.1.2.jar)
for /f "tokens=1-3 delims=.-" %%A in ("!JAR_NAME:*nimbus-=!") do (
    set "V_MAJOR=%%A" & set "V_MINOR=%%B" & set "V_PATCH=%%C"
)
REM Remove non-numeric prefix from V_MAJOR (e.g. "core" from "core-0")
for /f "delims=0123456789" %%X in ("%V_MAJOR%") do set "V_MAJOR=!V_MAJOR:%%X=!"
if not defined V_MAJOR goto :eof
REM Compare: is this version higher than current best?
if %V_MAJOR% gtr %BEST_MAJOR% goto :use_jar
if %V_MAJOR% lss %BEST_MAJOR% goto :eof
if %V_MINOR% gtr %BEST_MINOR% goto :use_jar
if %V_MINOR% lss %BEST_MINOR% goto :eof
if %V_PATCH% gtr %BEST_PATCH% goto :use_jar
goto :eof

:use_jar
set "NIMBUS_JAR=%JAR_NAME%"
set "BEST_MAJOR=%V_MAJOR%"
set "BEST_MINOR=%V_MINOR%"
set "BEST_PATCH=%V_PATCH%"
goto :eof
"@
    Set-Content -Path (Join-Path $InstallDir "nimbus.bat") -Value $batContent -Encoding ASCII

    # nimbus.ps1 — finds latest versioned JAR by semver, restart loop on exit code 10
    $ps1Content = @'
Set-Location $PSScriptRoot

function Find-LatestJar {
    $best = $null
    $bestVer = [Version]"0.0.0"
    foreach ($jar in Get-ChildItem -Filter "nimbus-core-*.jar") {
        if ($jar.Name -match '(\d+\.\d+\.\d+)') {
            $ver = [Version]$Matches[1]
            if ($ver -gt $bestVer) {
                $best = $jar.Name
                $bestVer = $ver
            }
        }
    }
    # Fallback: unversioned nimbus.jar (legacy installs)
    if (-not $best -and (Test-Path "nimbus.jar")) { $best = "nimbus.jar" }
    return $best
}

$javaOpts = @(
    "-Xms512M", "-Xmx1G",
    "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
    "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC",
    "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
    "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
    "-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=15",
    "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5",
    "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1"
)

do {
    $nimbusJar = Find-LatestJar
    if (-not $nimbusJar) {
        Write-Host "Error: No nimbus JAR found in $PSScriptRoot"
        exit 1
    }
    Write-Host "Starting $nimbusJar..."
    & java @javaOpts -jar $nimbusJar @args
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 10) {
        Write-Host "Update detected, restarting with latest version..."
    }
} while ($exitCode -eq 10)
exit $exitCode
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
    $batPath = Join-Path $InstallDir "nimbus.bat"

    # Use sc.exe to create service — nimbus.bat handles JAR version resolution
    & sc.exe create $serviceName binPath= "`"$batPath`"" start= auto DisplayName= "Nimbus Cloud"
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
