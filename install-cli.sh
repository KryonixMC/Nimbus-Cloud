#!/usr/bin/env bash
set -euo pipefail

# ── Nimbus CLI Installer ───────────────────────────────────────
# Usage: curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.sh | bash

REPO_OWNER="NimbusPowered"
REPO_NAME="Nimbus"
INSTALL_DIR="/usr/local/bin"
JAVA_VERSION="21"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

info()    { echo -e "${CYAN}[nimbus-cli]${RESET} $1"; }
success() { echo -e "${GREEN}[nimbus-cli]${RESET} $1"; }
warn()    { echo -e "${YELLOW}[nimbus-cli]${RESET} $1"; }
error()   { echo -e "${RED}[nimbus-cli]${RESET} $1"; }

banner() {
    echo -e ""
    echo -e "${CYAN}   Nimbus Remote CLI${RESET}"
    echo -e "${DIM}   Installer${RESET}"
    echo -e ""
}

# ── Detect OS ──────────────────────────────────────────────────

detect_os() {
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        OS="linux"
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        OS="macos"
    else
        error "Unsupported OS: $OSTYPE"
        exit 1
    fi
}

# ── Java check ─────────────────────────────────────────────────

check_java() {
    if command -v java &>/dev/null; then
        JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
        if [[ "$JAVA_VER" -ge "$JAVA_VERSION" ]]; then
            info "Java $JAVA_VER found"
            return
        fi
    fi

    warn "Java $JAVA_VERSION+ required but not found."
    info "Install Java 21 first, for example:"
    echo -e "  ${DIM}# Debian/Ubuntu${RESET}"
    echo -e "  sudo apt install openjdk-21-jre-headless"
    echo -e "  ${DIM}# macOS (Homebrew)${RESET}"
    echo -e "  brew install openjdk@21"
    exit 1
}

# ── Download latest release ────────────────────────────────────

download_cli() {
    info "Fetching latest release..."

    RELEASE_JSON=$(curl -fsSL "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest")
    VERSION=$(echo "$RELEASE_JSON" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4)

    if [[ -z "$VERSION" ]]; then
        error "Could not determine latest version."
        exit 1
    fi

    info "Latest version: $VERSION"

    # Find CLI JAR asset
    DOWNLOAD_URL=$(echo "$RELEASE_JSON" | grep -o '"browser_download_url": *"[^"]*nimbus-cli[^"]*\.jar"' | head -1 | cut -d'"' -f4)

    if [[ -z "$DOWNLOAD_URL" ]]; then
        error "No CLI JAR found in release $VERSION."
        error "The CLI may not be available in this release yet."
        exit 1
    fi

    info "Downloading nimbus-cli..."
    TEMP_JAR=$(mktemp)
    curl -fsSL -o "$TEMP_JAR" "$DOWNLOAD_URL"

    success "Downloaded $(du -h "$TEMP_JAR" | cut -f1)"

    # Install JAR
    JAR_DIR="${HOME}/.nimbus"
    mkdir -p "$JAR_DIR"
    mv "$TEMP_JAR" "${JAR_DIR}/nimbus-cli.jar"

    # Create wrapper script
    WRAPPER="${INSTALL_DIR}/nimbus-cli"

    if [[ -w "$INSTALL_DIR" ]]; then
        cat > "$WRAPPER" << 'SCRIPT'
#!/usr/bin/env bash
exec java -jar "${HOME}/.nimbus/nimbus-cli.jar" "$@"
SCRIPT
        chmod +x "$WRAPPER"
    else
        info "Installing to ${INSTALL_DIR} (requires sudo)..."
        sudo bash -c "cat > '$WRAPPER'" << 'SCRIPT'
#!/usr/bin/env bash
exec java -jar "${HOME}/.nimbus/nimbus-cli.jar" "$@"
SCRIPT
        sudo chmod +x "$WRAPPER"
    fi

    success "Installed to ${WRAPPER}"
}

# ── Main ───────────────────────────────────────────────────────

banner
detect_os
check_java
download_cli

echo ""
success "Nimbus CLI installed successfully!"
echo ""
info "Quick start:"
echo -e "  ${BOLD}nimbus-cli --host <controller-ip> --port 8080 --token <api-token>${RESET}"
echo ""
info "Save connection for reuse:"
echo -e "  ${BOLD}nimbus-cli --host <ip> --token <token> --save-profile prod${RESET}"
echo -e "  ${BOLD}nimbus-cli --profile prod${RESET}"
echo ""
