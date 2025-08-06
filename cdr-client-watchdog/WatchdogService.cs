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

public class WatchdogService : BackgroundService
{
    private readonly ILogger<WatchdogService> _logger;
    private readonly IConfiguration _configuration;
    private readonly IHostApplicationLifetime _hostLifetime;
    private string _serviceExecutablePath; // Made non-readonly to allow updates during path resolution
    private readonly TimeSpan _restartDelay;
    private readonly TimeSpan _healthCheckInterval;
    private readonly int _maxConsecutiveFailures;
    private readonly bool _enableLogging;
    
    private Process? _serviceProcess;
    private int _consecutiveFailures = 0;
    private DateTime _lastStartTime = DateTime.MinValue;
    private bool _isStoppingSelf = false; // Track when we're actively stopping the service

    public WatchdogService(ILogger<WatchdogService> logger, IConfiguration configuration, IHostApplicationLifetime hostLifetime)
    {
        _logger = logger;
        _configuration = configuration;
        _hostLifetime = hostLifetime;
        
        // Read configuration settings
        var rawPath = _configuration["ServiceExecutablePath"] ?? "cdr-client-service.exe";
        
        // Resolve environment variables and relative paths
        _serviceExecutablePath = ResolvePath(rawPath);
        
        _restartDelay = TimeSpan.FromSeconds(_configuration.GetValue<int>("RestartDelaySeconds", 5));
        _healthCheckInterval = TimeSpan.FromSeconds(_configuration.GetValue<int>("HealthCheckIntervalSeconds", 30));
        _maxConsecutiveFailures = _configuration.GetValue<int>("MaxConsecutiveFailures", 5);
        _enableLogging = _configuration.GetValue<bool>("EnableLogging", true);
    }

    private string ResolvePath(string path)
    {
        try
        {
            // Expand environment variables
            path = Environment.ExpandEnvironmentVariables(path);
            
            // Convert to absolute path if relative
            if (!Path.IsPathRooted(path))
            {
                var serviceDirectory = Path.GetDirectoryName(Environment.ProcessPath) ?? Environment.CurrentDirectory;
                _logger.LogInformation("Resolving relative path. Service directory: {ServiceDirectory}", serviceDirectory);
                
                var baseDirectory = DetermineBaseDirectory(serviceDirectory);
                path = Path.Combine(baseDirectory, path);
                _logger.LogInformation("Resolved relative path to: {Path}", path);
            }
            
            _logger.LogInformation("Resolved service executable path to: {Path}", path);
            return path;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error resolving path: {Path}", path);
            return path; // Return original path as fallback
        }
    }

    private string DetermineBaseDirectory(string serviceDirectory)
    {
        if (serviceDirectory.Contains("WindowsApps"))
        {
            return ResolveMsixBaseDirectory(serviceDirectory);
        }
        else if (serviceDirectory.Contains("bin\\watchdog"))
        {
            // Traditional Conveyor structure: bin/watchdog/ -> bin/
            return Path.GetDirectoryName(serviceDirectory) ?? serviceDirectory;
        }
        else
        {
            // Traditional installation
            return Path.GetDirectoryName(serviceDirectory) ?? serviceDirectory;
        }
    }

    private string ResolveMsixBaseDirectory(string serviceDirectory)
    {
        if (serviceDirectory.Contains("app\\bin\\watchdog"))
        {
            return ResolveMsixAppStructure(serviceDirectory);
        }
        else
        {
            return FindMsixRootAndAddBin(serviceDirectory);
        }
    }

    private string ResolveMsixAppStructure(string serviceDirectory)
    {
        // In MSIX: app/bin/watchdog/ -> go to MSIX root/bin/
        var parts = serviceDirectory.Split('\\');
        var appIndex = Array.FindIndex(parts, p => p.Equals("app", StringComparison.OrdinalIgnoreCase));
        
        if (appIndex >= 0)
        {
            // Take everything up to (but not including) "app", then add "bin"
            var rootParts = parts.Take(appIndex).ToArray();
            return Path.Combine(string.Join("\\", rootParts), "bin");
        }
        else
        {
            return Path.GetDirectoryName(serviceDirectory) ?? serviceDirectory;
        }
    }

