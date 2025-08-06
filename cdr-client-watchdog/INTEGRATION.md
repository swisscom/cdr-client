# Integration Guide: CDR Client Watchdog Service

This document explains how to integrate the new CDR Client Watchdog Service with your existing Conveyor-based build and deployment process.

## What This Provides

The watchdog service provides a robust and reliable C# Windows service that monitors your `cdr-client-service.exe` and automatically restarts it on failures.

## Changes Made

### 1. New Watchdog Service
- **Location**: `cdr-client-watchdog/` directory
- **Language**: C# (.NET 8.0)
- **Type**: Windows Service using .NET hosting framework

### 2. Updated Conveyor Configuration
The `conveyor.conf` file has been updated to:
- Include the watchdog service files using `inputs` directives (referencing actual files)
- Update the MSIX service registration
- No code duplication - all scripts are maintained as separate files and referenced

### 3. Key Features of the New Watchdog
- **Smart Restart Logic**: Only restarts on non-zero exit codes
- **Failure Protection**: Prevents endless restart loops (max 5 consecutive failures)
- **Configurable**: All settings in `appsettings.json`
- **Better Logging**: Windows Event Log integration
- **Graceful Shutdown**: Properly stops monitored process

## How to Use

### Building the Watchdog

From your project root:

```bash
# On Linux/WSL
cd cdr-client-watchdog
./build.sh

# On Windows
cd cdr-client-watchdog
.\build.ps1
# or
.\build.bat
```

This creates a `publish/` directory with all the necessary files.

### Integration with Your Build Process

1. **Build the watchdog first** (before running Conveyor):
   ```bash
   cd cdr-client-watchdog
   ./build.sh
   cd ..
   ```

2. **Run your normal Conveyor build**:
   ```bash
   ./gradlew -q printConveyorConfig
   conveyor make windows-installers
   ```

The watchdog files will be automatically included in your Windows installer under `bin/watchdog/`.

### Deployment and Installation

After installing your application via the Conveyor-generated installer:

1. **Navigate to installation directory**:
   ```cmd
   cd "C:\Program Files\CDR Client"
   ```

2. **Install the watchdog service** (as Administrator):
   ```cmd
   bin\install-watchdog.bat
   ```

3. **Verify the service is running**:
   ```cmd
   sc query "CDRClientWatchdog"
   ```

## Uninstallation

### Automatic Cleanup (Recommended)
The service should be automatically cleaned up when uninstalling the main application. However, if manual cleanup is needed:

### Manual Service Removal
If the service persists after uninstalling the application:

1. **Using the provided script** (as Administrator):
   ```cmd
   cd "C:\Program Files\CDR Client"
   bin\uninstall-watchdog.bat
   ```

2. **Using PowerShell** (as Administrator):
   ```powershell
   cd "C:\Program Files\CDR Client"
   .\bin\cleanup-service.ps1
   ```

3. **Manual cleanup** (as Administrator):
   ```cmd
   sc stop "CDRClientWatchdog"
   sc delete "CDRClientWatchdog"
   taskkill /F /IM "CdrClientWatchdog.exe"
   ```

### Verification
To verify complete removal:
```cmd
# Check if service is gone
sc query "CDRClientWatchdog"

# Check if processes are stopped
tasklist | findstr "CdrClientWatchdog"
```

If the service still appears in Windows Services after cleanup, a system reboot may be required.

### Configuration

The watchdog can be configured by editing `bin/watchdog/appsettings.json`:

```json
{
  "ServiceExecutablePath": "%BASE%\\cdr-client-service.exe",
  "RestartDelaySeconds": 5,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5,
  "EnableLogging": true
}
```

**Note**: `%BASE%` is automatically resolved to the installation directory.

## Service Configuration and Setup

### Key Features
- **JSON Configuration**: Settings are in simple JSON format
- **Smart Error Handling**: Distinguishes between clean exits (code 0) and crashes
- **Event Logging**: Uses Windows Event Log for monitoring and debugging
- **Process Monitoring**: Intelligent health checking and restart logic

### Removing Previous Service Installations (if needed)
If you have an existing service installation that needs to be replaced:

1. Stop the old service:
   ```cmd
   sc stop "CDRClientService"
   ```

2. Remove the old service:
   ```cmd
   sc delete "CDRClientService"
   ```

3. Install the new watchdog service as described above.

## Monitoring and Troubleshooting

### Service Status
```cmd
# Check service status
sc query "CDRClientWatchdog"

# Start/stop service
sc start "CDRClientWatchdog"
sc stop "CDRClientWatchdog"
```

### Logs
- **Windows Event Log**: Check Application log for "CDRClientWatchdog" events
- **Service Manager**: Use `services.msc` to view service properties

### Common Issues

1. **Service won't start**:
   - Check that `cdr-client-service.exe` exists at the configured path
   - Verify the service has appropriate permissions
   - Check Windows Event Log for error details

2. **Frequent restarts**:
   - Check why `cdr-client-service.exe` is exiting with non-zero codes
   - Review the CDR service logs
   - Consider increasing `RestartDelaySeconds`

3. **Service stops after failures**:
   - This is intentional after `MaxConsecutiveFailures` (default: 5)
   - Fix the underlying issue with CDR service
   - Restart the watchdog: `sc start "CDRClientWatchdog"`

## Development Notes

### Building for Different Configurations

```bash
# Debug build
dotnet publish -c Debug -r win-x64 --self-contained false

# Self-contained (includes .NET runtime)
dotnet publish -c Release -r win-x64 --self-contained true

# Different Windows architectures
dotnet publish -c Release -r win-arm64 --self-contained false
```

### Testing Locally

```bash
# Run as console application (for testing)
cd cdr-client-watchdog
dotnet run

# Run as Windows service
dotnet run --configuration Release
```

## Files Overview

```
cdr-client-watchdog/
├── CdrClientWatchdog.csproj    # Project file
├── Program.cs                   # Service entry point
├── WatchdogService.cs          # Main service logic
├── appsettings.json            # Configuration template
├── README.md                   # Detailed documentation
├── build.sh                    # Linux build script
├── build.ps1                   # PowerShell build script
├── build.bat                   # Windows batch build script
├── install-service.bat         # Service installation script
├── uninstall-service.bat       # Service removal script
└── publish/                    # Build output (created after build)
    ├── CdrClientWatchdog.exe
    ├── CdrClientWatchdog.dll
    ├── *.dll                   # .NET dependencies
    └── appsettings.json
```

The Conveyor configuration now automatically includes all files from `publish/` in the Windows installer.
