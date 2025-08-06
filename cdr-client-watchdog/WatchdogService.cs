using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

#nullable enable

namespace CdrClientWatchdog;

/// <summary>
/// Windows service that monitors the CDR Client service and automatically restarts it on failures.
/// Implements intelligent restart logic with failure protection and graceful shutdown handling.
/// </summary>
public class WatchdogService : BackgroundService
{
    #region Private Fields
    
    private readonly ILogger<WatchdogService> _logger;
    private readonly IConfiguration _configuration;
    private readonly IHostApplicationLifetime _hostLifetime;
    
    private string _serviceExecutablePath;
    private readonly TimeSpan _restartDelay;
    private readonly TimeSpan _healthCheckInterval;
    private readonly int _maxConsecutiveFailures;
    
    private Process? _serviceProcess;
    private int _consecutiveFailures = 0;
    private DateTime _lastStartTime = DateTime.MinValue;
    private bool _isStoppingSelf = false;
    
    #endregion

    public WatchdogService(ILogger<WatchdogService> logger, IConfiguration configuration, IHostApplicationLifetime hostLifetime)
    {
        _logger = logger;
        _configuration = configuration;
        _hostLifetime = hostLifetime;
        
        _serviceExecutablePath = ResolvePath(_configuration["ServiceExecutablePath"] ?? "cdr-client-service.exe");
        _restartDelay = TimeSpan.FromSeconds(_configuration.GetValue<int>("RestartDelaySeconds", 2));
        _healthCheckInterval = TimeSpan.FromSeconds(_configuration.GetValue<int>("HealthCheckIntervalSeconds", 30));
        _maxConsecutiveFailures = _configuration.GetValue<int>("MaxConsecutiveFailures", 5);
    }

    #region Path Resolution Methods

    private string ResolvePath(string path)
    {
        try
        {
            // Step 1: Expand any environment variables (e.g., %BASE%, %USERPROFILE%)
            var expandedPath = Environment.ExpandEnvironmentVariables(path);
            
            // Step 2: Convert relative paths to absolute paths
            if (!Path.IsPathRooted(expandedPath))
            {
                var watchdogDirectory = GetWatchdogServiceDirectory();
                var baseDirectory = DetermineBaseDirectory(watchdogDirectory);
                expandedPath = Path.Combine(baseDirectory, expandedPath);
                _logger.LogInformation("Resolved relative path '{OriginalPath}' to '{ResolvedPath}' via base directory '{BaseDirectory}'", 
                    path, expandedPath, baseDirectory);
            }
            else
            {
                _logger.LogInformation("Using absolute service executable path: {Path}", expandedPath);
            }
            
            return expandedPath;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error resolving path: {Path}. Using original path as fallback.", path);
            return path;
        }
    }

    private string GetWatchdogServiceDirectory()
    {
        return Path.GetDirectoryName(Environment.ProcessPath) ?? Environment.CurrentDirectory;
    }

    private string DetermineBaseDirectory(string watchdogDirectory)
    {
        if (IsRunningInMsixEnvironment(watchdogDirectory))
        {
            var resolvedDirectory = ResolveMsixBaseDirectory(watchdogDirectory);
            _logger.LogInformation("Detected MSIX environment, resolved base directory: {Directory}", resolvedDirectory);
            return resolvedDirectory;
        }
        else if (IsTraditionalConveyorStructure(watchdogDirectory))
        {
            // Traditional Conveyor structure: bin/watchdog/ -> bin/
            var parentDirectory = Path.GetDirectoryName(watchdogDirectory);
            _logger.LogInformation("Detected traditional Conveyor structure, using parent directory: {Directory}", parentDirectory);
            return parentDirectory ?? watchdogDirectory;
        }
        else
        {
            // Traditional installation - go up one level from watchdog directory
            var parentDirectory = Path.GetDirectoryName(watchdogDirectory);
            _logger.LogInformation("Detected traditional installation, using parent directory: {Directory}", parentDirectory);
            return parentDirectory ?? watchdogDirectory;
        }
    }

    private bool IsRunningInMsixEnvironment(string directory)
    {
        return directory.Contains("WindowsApps", StringComparison.OrdinalIgnoreCase);
    }

    private bool IsTraditionalConveyorStructure(string directory)
    {
        return directory.Contains("bin\\watchdog", StringComparison.OrdinalIgnoreCase);
    }

    private string ResolveMsixBaseDirectory(string watchdogDirectory)
    {
        if (HasMsixAppStructure(watchdogDirectory))
        {
            return ResolveMsixAppStructure(watchdogDirectory);
        }
        else
        {
            return FindMsixRootAndAddBin(watchdogDirectory);
        }
    }