    private string FindMsixRootAndAddBin(string serviceDirectory)
    {
        // Try to find MSIX root and add bin
        var parts = serviceDirectory.Split('\\');
        var packageIndex = FindMsixPackageIndex(parts);
        
        if (packageIndex >= 0)
        {
            var rootParts = parts.Take(packageIndex + 1).ToArray();
            return Path.Combine(string.Join("\\", rootParts), "bin");
        }
        else
        {
            return Path.GetDirectoryName(serviceDirectory) ?? serviceDirectory;
        }
    }

    private int FindMsixPackageIndex(string[] pathParts)
    {
        for (int i = pathParts.Length - 1; i >= 0; i--)
        {
            if (pathParts[i].Contains("_") && (pathParts[i].Contains("x64") || pathParts[i].Contains("x86")))
            {
                return i;
            }
        }
        return -1;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("CDRClientWatchdog Service started");
        _logger.LogInformation("Monitoring executable: {ExecutablePath}", _serviceExecutablePath);
        _logger.LogInformation("Health check interval: {Interval} seconds", _healthCheckInterval.TotalSeconds);
        _logger.LogInformation("Restart delay: {Delay} seconds", _restartDelay.TotalSeconds);

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await MonitorService(stoppingToken);
                await Task.Delay(_healthCheckInterval, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                _logger.LogInformation("Watchdog monitoring cancelled - service is being stopped");
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error in watchdog monitoring loop");
                await Task.Delay(TimeSpan.FromSeconds(10), stoppingToken);
            }
        }

