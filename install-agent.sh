#!/usr/bin/env bash
set -euo pipefail

# ── Nimbus Agent Installer ──────────────────────────────────────
# Usage: curl -fsSL https://raw.githubusercontent.com/jonax1337/Nimbus/main/install-agent.sh | bash
# ────────────────────────────────────────────────────────────────

REPO_OWNER="jonax1337"
REPO_NAME="Nimbus"
INSTALL_DIR="/opt/nimbus-agent"
JAVA_VERSION="21"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

info()    { echo -e "${CYAN}[nimbus-agent]${RESET} $1"; }
success() { echo -e "${GREEN}[nimbus-agent]${RESET} $1"; }
warn()    { echo -e "${YELLOW}[nimbus-agent]${RESET} $1"; }
error()   { echo -e "${RED}[nimbus-agent]${RESET} $1"; }

banner() {
    echo -e ""
    echo -e "${CYAN}   _  __ __ _   __ ___  _ __  ___${RESET}"
    echo -e "${CYAN}  / |/ // // \\,' // o.)/// /,' _/${RESET}"
    echo -e "${CYAN} / || // // \\,' // o \\/ U /_\\ \`. ${RESET}"
    echo -e "${CYAN}/_/|_//_//_/ /_//___,'\_,'/___,' ${RESET}"
    echo -e "${DIM}            A G E N T${RESET}"
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

# ── Download Nimbus Agent ────────────────────────────────────────

download_agent() {
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

    # Display available versions
    echo ""
    info "Available versions:"
    local i=1
    for ver in "${versions[@]}"; do
        local pre=""
        local is_pre
        is_pre=$(echo "$releases_json" | grep -A2 "\"tag_name\": \"$ver\"" | grep -oP '"prerelease"\s*:\s*\K(true|false)' | head -1)
        if [[ "$is_pre" == "true" ]]; then
            pre=" ${DIM}(pre-release)${RESET}"
        fi
        echo -e "    ${CYAN}${i})${RESET}  ${BOLD}${ver}${RESET}${pre}"
        ((i++))
    done
    echo ""

    # Prompt for version selection
    local selected_idx
    read -rp "$(echo -e "${CYAN}[nimbus-agent]${RESET} Select version ${DIM}[1]${RESET}: ")" selected_idx
    selected_idx="${selected_idx:-1}"

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

    # Find the agent JAR asset
    local jar_url
    jar_url=$(echo "$release_json" | grep -oP '"browser_download_url"\s*:\s*"\K[^"]*agent[^"]*\.jar' | head -1)

    if [[ -z "$jar_url" ]]; then
        error "No agent JAR asset found in release $selected_version"
        exit 1
    fi

    sudo mkdir -p "$INSTALL_DIR"
    info "Downloading Nimbus Agent ${selected_version}..."
    sudo curl -fsSL -o "$INSTALL_DIR/nimbus-agent.jar" "$jar_url"
    success "Downloaded to $INSTALL_DIR/nimbus-agent.jar"
}

# ── Create default config ───────────────────────────────────────

create_default_config() {
    local config_file="$INSTALL_DIR/agent.toml"
    if [[ -f "$config_file" ]]; then
        info "Config already exists, skipping"
        return
    fi

    info "Creating default agent config..."

    echo ""
    read -rp "$(echo -e "${CYAN}[nimbus-agent]${RESET} Controller host [127.0.0.1]: ")" controller_host
    controller_host="${controller_host:-127.0.0.1}"

    read -rp "$(echo -e "${CYAN}[nimbus-agent]${RESET} Controller port [8443]: ")" controller_port
    controller_port="${controller_port:-8443}"

    read -rp "$(echo -e "${CYAN}[nimbus-agent]${RESET} Node ID [$(hostname)]: ")" node_id
    node_id="${node_id:-$(hostname)}"

    read -rp "$(echo -e "${CYAN}[nimbus-agent]${RESET} Auth token: ")" auth_token

    sudo tee "$config_file" >/dev/null <<EOF
[agent]
node_id = "$node_id"
controller_host = "$controller_host"
controller_port = $controller_port
auth_token = "$auth_token"
EOF

    success "Config saved to $config_file"
}

# ── Create start script ─────────────────────────────────────────

create_start_script() {
    info "Creating start script..."

    sudo tee "$INSTALL_DIR/start.sh" >/dev/null <<'SCRIPT'
#!/usr/bin/env bash
cd "$(dirname "$0")"

JAVA_OPTS="-Xms256M -Xmx512M"
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200"

exec java $JAVA_OPTS -jar nimbus-agent.jar "$@"
SCRIPT
    sudo chmod +x "$INSTALL_DIR/start.sh"

    sudo ln -sf "$INSTALL_DIR/start.sh" /usr/local/bin/nimbus-agent
    success "Created 'nimbus-agent' command"
}

# ── Create systemd service ──────────────────────────────────────

create_systemd_service() {
    if [[ "$OS" != "linux" ]] || ! command -v systemctl &>/dev/null; then
        return
    fi

    echo ""
    read -rp "$(echo -e "${CYAN}[nimbus-agent]${RESET} Create systemd service for auto-start? [Y/n]: ")" create_service
    if [[ "${create_service,,}" == "n" || "${create_service,,}" == "no" ]]; then
        return
    fi

    # Create nimbus user if it doesn't exist
    if ! id -u nimbus &>/dev/null; then
        sudo useradd -r -m -d "$INSTALL_DIR" -s /bin/bash nimbus
        info "Created 'nimbus' system user"
    fi
    sudo chown -R nimbus:nimbus "$INSTALL_DIR"

    sudo tee /etc/systemd/system/nimbus-agent.service >/dev/null <<EOF
[Unit]
Description=Nimbus Agent
After=network.target
Wants=network-online.target

[Service]
Type=simple
User=nimbus
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/start.sh
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

    sudo systemctl daemon-reload
    sudo systemctl enable nimbus-agent.service
    success "Systemd service created and enabled"

    echo ""
    read -rp "$(echo -e "${CYAN}[nimbus-agent]${RESET} Start agent now? [Y/n]: ")" start_now
    if [[ "${start_now,,}" != "n" && "${start_now,,}" != "no" ]]; then
        sudo systemctl start nimbus-agent.service
        success "Agent started"
    fi

    info "  Status:  sudo systemctl status nimbus-agent"
    info "  Logs:    sudo journalctl -u nimbus-agent -f"
    info "  Stop:    sudo systemctl stop nimbus-agent"
}

# ── Main ────────────────────────────────────────────────────────

main() {
    banner

    if [[ $EUID -ne 0 ]] && ! command -v sudo &>/dev/null; then
        error "This installer requires root or sudo access"
        exit 1
    fi

    if ! command -v curl &>/dev/null; then
        error "curl is required but not installed"
        exit 1
    fi

    detect_os
    info "Detected: ${BOLD}$OS${RESET} (${PKG_MANAGER})"

    if ! check_java; then
        install_java
    fi

    download_agent
    create_default_config
    create_start_script
    create_systemd_service

    echo ""
    echo -e "${GREEN}${BOLD}Nimbus Agent installed successfully!${RESET}"
    echo ""
    echo -e "  ${CYAN}Installation:${RESET}  $INSTALL_DIR"
    echo -e "  ${CYAN}Config:${RESET}        $INSTALL_DIR/agent.toml"
    echo -e "  ${CYAN}Start:${RESET}         nimbus-agent"
    echo ""
}

main "$@"
