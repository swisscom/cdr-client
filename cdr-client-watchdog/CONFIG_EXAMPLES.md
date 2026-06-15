# CDR Client Watchdog Configuration Examples

This file contains different configuration examples for different installation scenarios.

## Configuration Files

This directory contains two appsettings files:

- **`appsettings.json`**: For Conveyor/MSIX packaging (includes Conveyor-specific paths)
- **`appsettings.release.json`**: For manual installation/GitHub releases (generic paths)

When building:
- `./gradlew buildWatchdog` → Uses `appsettings.json` → Output: `publish/` (self-contained)
- `./gradlew buildWatchdogRelease` → Uses `appsettings.release.json` → Output: `publish-release/` (framework-dependent)

## Execution Modes

The watchdog supports two execution modes:

### 1. Executable Mode (Windows 10/11 via Conveyor/MSIX)
Runs the service as a native Windows executable.

```json
{
  "ServiceExecutionMode": "Executable",
  "ServiceExecutablePath": "cdr-client-service.exe",
  "RestartDelaySeconds": 2,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5
}
```

### 2. JAR Mode (Windows Server Advanced Installation)
Runs the service as a Java JAR file.

**Basic Configuration:**
```json
{
  "ServiceExecutionMode": "Jar",
  "ServiceJarPath": "..\\..\\lib\\cdr-client-service.jar",
  "JavaExecutablePath": "..\\..\\jre\\bin\\java.exe",
  "JavaArguments": "-Xmx512m",
  "RestartDelaySeconds": 5,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5
}
```

**Production Configuration (Recommended):**
```json
{
  "ServiceExecutionMode": "Jar",
  "ServiceJarPath": "..\\..\\lib\\cdr-client-service.jar",
  "JavaExecutablePath": "..\\..\\jre\\bin\\java.exe",
  "JavaArguments": "-Xmx512m -Dspring.main.web-application-type=none -Dspring.config.additional-location=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/conf/application-customer.yaml -Dlogging.config=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/conf/logback-service.xml -Dcdr.client.log.directory=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/logs",
  "RestartDelaySeconds": 5,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5
}
```

**JavaArguments Explained:**
- `-Xmx512m`: Maximum heap size (adjust based on your requirements)
- `-Dspring.main.web-application-type=none`: Disables the web server completely (not needed for headless service)
- `-Dspring.config.additional-location`: Path to Spring Boot application YAML configuration
- `-Dlogging.config`: Path to Logback XML logging configuration
- `-Dcdr.client.log.directory`: Directory where log files will be written

**Note**: When running as a Windows service (JAR mode), the web server is disabled since there's no UI to access it. This also disables the management/actuator endpoints.

**Alternative: Using conf directory under installation:**
```json
{
  "JavaArguments": "-Xmx512m -Dspring.main.web-application-type=none -Dspring.config.additional-location=C:/Program Files/Swisscom (Schweiz) AG/curaLINEClient/conf/application-customer.yaml -Dlogging.config=C:/Program Files/Swisscom (Schweiz) AG/curaLINEClient/conf/logback-service.xml -Dcdr.client.log.directory=C:/Program Files/Swisscom (Schweiz) AG/curaLINEClient/logs"
}
```

**Alternative: If you need the web UI accessible (development/testing only):**
```json
{
  "JavaArguments": "-Xmx512m -Dspring.config.additional-location=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/conf/application-customer.yaml -Dlogging.config=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/conf/logback-service.xml -Dcdr.client.log.directory=C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/logs"
}
```
This will keep the web server enabled. The application will start with `REACTIVE` web type as configured in `application.yaml`, making the servers available at:
- Main server: `http://localhost:8191`
- Management/health: `http://localhost:8193/actuator/health`

## Standard Installation (Executable Mode - Recommended for Conveyor/MSIX)
```json
{
  "ServiceExecutablePath": "cdr-client-service.exe",
  "RestartDelaySeconds": 2,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5
}
```

## Alternative Paths (if standard doesn't work)

### Absolute Path
```json
{
  "ServiceExecutablePath": "C:\\Program Files\\Swisscom (Schweiz) AG\\curaLINEClient\\bin\\cdr-client-service.exe"
}
```

### Relative to Parent Directory (Traditional Installation)
```json
{
  "ServiceExecutablePath": "..\\cdr-client-service.exe"
}
```

### Relative to Bin Directory (Conveyor/MSIX)
```json
{
  "ServiceExecutablePath": "..\\..\\bin\\cdr-client-service.exe"
}
```

### Alternative Bin Path
```json
{
  "ServiceExecutablePath": "..\\bin\\cdr-client-service.exe"
}
```

## Environment-Specific Examples

### MSIX/Windows Store Installation
The watchdog automatically detects MSIX environments and resolves paths correctly. Use the standard configuration:
```json
{
  "ServiceExecutablePath": "cdr-client-service.exe"
}
```

## Service Behavior Configuration

### Aggressive Monitoring
```json
{
  "HealthCheckIntervalSeconds": 10,
  "RestartDelaySeconds": 2,
  "MaxConsecutiveFailures": 10
}
```

### Conservative Monitoring
```json
{
  "HealthCheckIntervalSeconds": 60,
  "RestartDelaySeconds": 30,
  "MaxConsecutiveFailures": 3
}
```

## Troubleshooting

1. **Run the troubleshooting script**: `troubleshoot.bat` (located in watchdog directory)
2. **Check Windows Event Log**: Look for "CDRClientWatchdog" events in Application log
3. **Verify file paths**: Ensure cdr-client-service.exe exists in the expected location
4. **Test manually**: Try running the service executable directly from command line

## Service Lifecycle Behavior

### Exit Code Handling
- **Exit Code 0**: Clean shutdown - watchdog service will also stop
- **Non-zero Exit Code**: Error condition - service will be restarted
- **Configuration Error**: Missing executable - watchdog service stops immediately

### Restart Logic
- Service restarts automatically on crashes (non-zero exit codes)
- Clean exits (exit code 0) stop both the CDR service and watchdog
- Maximum consecutive failures protection prevents endless restart loops

## Common Issues

### Error: "Service executable not found at configured path"
- **Cause**: ServiceExecutablePath points to non-existent file
- **Solution**: 
  1. Run `troubleshoot.bat` to see available paths
  2. Update appsettings.json with correct path
  3. Restart watchdog service

### Service starts but immediately stops
- **Cause**: CDR client service has configuration issues or exits cleanly
- **Solution**: 
  1. Check CDR client service logs
  2. Verify CDR client configuration
  3. If intentional shutdown, this is normal behavior

### Multiple restart attempts then watchdog stops
- **Cause**: CDR client service keeps crashing (MaxConsecutiveFailures reached)
- **Solution**: 
  1. Fix underlying CDR client issues
  2. Check CDR client logs for error details
  3. Increase MaxConsecutiveFailures if needed (temporarily)

### MSIX/Windows Store App Issues
- **Cause**: Path resolution in sandboxed environment
- **Solution**: Use standard relative path configuration - automatic detection handles MSIX structure
