# CDR Client Watchdog Service

This is a Windows service that monitors the CDR Client service (`cdr-client-service.exe`) and automatically restarts it when it exits with a non-zero exit code.

## Features

- **Automatic Restart**: Restarts the CDR Client service when it crashes or exits with non-zero exit code
- **Failure Protection**: Prevents endless restart loops by limiting consecutive failures
- **Configurable**: Settings can be adjusted via `appsettings.json`
- **Logging**: Comprehensive logging to Windows Event Log and console
- **Graceful Shutdown**: Properly stops the monitored service when the watchdog is stopped

## Configuration

Edit `appsettings.json` to configure the watchdog behavior:

```json
{
  "ServiceExecutablePath": "C:\\Program Files\\CDR Client\\cdr-client-service.exe",
  "RestartDelaySeconds": 2,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5
}
```

### Configuration Options

- **ServiceExecutablePath**: Full path to the `cdr-client-service.exe` file
- **RestartDelaySeconds**: Delay before restarting after a failure (default: 2 seconds)
- **HealthCheckIntervalSeconds**: How often to check if the service is running (default: 30 seconds)
- **MaxConsecutiveFailures**: Maximum number of consecutive failures before stopping the watchdog (default: 5)

## Installation

### Prerequisites
- .NET 8.0 Runtime (Windows)
- Administrator privileges

### Steps

1. **Build the service** (if building from source):
   ```cmd
   build.bat
   ```

2. **Deploy the files**:
   - Copy all files from the `publish` folder to your installation directory (e.g., `C:\Program Files\CDR Client\Watchdog\`)

3. **Configure the service**:
   - Edit `appsettings.json` to set the correct path to your `cdr-client-service.exe`

4. **Install as Windows service**:
   ```cmd
   install-service.bat
   ```
   (Run as Administrator)

## Usage

### Service Management

- **Install**: Run `install-service.bat` as Administrator
- **Uninstall**: Run `uninstall-service.bat` as Administrator
- **Start**: `sc start "CDRClientWatchdog"` or use Services Manager
- **Stop**: `sc stop "CDRClientWatchdog"` or use Services Manager

### Monitoring

The service logs events to:
- **Windows Event Log**: Application log, source "CDRClientWatchdog"

**To view logs in Windows Event Viewer:**
1. Open Event Viewer (`eventvwr.msc`)
2. Navigate to: Windows Logs → Application
3. Filter by Source: "CDRClientWatchdog"
4. Set Event Level to include: Information, Warning, Error

### Service Status

You can check the service status using:
```cmd
sc query "CDRClientWatchdog"
```

Or use the Windows Services Manager (`services.msc`).

## How It Works

1. The watchdog service starts and immediately launches the CDR Client service
2. It monitors the CDR Client process every 30 seconds (configurable)
3. If the CDR Client exits with code 0 (clean exit), the watchdog respects this and doesn't restart
4. If the CDR Client exits with a non-zero code (error), the watchdog waits 5 seconds and restarts it
5. If there are too many consecutive failures (5 by default), the watchdog stops to prevent endless loops
6. When the watchdog service is stopped, it gracefully shuts down the CDR Client service

## Integration with Conveyor

To integrate this watchdog with your existing Conveyor configuration, you can:

1. Add the built watchdog service files to your Conveyor inputs
2. Configure the watchdog service in your deployment scripts
3. Update your installation scripts to install the watchdog service

Example Conveyor configuration update:

```hocon
windows {
  inputs += cdr-client-watchdog/publish/CdrClientWatchdog.exe -> bin/CdrClientWatchdog.exe
  inputs += cdr-client-watchdog/publish/appsettings.json -> bin/watchdog-appsettings.json
  inputs += cdr-client-watchdog/install-service.bat -> bin/install-watchdog.bat
  inputs += cdr-client-watchdog/uninstall-service.bat -> bin/uninstall-watchdog.bat
}
```

## Troubleshooting

### Service Won't Start
- Check that the path in `appsettings.json` is correct
- Ensure the CDR Client executable exists and is accessible
- Check Windows Event Log for error details

### Service Keeps Restarting
- Check the CDR Client logs to understand why it's exiting with non-zero codes
- Increase `RestartDelaySeconds` if the service needs more time to initialize
- Check `MaxConsecutiveFailures` setting

### Performance Issues
- Increase `HealthCheckIntervalSeconds` to reduce monitoring frequency

## Development

To run the service in development mode (console application):
```cmd
dotnet run
```

To build for production:
```cmd
dotnet publish -c Release -r win-x64 --self-contained false
```
