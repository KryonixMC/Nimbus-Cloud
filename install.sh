#!/usr/bin/env bash
set -euo pipefail

# ── Nimbus Cloud Installer ──────────────────────────────────────
# Usage: curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.sh | bash

# Ensure interactive prompts work when piped via curl | bash
if [[ -e /dev/tty ]]; then
    TTY=/dev/tty
else
    TTY=/dev/stdin
fi
# ────────────────────────────────────────────────────────────────

REPO_OWNER="NimbusPowered"
REPO_NAME="Nimbus"
INSTALL_DIR="/opt/nimbus"
JAVA_VERSION="21"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

info()    { echo -e "${CYAN}[nimbus]${RESET} $1"; }
success() { echo -e "${GREEN}[nimbus]${RESET} $1"; }
warn()    { echo -e "${YELLOW}[nimbus]${RESET} $1"; }
error()   { echo -e "${RED}[nimbus]${RESET} $1"; }

banner() {
    echo -e ""
    echo -e "${CYAN}   _  __ __ _   __ ___  _ __  ___${RESET}"
    echo -e "${CYAN}  / |/ // // \\,' // o.)/// /,' _/${RESET}"
    echo -e "${CYAN} / || // // \\,' // o \\/ U /_\\ \`. ${RESET}"
    echo -e "${CYAN}/_/|_//_//_/ /_//___,'\_,'/___,' ${RESET}"
    echo -e "${DIM}            C L O U D${RESET}"
    echo -e "${DIM}         Installer${RESET}"
    echo -e ""
}

# ── Detect OS & package manager ─────────────────────────────────

detect_os() {
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        if command -v apt-get &>/dev/null; then
            PKG_MANAGER="apt"
        elif command -v dnf &>/dev/null; then
            PKG_MANAGER="dnf"
        elif command -v yum &>/dev/null; then
            PKG_MANAGER="yum"
        elif command -v pacman &>/dev/null; then
            PKG_MANAGER="pacman"
        elif command -v zypper &>/dev/null; then
            PKG_MANAGER="zypper"
        else
            PKG_MANAGER="unknown"
        fi
        OS="linux"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        PKG_MANAGER="brew"
        OS="macos"
    else
        error "Unsupported operating system: $OSTYPE"
        exit 1
    fi
}

# ── Check/install Java 21 ───────────────────────────────────────

check_java() {
    if command -v java &>/dev/null; then
        local java_ver
        java_ver=$(java -version 2>&1 | head -1 | grep -oP '"(\d+)' | grep -oP '\d+')
        if [[ "$java_ver" -ge "$JAVA_VERSION" ]]; then
            success "Java $java_ver found"
            return 0
        fi
        warn "Java $java_ver found, but Java $JAVA_VERSION+ is required"
    else
        warn "Java not found"
    fi
    return 1
}

install_java() {
    info "Installing Java $JAVA_VERSION (Eclipse Temurin)..."

    case "$PKG_MANAGER" in
        apt)
            # Add Adoptium repository
            sudo apt-get update -qq
            sudo apt-get install -y -qq wget apt-transport-https gnupg
            wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg 2>/dev/null || true
            echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
            sudo apt-get update -qq
            sudo apt-get install -y -qq "temurin-${JAVA_VERSION}-jdk"
            ;;
        dnf|yum)
            sudo "$PKG_MANAGER" install -y "java-${JAVA_VERSION}-openjdk"
            ;;
        pacman)
            sudo pacman -S --noconfirm "jdk${JAVA_VERSION}-openjdk"
            ;;
        zypper)
            sudo zypper install -y "java-${JAVA_VERSION}-openjdk"
            ;;
        brew)
            brew install --cask temurin@${JAVA_VERSION}
            ;;
        *)
            error "Cannot auto-install Java. Please install Java $JAVA_VERSION manually:"
            error "  https://adoptium.net/temurin/releases/"
            exit 1
            ;;
    esac

    if check_java; then
        success "Java $JAVA_VERSION installed successfully"
    else
        error "Java installation failed. Please install Java $JAVA_VERSION manually."
        exit 1
    fi
}

# ── Download Nimbus release ──────────────────────────────────────

