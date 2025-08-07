# Build script for CDR Client Watchdog Service
# This script builds the watchdog service and prepares it for Conveyor packaging

param(
    [string]$Configuration = "Release",
    [string]$Runtime = "win-x64",
    [switch]$SelfContained = $false
)

Write-Host "Building CDR Client Watchdog Service..." -ForegroundColor Green

# Check if .NET SDK is available
try {
    $dotnetVersion = dotnet --version
    Write-Host "Using .NET SDK version: $dotnetVersion" -ForegroundColor Yellow
} catch {
    Write-Error ".NET SDK is not installed or not in PATH. Please install .NET 8.0 SDK or later."
    exit 1
}

# Set the working directory to the watchdog project
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# Clean previous builds
if (Test-Path "publish") {
    Remove-Item -Recurse -Force "publish"
    Write-Host "Cleaned previous build artifacts" -ForegroundColor Yellow
}

# Build the project
Write-Host "Building for $Runtime (Self-contained: $SelfContained)..." -ForegroundColor Yellow

$publishArgs = @(
    "publish"
    "-c", $Configuration
    "-r", $Runtime
    "--self-contained", $SelfContained.ToString().ToLower()
    "-o", "publish"
    "--verbosity", "minimal"
)

$buildResult = & dotnet @publishArgs

if ($LASTEXITCODE -eq 0) {
    Write-Host "Build completed successfully!" -ForegroundColor Green
    Write-Host "Output directory: $(Join-Path $scriptDir 'publish')" -ForegroundColor Green
    
    # List the published files
    Write-Host "`nPublished files:" -ForegroundColor Yellow
    Get-ChildItem "publish" | ForEach-Object { Write-Host "  $($_.Name)" }
    
    Write-Host "`nTo integrate with Conveyor:" -ForegroundColor Cyan
    Write-Host "1. The conveyor.conf has been updated to include the watchdog service" -ForegroundColor White
    Write-Host "2. Run your Conveyor build process normally" -ForegroundColor White
    Write-Host "3. The watchdog will be included in the Windows installation" -ForegroundColor White
    Write-Host "4. Use 'install-watchdog.bat' to install the service after deployment" -ForegroundColor White
    
} else {
    Write-Error "Build failed!"
    exit 1
}

Write-Host "`nBuild script completed." -ForegroundColor Green
