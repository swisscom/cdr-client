#!/bin/bash

# Build script for CDR Client Watchdog Service (Linux/WSL)
# This script builds the watchdog service and prepares it for Conveyor packaging
# Usage: ./build.sh [SELF_CONTAINED] [CONFIGURATION] [RUNTIME]

CONFIGURATION="${2:-Release}"
RUNTIME="${3:-win-x64}"
SELF_CONTAINED="${1:-true}"

echo "Building CDR Client Watchdog Service..."

# Check if .NET SDK is available - prefer local (if called from Gradle), fall back to system
DOTNET_PATH="../build/dotnet-sdk/dotnet"
if [ -f "$DOTNET_PATH" ]; then
    echo "Using local .NET SDK (downloaded by Gradle)"
elif command -v dotnet &> /dev/null; then
    DOTNET_PATH="dotnet"
    echo "Using system .NET SDK"
else
    echo "Error: .NET SDK not found."
    echo "Please either:"
    echo "  1. Install .NET 8.0 SDK system-wide, or"
    echo "  2. Run './gradlew buildWatchdog' to auto-download a local SDK"
    exit 1
fi

DOTNET_VERSION=$($DOTNET_PATH --version 2>/dev/null)
if [ $? -ne 0 ] || [ -z "$DOTNET_VERSION" ]; then
    echo "Error: .NET SDK command failed or returned empty version."
    echo "Please either:"
    echo "  1. Install .NET 8.0 SDK system-wide, or"
    echo "  2. Run './gradlew buildWatchdog' to auto-download a local SDK"
    exit 1
fi
echo "Using .NET SDK version: $DOTNET_VERSION"

# Navigate to the watchdog directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Clean previous builds
if [ -d "publish" ]; then
    rm -rf publish
    echo "Cleaned previous build artifacts"
fi

# Build the project
echo "Building for $RUNTIME (Self-contained: $SELF_CONTAINED)..."

$DOTNET_PATH publish \
    -c "$CONFIGURATION" \
    -r "$RUNTIME" \
    --self-contained "$SELF_CONTAINED" \
    -o publish \
    --verbosity minimal

if [ $? -eq 0 ]; then
    echo "Build completed successfully!"
    echo "Output directory: $SCRIPT_DIR/publish"
    
    # List the published files
    echo ""
    echo "Published files:"
    ls -la publish/
    
    echo ""
    echo "To integrate with Conveyor:"
    echo "1. The conveyor.conf has been updated to include the watchdog service"
    echo "2. Run your Conveyor build process normally"
    echo "3. The watchdog will be included in the Windows installation"
    echo "4. Use 'install-watchdog.bat' to install the service after deployment"
else
    echo "Build failed!"
    exit 1
fi

echo ""
echo "Build script completed."