    private bool HasMsixAppStructure(string directory)
    {
        return directory.Contains("app\\bin\\watchdog", StringComparison.OrdinalIgnoreCase);
    }

    private string ResolveMsixAppStructure(string watchdogDirectory)
    {
        var pathParts = watchdogDirectory.Split('\\');
        var appIndex = Array.FindIndex(pathParts, part => 
            part.Equals("app", StringComparison.OrdinalIgnoreCase));
        
        if (appIndex >= 0)
        {
            // Take everything up to (but not including) "app", then add "bin"
            var msixRootParts = pathParts.Take(appIndex).ToArray();
            var resolvedPath = Path.Combine(string.Join("\\", msixRootParts), "bin");
            return resolvedPath;
        }
        else
        {
            _logger.LogWarning("Expected 'app' directory not found in MSIX path, using parent directory");
            return Path.GetDirectoryName(watchdogDirectory) ?? watchdogDirectory;
        }
    }

    private string FindMsixRootAndAddBin(string watchdogDirectory)
    {
        var pathParts = watchdogDirectory.Split('\\');
        var packageIndex = FindMsixPackageIndex(pathParts);
        
        if (packageIndex >= 0)
        {
            var msixRootParts = pathParts.Take(packageIndex + 1).ToArray();
            var resolvedPath = Path.Combine(string.Join("\\", msixRootParts), "bin");
            _logger.LogInformation("Found MSIX package root, resolved path: {Path}", resolvedPath);
            return resolvedPath;
        }
        else
        {
            _logger.LogWarning("MSIX package directory pattern not found, using parent directory");
            return Path.GetDirectoryName(watchdogDirectory) ?? watchdogDirectory;
        }
    }

    private int FindMsixPackageIndex(string[] pathParts)
    {
        for (int i = pathParts.Length - 1; i >= 0; i--)
        {
            var part = pathParts[i];
            if (IsMsixPackageDirectory(part))
            {
                _logger.LogDebug("Found MSIX package directory: {Directory} at index {Index}", part, i);
                return i;
            }
        }
        
        _logger.LogDebug("No MSIX package directory pattern found in path");
        return -1;
    }

    private bool IsMsixPackageDirectory(string directoryName)
    {
        return directoryName.Contains("_") && 
               (directoryName.Contains("x64", StringComparison.OrdinalIgnoreCase) || 
                directoryName.Contains("x86", StringComparison.OrdinalIgnoreCase));
    }

    #endregion

    #region Service Monitoring and Control

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        LogServiceStartup();
        