        _logger.LogInformation("CDRClientWatchdog Service stopping monitoring loop");
        await StopService();
    }

    private async Task MonitorService(CancellationToken cancellationToken)
    {
        // Check if service is running
        if (_serviceProcess == null || _serviceProcess.HasExited)
        {
            if (_serviceProcess?.HasExited == true)
            {
                var exitCode = _serviceProcess.ExitCode;
                var runDuration = DateTime.Now - _lastStartTime;
                
                if (_isStoppingSelf)
                {
                    _logger.LogInformation("CDR Client service exited as expected during watchdog shutdown (exit code: {ExitCode}, duration: {Duration})", 
                        exitCode, runDuration);
                    _isStoppingSelf = false; // Reset flag
                    _serviceProcess.Dispose();
                    _serviceProcess = null;
                    return; // Don't restart when we're stopping
                }

                if (exitCode == 0)
                {
                    // Clean exit - service should remain stopped and watchdog should stop too
                    _logger.LogInformation("CDR Client service exited cleanly (exit code 0) after running for {Duration}. Stopping watchdog service as well.", runDuration);
                    _consecutiveFailures = 0; // Reset failure count
                    _serviceProcess.Dispose();
                    _serviceProcess = null;
                    
                    // Stop the watchdog service since the main service was intentionally shut down
                    _logger.LogInformation("Initiating watchdog service shutdown due to clean CDR service exit.");
                    
                    // Use IHostApplicationLifetime to properly stop the Windows Service
                    _hostLifetime.StopApplication();
                    
                    return; // Don't restart on clean exit
                }
                else
                {
                    // Non-zero exit code - service crashed or failed
                    _consecutiveFailures++;
                    _logger.LogWarning("CDR Client service exited with error code {ExitCode} after running for {Duration}. Consecutive failures: {Count}/{Max}", 
                        exitCode, runDuration, _consecutiveFailures, _maxConsecutiveFailures);

                    if (_consecutiveFailures >= _maxConsecutiveFailures)
                    {
                        _logger.LogError("Maximum consecutive failures reached ({Count}). Stopping watchdog to prevent endless restart loop.", 
                            _maxConsecutiveFailures);
                        _serviceProcess.Dispose();
                        _serviceProcess = null;
                        
                        // Stop the entire Windows Service when max failures reached
                        _logger.LogInformation("Stopping watchdog Windows Service due to maximum consecutive failures.");
                        _hostLifetime.StopApplication();
                        return;
                    }
                }

                _serviceProcess.Dispose();
                _serviceProcess = null;
            }

            // Wait before restarting if there was a failure
            if (_consecutiveFailures > 0)
            {
                _logger.LogInformation("Waiting {Delay} seconds before restart attempt...", _restartDelay.TotalSeconds);
                await Task.Delay(_restartDelay, cancellationToken);
            }

            // Start the service if:
            // 1. We have failures (restart after crash)
            // 2. We have no failures AND no process (initial start or after clean exit with restart needed)
            if (_consecutiveFailures > 0 || _serviceProcess == null)
            {
                await StartService();
            }
            // If _consecutiveFailures == 0 AND _serviceProcess != null, it means clean exit - don't restart
        }
        else
        {
            // Service is running, reset consecutive failures
            if (_consecutiveFailures > 0)
            {
                _logger.LogInformation("Service is running normally. Resetting failure counter.");
                _consecutiveFailures = 0;
            }

            // Optional: Check if service is responsive (can be extended)
            if (_enableLogging && DateTime.Now - _lastStartTime > TimeSpan.FromMinutes(5))
            {
                _logger.LogDebug("CDR Client service is running (PID: {ProcessId}, Started: {StartTime})", 
                    _serviceProcess.Id, _lastStartTime);
            }
        }
    }

    private Task StartService()
    {
        try
        {
            if (!File.Exists(_serviceExecutablePath))
            {
                _logger.LogError("Service executable not found at configured path: {Path}. " +
                    "Please verify the ServiceExecutablePath configuration in appsettings.json. " +
                    "Current working directory: {WorkingDirectory}. " +
                    "Watchdog executable location: {WatchdogPath}. " +
                    "Stopping watchdog service due to configuration error. Fix the ServiceExecutablePath and restart the service.",
                    _serviceExecutablePath, Environment.CurrentDirectory, Environment.ProcessPath);
                
                // This is a configuration error, not a runtime failure - stop the watchdog service
                _hostLifetime.StopApplication();
                return Task.CompletedTask;
            }

            _logger.LogInformation("Starting CDR Client service: {Path}", _serviceExecutablePath);

            var startInfo = new ProcessStartInfo
            {
                FileName = _serviceExecutablePath,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = _enableLogging,
                RedirectStandardError = _enableLogging,
                WorkingDirectory = Path.GetDirectoryName(_serviceExecutablePath) ?? Environment.CurrentDirectory
            };

            _logger.LogInformation("Working directory: {WorkingDirectory}", startInfo.WorkingDirectory);

            _serviceProcess = Process.Start(startInfo);
            
            if (_serviceProcess == null)
            {
                _logger.LogError("Failed to start CDR Client service process");
                _consecutiveFailures++;
                return Task.CompletedTask;
            }

            _lastStartTime = DateTime.Now;
            _logger.LogInformation("CDR Client service started successfully (PID: {ProcessId})", _serviceProcess.Id);

            // Optional: Capture output for logging (in a separate task to avoid blocking)
            if (_enableLogging && _serviceProcess.StartInfo.RedirectStandardOutput)
            {
                _ = Task.Run(async () =>
                {
                    try
                    {
                        while (!_serviceProcess.HasExited && _serviceProcess.StandardOutput != null)
                        {
                            var line = await _serviceProcess.StandardOutput.ReadLineAsync();
                            if (!string.IsNullOrEmpty(line))
                            {
                                _logger.LogInformation("[CDR-Service] {Output}", line);
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        _logger.LogDebug(ex, "Error reading service output");
                    }
                });

                _ = Task.Run(async () =>
                {
                    try
                    {
                        while (!_serviceProcess.HasExited && _serviceProcess.StandardError != null)
                        {
                            var line = await _serviceProcess.StandardError.ReadLineAsync();
                            if (!string.IsNullOrEmpty(line))
                            {
                                _logger.LogWarning("[CDR-Service-Error] {Error}", line);
                            }
                        }
                    }
                    catch (Exception ex)
                    {
                        _logger.LogDebug(ex, "Error reading service error output");
                    }
                });
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error starting CDR Client service");
            _consecutiveFailures++;
        }
        
        return Task.CompletedTask;
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
}
