# curaLINE Client updateservice

This service automatically manages updates of the curaLINE Client on Windows Server (especially 2019 and older versions), where MSIX auto-update functionality is not available.

> ⚠️ **Note**: If you're using **Windows Server 2022 or newer**, please use the default MSIX installer instead. This advanced installation method is primarily for Windows Server version 2019 which does not fully support MSIX packages.

## Overview

The Update Service:
- Checks for new versions from the update site every 2 hours (configurable, with immediate check at startup)
- Downloads available artifacts from the update site with SHA256 checksum verification
- Stops the Watchdog service (which stops the curaLINE Client application)
- Creates a backup of current files before applying updates
- Applies updates to present components (Service JAR, Watchdog)
- Restarts the Watchdog service
- Tracks component versions independently
- Logs all operations to Windows Event Log
- Supports version pinning to prevent unwanted updates
- Rolls back updates on failure

### Intelligent Component Updates

**Components are updated independently based on what's available in each release:**

- **Service JAR**: Always included in every release
- **Watchdog**: Only included if watchdog code changes

This means:
- Release `5.3.1` might only update the Service JAR (if only service code changed)
- Release `5.3.2` might update Service + Watchdog (if both changed)
- Components retain their previous versions until a release includes an update for them

## Architecture

```
curaLINEClientUpdateService (this service, displayed as "curaLINE Client updateservice")
    ↓ manages/stops/starts
CDRClientWatchdog
    ↓ monitors/restarts
CDRClientService (main application JAR)
```

## Installation

### Prerequisites
- curaLINE Client must be installed first (including the Watchdog service)
- .NET 10 Runtime (Windows) - **Required** (this service is framework-dependent)
- Java Runtime Environment (JRE) included in installation
- Administrator privileges required

### Steps

**Note**: UpdateService is Windows-only (for Windows Server advanced installation).

1. **Install the Service**:
   ```cmd
   install-service.bat
   ```

2. **Verify Installation**:
   ```cmd
   sc query curaLINEClientUpdateService
   ```

## Configuration

Edit `appsettings.json` to customize behavior:

```json
{
  "UpdateCheckIntervalHours": 2,
  "WatchdogServiceName": "CDRClientWatchdog",
  "InstallationPath": "",
  "PinnedVersion": "",
  "MaxBackupsToKeep": 3,
  "CurrentVersions": {
    "Service": "1.0.0",
    "Watchdog": "1.0.0",
    "UpdateService": "1.0.0"
  },
  "Artifacts": {
    "Service": {
      "FileName": "cdr-client-service.jar",
      "TargetPath": "lib/cdr-client-service.jar"
    },
    "Watchdog": {
      "FileName": "CdrClientWatchdog.zip",
      "TargetPath": "bin/watchdog/"
    }
  },
  "JavaExecutablePath": "bin/jre/bin/java.exe"
}
```

### Configuration Parameters