download_nimbus() {
    info "Fetching available releases from GitHub..."

    local releases_json
    releases_json=$(curl -fsSL "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=20" 2>/dev/null) || {
        error "Failed to fetch releases from GitHub"
        exit 1
    }

    # Extract all tag names (including pre-releases)
    local -a versions
    mapfile -t versions < <(echo "$releases_json" | grep -oP '"tag_name"\s*:\s*"\K[^"]+')

    if [[ ${#versions[@]} -eq 0 ]]; then
        error "No releases found on GitHub"
        exit 1
    fi

    # Extract pre-release flags (parallel array)
    local -a prereleases
    mapfile -t prereleases < <(echo "$releases_json" | grep -oP '"prerelease"\s*:\s*\K(true|false)' || true)

    # Display available versions
    echo ""
    info "Available versions:"
    local i=1
    for idx in "${!versions[@]}"; do
        local ver="${versions[$idx]}"
        local pre=""
        if [[ "${prereleases[$idx]:-}" == "true" ]]; then
            pre=" ${DIM}(pre-release)${RESET}"
        fi
        echo -e "    ${CYAN}${i})${RESET}  ${BOLD}${ver}${RESET}${pre}"
        i=$((i + 1))
    done
    echo ""

    # Prompt for version selection
    local selected_idx
    read -rp "$(echo -e "${CYAN}[nimbus]${RESET} Select version ${DIM}[1]${RESET}: ")" selected_idx <"$TTY"
    selected_idx="${selected_idx:-1}"

    # Validate selection
    if ! [[ "$selected_idx" =~ ^[0-9]+$ ]] || [[ "$selected_idx" -lt 1 ]] || [[ "$selected_idx" -gt ${#versions[@]} ]]; then
        error "Invalid selection: $selected_idx"
        exit 1
    fi

    local selected_version="${versions[$((selected_idx - 1))]}"
    info "Selected: ${BOLD}${selected_version}${RESET}"

    # Fetch the specific release to get assets
    local release_json
    release_json=$(curl -fsSL "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/tags/$selected_version" 2>/dev/null) || {
        error "Failed to fetch release $selected_version"
        exit 1
    }

    # Find the controller JAR asset (nimbus-core-*.jar)
    local jar_url
    jar_url=$(echo "$release_json" | grep -oP '"browser_download_url"\s*:\s*"\K[^"]*nimbus-core-[^"]*\.jar' | head -1)

    if [[ -z "$jar_url" ]]; then
        # Fallback: *controller*.jar (future naming)
        jar_url=$(echo "$release_json" | grep -oP '"browser_download_url"\s*:\s*"\K[^"]*controller[^"]*\.jar' | head -1)
    fi

    if [[ -z "$jar_url" ]]; then
        error "No JAR asset found in release $selected_version"
        exit 1
    fi

    # Keep the original versioned filename (e.g. nimbus-core-0.1.2.jar)
    local jar_name
    jar_name=$(basename "$jar_url")
    info "Downloading Nimbus ${selected_version}..."
    sudo mkdir -p "$INSTALL_DIR"
    sudo curl -fsSL -o "$INSTALL_DIR/$jar_name" "$jar_url"

    # Create working directories and set ownership to invoking user
    local real_user="${SUDO_USER:-$(whoami)}"
    sudo mkdir -p "$INSTALL_DIR"/{config/groups,config/modules,templates,services,logs}
    sudo chown -R "$real_user:$(id -gn "$real_user")" "$INSTALL_DIR"
    success "Downloaded to $INSTALL_DIR/$jar_name"
}

# ── Install screen ──────────────────────────────────────────────

install_screen() {
    if command -v screen &>/dev/null; then
        return
    fi

    info "Installing screen..."
    case "$PKG_MANAGER" in
        apt)    sudo apt-get install -y -qq screen ;;
        dnf|yum) sudo "$PKG_MANAGER" install -y screen ;;
        pacman) sudo pacman -S --noconfirm screen ;;
        zypper) sudo zypper install -y screen ;;
        brew)   brew install screen ;;
        *)      warn "Cannot auto-install screen. Please install it manually." ;;
    esac
}

# ── Create start script ─────────────────────────────────────────

create_start_script() {
    info "Creating start scripts..."

    # start.sh — starts Nimbus in screen and attaches, or reattaches if already running
    # Usage: start.sh           → start + attach (interactive)
    #        start.sh --detach  → start detached (for systemd)
    #        start.sh --run     → internal: restart loop (called by screen)
    sudo tee "$INSTALL_DIR/start.sh" >/dev/null <<'SCRIPT'
#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
cd "$SCRIPT_DIR"

SESSION="nimbus"
DETACH=false
RUN_MODE=false
for arg in "$@"; do
    case "$arg" in
        --detach) DETACH=true ;;
        --run) RUN_MODE=true ;;
    esac
done

# Find the latest nimbus JAR by semver comparison
find_latest_jar() {
    local best="" best_major=0 best_minor=0 best_patch=0
    for jar in nimbus-core-*.jar; do
        [[ -f "$jar" ]] || continue
        local ver
        ver=$(echo "$jar" | grep -oP '\d+\.\d+\.\d+')
        [[ -z "$ver" ]] && continue
        IFS='.' read -r major minor patch <<< "$ver"
        if (( major > best_major || (major == best_major && minor > best_minor) || (major == best_major && minor == best_minor && patch > best_patch) )); then
            best="$jar"
            best_major=$major; best_minor=$minor; best_patch=$patch
        fi
    done
    # Fallback: unversioned nimbus.jar (legacy installs)
    if [[ -z "$best" && -f "nimbus.jar" ]]; then
        best="nimbus.jar"
    fi
    echo "$best"
}

JAVA_OPTS="-Xms512M -Xmx1G"

# Aikar's flags for better GC
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200"
JAVA_OPTS="$JAVA_OPTS -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC"
JAVA_OPTS="$JAVA_OPTS -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40"
JAVA_OPTS="$JAVA_OPTS -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20"
JAVA_OPTS="$JAVA_OPTS -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15"
JAVA_OPTS="$JAVA_OPTS -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5"
JAVA_OPTS="$JAVA_OPTS -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"

