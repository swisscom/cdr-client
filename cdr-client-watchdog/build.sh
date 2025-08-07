#!/bin/bash

# Build script for CDR Client Watchdog Service (Linux/WSL)
# This script builds the watchdog service and prepares it for Conveyor packaging

CONFIGURATION="Release"
RUNTIME="win-x64"
SELF_CONTAINED="false"

echo "Building CDR Client Watchdog Service..."

# Check if .NET SDK is available
if ! command -v dotnet &> /dev/null; then
    echo "Error: .NET SDK is not installed or not in PATH."
    echo "Please install .NET 8.0 SDK or later."
    exit 1
fi

DOTNET_VERSION=$(dotnet --version)
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

dotnet publish \
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