- **UpdateCheckIntervalHours**: How often to check for updates (default: 2 hours)
- **WatchdogServiceName**: Name of the watchdog Windows service
- **InstallationPath**: Root installation path (auto-detected if empty)
- **PinnedVersion**: Pin to a specific curaLINE client version (empty = auto-update, "5.3.0" = stay on that version, watchdog won't be auto-updated either)
- **MaxBackupsToKeep**: Number of backups to retain (default: 3)
- **CurrentVersions**: Current installed versions (updated automatically after each update)
- **Artifacts**: Configuration for each updateable component
  - **FileName**: Informational artifact name from packaging defaults
  - **TargetPath**: Where to install the artifact (relative to installation path)
- **JavaExecutablePath**: Path to Java executable for running JARs

### Security Features

- **Hardcoded Update URL**: The update site URL (`https://cdr.health.swisscom.ch/share/downloads/manualInstallation`) is hardcoded in the service binary to prevent tampering via configuration files
- **System Proxy Support**: Automatically uses Windows system proxy settings with default network credentials for corporate environments

## Update Process

When a new version is detected in the update site:

1. **Discover**: Fetch `latest.json` to get current version pointer
2. **Fetch Manifest**: Download `manifest.json` for that version
3. **Download & Verify**: Download all artifacts and verify SHA256 checksums
4. **Backup**: Create timestamped backup of current files
5. **Stop**: Stop watchdog service (which stops curaLINE client service)
6. **Apply**: Copy JARs to target location / Extract ZIPs for watchdog
7. **Config**: Update current versions in appsettings.json
8. **Restart**: Restart watchdog service (which restarts curaLINE client service with new JAR)
9. **Verify**: Confirm watchdog is running
10. **Cleanup**: Delete old backups (keep last 3), remove temporary files

### Failure Handling

If any step fails during update:
- **Automatic Rollback**: Restore from backup
- **Service Recovery**: Attempt to restart watchdog
- **Logging**: Critical error logged with backup location for manual recovery

## Monitoring

### Event Log
All operations are logged to Windows Event Log under Application log with source "curaLINEClientUpdateService".

View logs:
```powershell
Get-EventLog -LogName Application -Source curaLINEClientUpdateService -Newest 50
```

### Service Status
Check service status:
```cmd
sc query curaLINEClientUpdateService
```

## Troubleshooting

### Service does not start
1. Check Event Log for error messages
2. Verify CDRClientWatchdog service exists and is installed
3. Verify `appsettings.json` configuration is valid
4. Check update site URL accessibility: `https://cdr.health.swisscom.ch/share/downloads/manualInstallation/latest.json`

### Updates not being applied
1. Check Event Log for download/extraction errors
2. Verify network connectivity to the update site
3. Check that manifest contains expected artifacts
4. Verify watchdog service can be stopped/started
5. Check for checksum verification failures
6. Verify Java executable path is correct

### Checksum verification failures
1. Check Event Log for specific checksum mismatch details
2. Re-download artifacts that may be corrupted
3. Verify update site artifacts integrity
4. Check network proxy settings

### Manual update rollback
If automatic rollback fails:
1. Check `backup-YYYYMMDD-HHMMSS` folders in installation directory
2. Stop UpdateService and Watchdog
3. Manually restore files from backup folder
4. Restart services

### Prevent automatic updates
To temporarily disable updates:
1. Edit `appsettings.json`
2. Set `"PinnedVersion": "5.3.0"` (your current version)
3. Restart UpdateService
4. Updates will be skipped until PinnedVersion is cleared

### Manual update check
To force an immediate update check, restart the service:
```cmd
sc stop curaLINEClientUpdateService
sc start curaLINEClientUpdateService
```

## Update Site Release Structure

For automatic updates to work, artifacts are published to the update site with this structure:

```
downloads/manualInstallation/
├── latest.json                                      # Points to current version + docs link
├── WINDOWS_SERVER_ADVANCED_INSTALLATION_GUIDE.md    # Installation guide for manual setup
├── 5.3.0/
│   ├── manifest.json                    # Describes available artifacts
│   ├── cdr-client-service-5.3.0.jar    # Service JAR (always included)
│   └── CdrClientWatchdog-5.3.0.zip     # Watchdog (when changed)
└── 5.3.1/
    ├── manifest.json
    ├── cdr-client-service-5.3.1.jar
    └── CdrClientWatchdog-5.3.1.zip
```

### Manifest Format

Root has a `latest.json`:
```json
{
  "version": "5.3.1",
  "manifestUrl": "https://cdr.health.swisscom.ch/share/downloads/manualInstallation/5.3.1/manifest.json",
  "installationGuideUrl": "https://cdr.health.swisscom.ch/share/downloads/manualInstallation/WINDOWS_SERVER_ADVANCED_INSTALLATION_GUIDE.md"
}
```

Each version has a `manifest.json`:
```json
{
  "version": "5.3.1",
  "releaseDate": "2026-06-01T10:00:00Z",
  "artifacts": [
    {
      "name": "Service",
      "fileName": "cdr-client-service-5.3.1.jar",
      "url": "https://cdr.health.swisscom.ch/share/downloads/manualInstallation/5.3.1/cdr-client-service-5.3.1.jar",
      "checksum": "sha256:abc123..."
    }
  ]
}
```

The UpdateService is **not** auto-updated and its artifact is not checked by the service.

Publishing is automated via Azure Pipelines on every commit to `main`.

## Updating the Update Service

The update service cannot update itself. To update the update service:

1. Stop the service:
   ```cmd
   sc stop curaLINEClientUpdateService
   ```

2. Replace files in `bin/updateservice/`

3. Start the service:
   ```cmd
   sc start curaLINEClientUpdateService
   ```

## Uninstalling the Update Service

```cmd
uninstall-service.bat
```

This stops and removes the service. Event log source is intentionally preserved to keep historical logs.

## Development and Testing
### Running the Tests

#### Via Gradle (Recommended)

```bash
# Tests run automatically before build
./gradlew buildUpdateService
```

The tests are automatically executed as part of the build process. If tests fail, the build will fail and no artifacts will be published.

#### Via .NET CLI Directly

```bash
cd cdr-client-updateservice
dotnet test CuraLineClientUpdateService.Tests.csproj --verbosity normal
```

## Legacy Windows Server Support

This service is primarily for Windows Server 2019 that don't support MSIX packages. As these older Windows Server versions reach end-of-life (Windows Server 2019 EOL: January 2029), this service can be phased out in favor of MSIX-based installation on newer Windows Server versions.

---

**Copyright © Swisscom (Schweiz) AG**
