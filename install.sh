#!/usr/bin/env bash
set -euo pipefail

# ── Nimbus Cloud Installer ──────────────────────────────────────
# Usage: curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.sh | bash
#
# Downloads the latest Nimbus release and starts it once.
# The built-in setup wizard handles configuration and start script creation.

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

    # Keep the original versioned filename (e.g. nimbus-core-0.4.2.jar)
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

    # Export for main() to use
    NIMBUS_JAR="$jar_name"
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

    # Download
    download_nimbus

    echo ""
    echo -e "${GREEN}${BOLD}Nimbus downloaded successfully!${RESET}"
    echo ""
    echo -e "  ${CYAN}Installation:${RESET}  $INSTALL_DIR"
    echo -e "  ${DIM}The setup wizard will guide you through configuration on first start.${RESET}"
    echo ""

    # Start Nimbus (setup wizard runs on first start)
    read -rp "$(echo -e "${CYAN}[nimbus]${RESET} Start Nimbus now? [Y/n]: ")" start_now <"$TTY"
    if [[ "${start_now,,}" != "n" && "${start_now,,}" != "no" ]]; then
        echo ""
        echo -e "  ${DIM}Starting Nimbus...${RESET}"
        echo ""
        cd "$INSTALL_DIR"
        java -Xms256M -Xmx256M --enable-native-access=ALL-UNNAMED -jar "$NIMBUS_JAR"
    else
        echo -e "  ${DIM}To start manually:${RESET}"
        echo -e "    cd $INSTALL_DIR && java -jar $NIMBUS_JAR"
    fi
    echo ""
}

main "$@"
