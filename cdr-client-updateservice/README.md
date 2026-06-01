# curaLINE Client updateservice

This service automatically manages updates for CDR Client on Windows Server (especially 2019 and older versions), where MSIX auto-update functionality is not available.

> ⚠️ **Note**: If you're using **Windows Server 2022 or newer**, please use the default MSIX installer instead. This advanced installation method is primarily for Windows Server 2019 version that don't support MSIX packages.

## Overview

The Update Service:
- Checks for new versions on GitHub every 2 hours (configurable, with immediate check at startup)
- Downloads available artifacts from GitHub releases (only those present in the release)
- Stops the Watchdog service (which stops the CDR service)
- Applies updates to present components (Service JAR, Watchdog)
- Restarts the Watchdog service
- Tracks component versions independently
- Logs all operations to Windows Event Log

### Intelligent Component Updates

**Components are updated independently based on what's available in each release:**

- **Service JAR**: Always included in every release
- **Watchdog**: Only included when watchdog code changes

This means:
- Release `5.3.1` might only update the Service JAR (if only service code changed)
- Release `5.3.2` might update Service + Watchdog (if both changed)
- Components retain their previous versions until a release includes an update for them

## Architecture

```
curaLINEClientUpdateService (this service, displayed as "curaLINE Client updateservice")
    ↓ manages/stops/starts
CDRClientWatchdog (runs CDR service as JAR)
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

1. **Install the Service** (choose one):
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
  "GitHubRepository": "swisscom/cdr-client",
  "WatchdogServiceName": "CDRClientWatchdog",
  "InstallationPath": "",
  "CurrentVersions": {
    "Service": "1.0.0",
    "Watchdog": "1.0.0",
    "UpdateService": "1.0.0"
  },
  "Artifacts": {
    "Service": {
      "FileName": "cdr-client-service-{version}.jar",
      "TargetPath": "lib/cdr-client-service.jar"
    },
    "Watchdog": {
      "FileName": "CdrClientWatchdog-{version}.zip",
      "TargetPath": "bin/watchdog/"
    }
  },
  "JavaExecutablePath": "bin/jre/bin/java.exe"
}
```

### Configuration Parameters

- **UpdateCheckIntervalHours**: How often to check for updates (default: 2 hours)
- **GitHubRepository**: GitHub repository in format "owner/repo"
- **WatchdogServiceName**: Name of the watchdog Windows service
- **InstallationPath**: Root installation path (auto-detected if empty)
- **CurrentVersions**: Current installed versions (updated automatically after each update)
- **Artifacts**: Configuration for each updateable component
  - **FileName**: Template for GitHub release artifact name (use `{version}` placeholder)
  - **TargetPath**: Where to install the artifact (relative to installation path)
- **JavaExecutablePath**: Path to Java executable for running JARs

## Update Process

When a new version is detected on GitHub:

1. **Fetch**: Query GitHub API for latest release
2. **Download**: Download specified artifacts (Service JAR, optionally Watchdog ZIP)
3. **Stop**: Stop watchdog service (which stops CDR service)
4. **Apply**: Copy JAR to target location / Extract ZIP for watchdog
5. **Config**: Update current versions in appsettings.json
6. **Restart**: Restart watchdog service (which restarts CDR service using the new JAR)
7. **Cleanup**: Delete temporary files

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

### Service won't start
1. Check Event Log for error messages
2. Verify CDRClientWatchdog service exists and is installed
3. Verify `appsettings.json` configuration is valid
4. Check GitHub repository accessibility

### Updates not being applied
1. Check Event Log for download/extraction errors
2. Verify network connectivity to GitHub
3. Check that GitHub releases contain expected artifacts
4. Verify watchdog service can be stopped/started
5. Verify Java executable path is correct

### Manual update check
To force an immediate update check, restart the service:
```cmd
sc stop curaLINEClientUpdateService
sc start curaLINEClientUpdateService
```

## GitHub Release Artifacts

For automatic updates to work, each GitHub release must include:

1. **cdr-client-service-{version}.jar** - Main service (required for every release)
2. **CdrClientWatchdog-{version}.zip** - (Optional) Only when watchdog has changes

The UpdateService is **not** auto-updated and its artifact is not checked by the service.

See `RELEASE_ARTIFACTS.md` for build instructions.

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

## Uninstallation

```cmd
uninstall-service.bat
```

This stops and removes the service. Event log source is intentionally preserved to keep historical logs.

## Legacy Windows Server Support

This service is primarily for Windows Server 2019 that don't support MSIX packages. As these older Windows Server versions reach end-of-life (Windows Server 2019 EOL: January 2029), this service can be phased out in favor of MSIX-based installation on newer Windows Server versions.

---

**Copyright © Swisscom (Schweiz) AG**



