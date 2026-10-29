# CDR Client Watchdog Service

This is a Windows service that monitors the CDR Client service and automatically restarts it when it exits with a non-zero exit code.

The watchdog supports two execution modes:
- **Executable Mode**: Monitors and runs `cdr-client-service.exe` (Windows 10/11 via Conveyor/MSIX)
- **JAR Mode**: Monitors and runs the service as a Java JAR file (Windows Server (2019) advanced installation)

## Features

- **Automatic Restart**: Restarts the CDR Client service when it crashes or exits with non-zero exit code
- **Failure Protection**: Prevents endless restart loops by limiting consecutive failures
- **Configurable**: Settings can be adjusted via `appsettings.json`
- **Logging**: Comprehensive logging to Windows Event Log and console
- **Coordinated Shutdown**: Cancels monitoring before cleanup and terminates the monitored process tree with a bounded exit wait

## MSIX update servicing

Conveyor and Windows App Installer continue to manage MSIX updates. The watchdog
handles SCM stop requests without starting another client during shutdown.
Monitoring owns process cleanup, requests forced termination of the entire managed
process tree, and waits up to 10 seconds for the tracked process to exit. Cleanup
errors are logged and propagated rather than reported as a successful stop.

This is forced termination, not a graceful JVM shutdown. It does not guarantee
that Windows will request a stop for every update attempt, close the UI, or remove
descendants that have already outlived their tracked parent. Windows may defer
package registration while other package processes remain active.

## Configuration

Edit `appsettings.json` to configure the watchdog behavior.

### Configuration Files

- **`appsettings.json`**: Used for Conveyor/MSIX packaging (includes Conveyor-specific paths)
- **`appsettings.release.json`**: Used for manual installation/GitHub releases (generic paths)

When building with `./gradlew buildWatchdogRelease`, the release version is automatically copied to the output.

### JAR Mode Configuration

When running in JAR mode (Windows Server advanced installation), the CDR Client service requires:
- Application configuration file (`application-customer.yaml`)
- Logging configuration file (`logback-service.xml`)

These paths must be configured in the `JavaArguments` setting. See [CONFIG_EXAMPLES.md](CONFIG_EXAMPLES.md) for details.

**Example JAR Mode Configuration:**
```json
{
  "ServiceExecutionMode": "Jar",
  "ServiceJarPath": "..\\..\\lib\\cdr-client-service.jar",
  "JavaExecutablePath": "..\\..\\jre\\bin\\java.exe",
  "JavaArguments": "-Xmx512m -Dspring.config.additional-location=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/conf/application-customer.yaml -Dlogging.config=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/conf/logback-service.xml -Dcdr.client.log.directory=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/logs",
  "RestartDelaySeconds": 5,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5
}
```

**Example Executable Mode Configuration:**
```json
{
  "ServiceExecutionMode": "Executable",
  "ServiceExecutablePath": "cdr-client-service.exe",
  "RestartDelaySeconds": 2,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5
}
```

### Configuration Options

**Common Settings:**
- **RestartDelaySeconds**: Delay before restarting after a failure (default: 2 seconds)
- **HealthCheckIntervalSeconds**: How often to check if the service is running (default: 30 seconds)
- **MaxConsecutiveFailures**: Maximum number of consecutive failures before stopping the watchdog (default: 5)

**For Executable Mode:**
- **ServiceExecutionMode**: Set to `"Executable"` (default)
- **ServiceExecutablePath**: Full path to the `cdr-client-service.exe` file

**For JAR Mode:**
- **ServiceExecutionMode**: Set to `"Jar"`
- **ServiceJarPath**: Path to the service JAR file (e.g., `..\\..\\lib\\cdr-client-service.jar`)
- **JavaExecutablePath**: Path to the Java executable (e.g., `..\\..\\jre\\bin\\java.exe`)
- **JavaArguments**: Java command-line arguments, including Spring Boot configuration paths

## Testing

Run `./gradlew testWatchdog` to execute the watchdog tests. Both `buildWatchdog` and
`buildWatchdogRelease` run these tests before publishing and fail if any test fails.

## Installation

### Prerequisites
- .NET 10 Runtime (Windows) - **Required** (this service is framework-dependent)
- Administrator privileges

### Steps

1. **Build the service** (if building from source):
   ```cmd
   build.bat
   ```
   
   Note: By default, builds as framework-dependent (requires .NET 10 runtime on target system).
   For self-contained build (includes runtime): `build.bat true`

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

1. The watchdog service starts and immediately launches the CDR Client service (either as executable or JAR)
2. It monitors the CDR Client process at the configured health check interval (default: 30 seconds)
3. If the CDR Client exits with code 0 (clean exit), the watchdog respects this and doesn't restart
4. If the CDR Client exits with a non-zero code (error), the watchdog waits for the configured restart delay and restarts it
5. If there are too many consecutive failures (default: 5), the watchdog stops to prevent endless loops
6. When the watchdog service is stopped, monitoring is cancelled and its cleanup terminates the CDR Client process tree

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

### Service Does Not Start
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