# --run mode: called by screen session, handles restart loop
if [[ "$RUN_MODE" == true ]]; then
    while true; do
        NIMBUS_JAR=$(find_latest_jar)
        if [[ -z "$NIMBUS_JAR" ]]; then
            echo "Error: No nimbus JAR found in $SCRIPT_DIR"
            exit 1
        fi
        echo "Starting $NIMBUS_JAR..."
        java $JAVA_OPTS -jar "$NIMBUS_JAR"
        EXIT_CODE=$?
        if [[ $EXIT_CODE -ne 10 ]]; then
            exit $EXIT_CODE
        fi
        echo "Update detected, restarting with latest version..."
    done
fi

# Already running? Attach if interactive, exit if detached.
if screen -list | grep -q "\.$SESSION\b"; then
    if [[ "$DETACH" == true ]]; then
        echo "Nimbus is already running."
        exit 0
    fi
    exec screen -r "$SESSION"
fi

if [[ "$DETACH" == true ]]; then
    # Detached mode (systemd): start in background and return
    screen -dmS "$SESSION" "$0" --run
else
    # Interactive mode: start and attach immediately (Ctrl+A, D to detach)
    exec screen -S "$SESSION" "$0" --run
fi
SCRIPT
    sudo chmod +x "$INSTALL_DIR/start.sh"

    # nimbus command — just calls start.sh (which handles attach-or-start)
    # Remove old symlink first (previous installs created a symlink here)
    sudo rm -f /usr/local/bin/nimbus
    sudo tee /usr/local/bin/nimbus >/dev/null <<'CMD'
#!/usr/bin/env bash
exec /opt/nimbus/start.sh "$@"
CMD
    sudo chmod +x /usr/local/bin/nimbus
    success "Created 'nimbus' command"
}

# ── Create systemd service (optional) ───────────────────────────

create_systemd_service() {
    if [[ "$OS" != "linux" ]] || ! command -v systemctl &>/dev/null; then
        return
    fi

    echo ""
    read -rp "$(echo -e "${CYAN}[nimbus]${RESET} Create systemd service for auto-start? [y/N]: ")" create_service <"$TTY"
    if [[ "${create_service,,}" != "y" && "${create_service,,}" != "yes" ]]; then
        return
    fi

    # Use the invoking user for the service (not a separate system user)
    local service_user="${SUDO_USER:-$(whoami)}"
    sudo chown -R "$service_user:$(id -gn "$service_user")" "$INSTALL_DIR"

    sudo tee /etc/systemd/system/nimbus.service >/dev/null <<EOF
[Unit]
Description=Nimbus Cloud
After=network.target

[Service]
Type=forking
User=$service_user
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/start.sh --detach
ExecStop=/usr/bin/screen -S nimbus -X stuff "shutdown\nshutdown confirm\n"
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

    sudo systemctl daemon-reload
    sudo systemctl enable nimbus.service
    success "Systemd service created and enabled"
    info "  Start:   sudo systemctl start nimbus"
    info "  Attach:  nimbus  ${DIM}(or: screen -r nimbus)${RESET}"
    info "  Detach:  Ctrl+A, then D"
    info "  Status:  sudo systemctl status nimbus"
}

# ── Main ────────────────────────────────────────────────────────

main() {
    banner

    # Check for root/sudo
    if [[ $EUID -ne 0 ]] && ! command -v sudo &>/dev/null; then
        error "This installer requires root or sudo access"
        exit 1
    fi

    # Check for curl
    if ! command -v curl &>/dev/null; then
        error "curl is required but not installed"
        exit 1
    fi

    detect_os
    info "Detected: ${BOLD}$OS${RESET} (${PKG_MANAGER})"

    # Dependencies
    if ! check_java; then
        install_java
    fi
    install_screen

    # Download
    download_nimbus

    # Start script
    create_start_script

    # Systemd (Linux only)
    create_systemd_service

    echo ""
    echo -e "${GREEN}${BOLD}Nimbus installed successfully!${RESET}"
    echo ""
    echo -e "  ${CYAN}Installation:${RESET}  $INSTALL_DIR"
    echo -e "  ${CYAN}Start:${RESET}         nimbus"
    echo -e "  ${CYAN}Attach:${RESET}        nimbus  ${DIM}(auto-attaches if already running)${RESET}"
    echo -e "  ${CYAN}Detach:${RESET}        Ctrl+A, then D"
    echo ""

    # Offer to start now
    read -rp "$(echo -e "${CYAN}[nimbus]${RESET} Start Nimbus now? [Y/n]: ")" start_now <"$TTY"
    if [[ "${start_now,,}" != "n" && "${start_now,,}" != "no" ]]; then
        echo ""
        echo -e "  ${DIM}Starting Nimbus... (Ctrl+A, D to detach from console)${RESET}"
        echo ""
        "$INSTALL_DIR/start.sh"
    else
        echo -e "  ${DIM}Run 'nimbus' when you're ready to start.${RESET}"
    fi
    echo ""
}

main "$@"
