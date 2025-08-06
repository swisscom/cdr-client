# CDR Client Watchdog Service - Complete Solution

## Summary

I've created a complete C# Windows service solution to replace your current `winsw` setup with a more reliable auto-restart mechanism for your `cdr-client-service.exe`. 

## What's Been Created

### 1. Watchdog Service (`cdr-client-watchdog/`)
- **CdrClientWatchdog.csproj**: .NET 8.0 project file with all necessary dependencies
- **Program.cs**: Service entry point with Windows service hosting
- **WatchdogService.cs**: Main monitoring logic with intelligent restart capabilities
- **appsettings.json**: Configuration file for customizing behavior

### 2. Build Scripts
- **build.sh**: Linux/WSL build script
- **build.ps1**: PowerShell build script  
- **build.bat**: Windows batch build script

### 3. Installation Scripts
- **install-service.bat**: Installs the watchdog as a Windows service
- **uninstall-service.bat**: Completely removes the watchdog service (improved with force cleanup)
- **cleanup-service.ps1**: PowerShell script for robust service cleanup
- **cleanup-on-uninstall.bat**: Automatic cleanup script for integration with uninstallers

### 4. Documentation
- **README.md**: Complete usage documentation
- **INTEGRATION.md**: Integration guide with your existing build process

### 5. Updated Conveyor Configuration
Your `conveyor.conf` has been updated to:
- Reference actual script files instead of inline content (cleaner, maintainable)
- Include all watchdog service files and scripts
- Update MSIX service registration to use the new watchdog
- No code duplication - all scripts maintained as separate files

## Key Features

### Intelligent Restart Logic
- Only restarts on **non-zero exit codes** (crashes/errors)
- Respects clean exits (exit code 0)
- Configurable restart delays and failure limits
- Prevents endless restart loops

### Robust Monitoring
- Health checks every 30 seconds (configurable)
- Process lifecycle management
- Graceful shutdown handling
- Comprehensive logging

### Easy Configuration
```json
{
  "ServiceExecutablePath": "%BASE%\\cdr-client-service.exe",
  "RestartDelaySeconds": 5,
  "HealthCheckIntervalSeconds": 30,
  "MaxConsecutiveFailures": 5,
  "EnableLogging": true
}
```

## How to Use

### Step 1: Build the Watchdog (on Windows with .NET 8.0 SDK)
```bash
cd cdr-client-watchdog
.\build.bat
# or
.\build.ps1
```

### Step 2: Build Your Application with Conveyor
```bash
# From your project root
./gradlew -q printConveyorConfig
conveyor make windows-installers
```

The watchdog will be automatically included in your Windows installer.

### Step 3: After Installing Your Application
Run as Administrator:
```cmd
cd "C:\Program Files\CDR Client"
bin\install-watchdog.bat
```

## Advantages Over WinSW

1. **Better Error Handling**: Distinguishes between crashes and clean exits
2. **Configurable**: Easy JSON configuration vs XML
3. **Modern Logging**: Windows Event Log integration
4. **Failure Protection**: Intelligent failure counting and circuit breaking
5. **Process Monitoring**: Real-time health checking
6. **Graceful Shutdown**: Proper cleanup on service stop

## Next Steps

1. **Install .NET 8.0 SDK** on your Windows development machine
2. **Build the watchdog** using one of the provided build scripts
3. **Test the service** locally before deploying
4. **Update your deployment process** to use the new watchdog
5. **Monitor the service** using Windows Event Viewer

## Troubleshooting

### "Error 2: The system cannot find the file specified"

This error means the watchdog can't find the `cdr-client-service.exe` file. Here's how to fix it:

1. **Run the troubleshooting script**:
   ```cmd
   cd "C:\Program Files\CDR Client"
   bin\troubleshoot-watchdog.bat
   ```

2. **Check the configuration**:
   - Edit `bin\watchdog\appsettings.json`
   - Update `ServiceExecutablePath` to the correct path
   
3. **Common path fixes**:
   ```json
   // Try relative path (recommended)
   "ServiceExecutablePath": "cdr-client-service.exe"
   
   // Or absolute path
   "ServiceExecutablePath": "C:\\Program Files\\CDR Client\\cdr-client-service.exe"
   
   // Or relative to parent directory
   "ServiceExecutablePath": "..\\cdr-client-service.exe"
   ```

4. **Restart the watchdog service** after configuration changes:
   ```cmd
   sc stop "CDR Client Watchdog"
   sc start "CDR Client Watchdog"
   ```

### Other Common Issues

1. **Service won't start**:
   - Check that the path in `appsettings.json` is correct
   - Verify the CDR Client executable exists and is accessible
   - Check Windows Event Log for error details

2. **Service keeps restarting**:
   - Check the CDR Client logs to understand why it's exiting with non-zero codes
   - Increase `RestartDelaySeconds` if the service needs more time to initialize
   - Check `MaxConsecutiveFailures` setting

3. **Performance Issues**:
   - Increase `HealthCheckIntervalSeconds` to reduce monitoring frequency
   - Disable detailed logging by setting `EnableLogging` to false

## Testing the Watchdog

You can test the watchdog behavior by:
1. Installing the service
2. Manually stopping your CDR client process
3. Observing the automatic restart
4. Checking Windows Event Log for detailed logs

The watchdog will:
- Immediately restart if the CDR client crashes (non-zero exit)
- Not restart if the CDR client exits cleanly (zero exit code)
- Stop trying after 5 consecutive failures to prevent loops

This solution should provide much more reliable auto-restart functionality compared to your current WinSW setup.