        try
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                await MonitorServiceHealth(stoppingToken);
                await WaitForNextHealthCheck(stoppingToken);
            }
        }
        catch (OperationCanceledException)
        {
            _logger.LogInformation("Watchdog monitoring cancelled - service shutdown requested");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unexpected error in watchdog monitoring loop");
        }
        finally
        {
            await PerformShutdownCleanup();
        }
    }

    private void LogServiceStartup()
    {
        _logger.LogInformation("CDRClientWatchdog Service started. Monitoring: {ExecutablePath}, Health check: {Interval}s, Restart delay: {Delay}s, Max failures: {MaxFailures}", 
            _serviceExecutablePath, _healthCheckInterval.TotalSeconds, _restartDelay.TotalSeconds, _maxConsecutiveFailures);
    }

    private async Task WaitForNextHealthCheck(CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(_healthCheckInterval, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            // Expected during shutdown - re-throw to exit the loop
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error during health check delay, using fallback delay");
            await Task.Delay(TimeSpan.FromSeconds(10), CancellationToken.None);
        }
    }

    private async Task PerformShutdownCleanup()
    {
        _logger.LogInformation("CDRClientWatchdog Service stopping monitoring loop");
        await StopService();
    }

    private async Task MonitorServiceHealth(CancellationToken cancellationToken)
    {
        if (IsManagedServiceRunning())
        {
            await HandleRunningService();
        }
        else
        {
            await HandleStoppedService(cancellationToken);
        }
    }

    private bool IsManagedServiceRunning()
    {
        return _serviceProcess != null && !_serviceProcess.HasExited;
    }

    private async Task HandleRunningService()
    {
        if (_consecutiveFailures > 0)
        {
            _logger.LogInformation("CDR Client service is running normally. Resetting failure counter.");
            _consecutiveFailures = 0;
        }

        await LogServiceStatusIfEnabled();
    }

    private async Task LogServiceStatusIfEnabled()
    {
        if (_serviceProcess != null && DateTime.Now - _lastStartTime > TimeSpan.FromMinutes(5))
        {
            await Task.Run(() =>
            {
                _logger.LogDebug("CDR Client service is running (PID: {ProcessId}, Started: {StartTime}, Uptime: {Uptime})", 
                    _serviceProcess.Id, _lastStartTime, DateTime.Now - _lastStartTime);
            });
        }
    }

    private async Task HandleStoppedService(CancellationToken cancellationToken)
    {
        if (_serviceProcess?.HasExited == true)
        {
            await AnalyzeServiceExit();
        }

        await AttemptServiceRestartIfNeeded(cancellationToken);
    }

    private async Task AnalyzeServiceExit()
    {
        if (_serviceProcess == null) return;

        var exitCode = _serviceProcess.ExitCode;
        var runDuration = DateTime.Now - _lastStartTime;

        if (_isStoppingSelf)
        {
            await HandleExpectedShutdown(exitCode, runDuration);
            return;
        }

        if (exitCode == 0)
        {
            await HandleCleanExit(runDuration);
        }
        else
        {
            await HandleErrorExit(exitCode, runDuration);
        }

        CleanupServiceProcess();
    }

    private async Task HandleExpectedShutdown(int exitCode, TimeSpan runDuration)
    {
        _logger.LogInformation(
            "CDR Client service exited as expected during watchdog shutdown (exit code: {ExitCode}, duration: {Duration})", 
            exitCode, runDuration);
        
        _isStoppingSelf = false;
        await Task.CompletedTask; // For consistency with async pattern
    }

    private async Task HandleCleanExit(TimeSpan runDuration)
    {
        _logger.LogInformation(
            "CDR Client service exited cleanly (exit code 0) after running for {Duration}. Stopping watchdog service as well.", 
            runDuration);
        
        _consecutiveFailures = 0;
        
        _logger.LogInformation("Initiating watchdog service shutdown due to clean CDR service exit.");
        _hostLifetime.StopApplication();
        
        await Task.CompletedTask; // For consistency with async pattern
    }

    private async Task HandleErrorExit(int exitCode, TimeSpan runDuration)
    {
        _consecutiveFailures++;
        
        _logger.LogWarning(
            "CDR Client service exited with error code {ExitCode} after running for {Duration}. Consecutive failures: {Count}/{Max}", 
            exitCode, runDuration, _consecutiveFailures, _maxConsecutiveFailures);

        if (_consecutiveFailures >= _maxConsecutiveFailures)
        {
            await HandleMaxFailuresReached();
        }
        
        await Task.CompletedTask; // For consistency with async pattern
    }

    private async Task HandleMaxFailuresReached()
    {
        _logger.LogError(
            "Maximum consecutive failures reached ({Count}). Stopping watchdog to prevent endless restart loop.", 
            _maxConsecutiveFailures);
        
        _logger.LogInformation("Stopping watchdog Windows Service due to maximum consecutive failures.");
        _hostLifetime.StopApplication();
        
        await Task.CompletedTask; // For consistency with async pattern
    }

    private void CleanupServiceProcess()
    {
        _serviceProcess?.Dispose();
        _serviceProcess = null;
    }

    private async Task AttemptServiceRestartIfNeeded(CancellationToken cancellationToken)
    {
        if (ShouldRestartService())
        {
            await WaitBeforeRestart(cancellationToken);
            await StartManagedService();
        }
    }

    private bool ShouldRestartService()
    {
        return _consecutiveFailures > 0 || _serviceProcess == null;
    }

    private async Task WaitBeforeRestart(CancellationToken cancellationToken)
    {
        if (_consecutiveFailures > 0)
        {
            _logger.LogInformation("Waiting {Delay} seconds before restart attempt (consecutive failures: {Count})", 
                _restartDelay.TotalSeconds, _consecutiveFailures);
            await Task.Delay(_restartDelay, cancellationToken);
        }
    }

    #endregion

    #region Service Lifecycle Management

    private async Task StartManagedService()
    {
        try
        {
            if (!ValidateServiceExecutable())
            {
                return; // Validation failed, watchdog will be stopped
            }

            var processStartInfo = CreateProcessStartInfo();
            _serviceProcess = StartServiceProcess(processStartInfo);
            
            if (_serviceProcess == null)
            {
                _logger.LogError("Failed to start CDR Client service process");
                _consecutiveFailures++;
                return;
            }

            RecordSuccessfulStart();
            await Task.CompletedTask; // For consistency with async pattern
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error starting CDR Client service");
            _consecutiveFailures++;
        }
    }

    private bool ValidateServiceExecutable()
    {
        if (File.Exists(_serviceExecutablePath))
        {
            return true;
        }

        _logger.LogError(
            "Service executable not found at configured path: {Path}. " +
            "Please verify the ServiceExecutablePath configuration in appsettings.json. " +
            "Current working directory: {WorkingDirectory}. " +
            "Watchdog executable location: {WatchdogPath}. " +
            "Stopping watchdog service due to configuration error. Fix the ServiceExecutablePath and restart the service.",
            _serviceExecutablePath, Environment.CurrentDirectory, Environment.ProcessPath);

        // This is a configuration error, not a runtime failure - stop the watchdog service
        _hostLifetime.StopApplication();
        return false;
    }

    private ProcessStartInfo CreateProcessStartInfo()
    {
        var workingDirectory = Path.GetDirectoryName(_serviceExecutablePath) ?? Environment.CurrentDirectory;
        
        _logger.LogInformation("Starting CDR Client service: {Path} from working directory: {WorkingDirectory}", 
            _serviceExecutablePath, workingDirectory);

        return new ProcessStartInfo
        {
            FileName = _serviceExecutablePath,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = false,
            RedirectStandardError = false,
            WorkingDirectory = workingDirectory
        };
    }

    private Process? StartServiceProcess(ProcessStartInfo startInfo)
    {
        try
        {
            return Process.Start(startInfo);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to start service process");
            return null;
        }
    }

    private void RecordSuccessfulStart()
    {
        if (_serviceProcess == null) return;
        
        _lastStartTime = DateTime.Now;
        _logger.LogInformation("CDR Client service started successfully (PID: {ProcessId})", _serviceProcess.Id);
    }

    private async Task StopService()
    {
        if (_serviceProcess != null && !_serviceProcess.HasExited)
        {
            _isStoppingSelf = true; // Mark that we're actively stopping the service
            
            try
            {
                _logger.LogInformation("Stopping CDR Client service (PID: {ProcessId})", _serviceProcess.Id);
                _logger.LogInformation("Service has been running for: {Duration}", DateTime.Now - _lastStartTime);
                
                // For console applications, CloseMainWindow() typically doesn't work
                // Try a more direct approach by sending SIGTERM first
                _logger.LogInformation("Sending termination signal to CDR Client service...");
                
                _serviceProcess.Kill(entireProcessTree: false);
                
                // Wait for graceful shutdown
                _logger.LogInformation("Waiting up to 30 seconds for graceful shutdown...");
                if (_serviceProcess.WaitForExit(TimeSpan.FromSeconds(30)))
                {
                    _logger.LogInformation("CDR Client service stopped gracefully (exit code: {ExitCode})", _serviceProcess.ExitCode);
                }
                else
                {
                    _logger.LogWarning("Service didn't stop within 30 seconds, forcing termination...");
                    
                    try
                    {
                        if (!_serviceProcess.HasExited)
                        {
                            _serviceProcess.Kill(entireProcessTree: true);
                            await _serviceProcess.WaitForExitAsync();
                            _logger.LogWarning("CDR Client service forcefully terminated (exit code: {ExitCode})", _serviceProcess.ExitCode);
                        }
                    }
                    catch (Exception ex)
                    {
                        _logger.LogWarning(ex, "Error during forced termination, process may have already exited");
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error stopping CDR Client service");
            }
            finally
            {
                _logger.LogInformation("Cleaning up service process resources");
                _serviceProcess?.Dispose();
                _serviceProcess = null;
            }
        }
        else if (_serviceProcess != null)
        {
            _logger.LogInformation("CDR Client service process already exited (PID was: {ProcessId})", _serviceProcess.Id);
            _serviceProcess?.Dispose();
            _serviceProcess = null;
        }
        else
        {
            _logger.LogInformation("No CDR Client service process to stop");
        }
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        _logger.LogInformation("=== Watchdog service stop requested ===");
        _logger.LogInformation("Reason: Watchdog service is being stopped/uninstalled");
        
        // Ensure we properly clean up when the watchdog service itself is being stopped/uninstalled
        await StopService();
        
        // Give a moment for cleanup
        _logger.LogInformation("Waiting for final cleanup...");
        try
        {
            await Task.Delay(1000, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            _logger.LogInformation("Cleanup wait cancelled during shutdown");
        }
        
        await base.StopAsync(cancellationToken);
        _logger.LogInformation("=== Watchdog service stopped successfully ===");
    }

    #endregion
}
