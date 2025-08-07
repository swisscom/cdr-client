# CDR Client Watchdog Configuration Examples

This file contains different configuration examples for different installation scenarios.

## Standard Installation (Recommended)
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
  "ServiceExecutablePath": "C:\\Program Files\\CDR Client\\bin\\cdr-client-service.exe"
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
