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
/// Implements restart logic with failure protection and bounded process-tree shutdown.
/// </summary>
public class WatchdogService : BackgroundService
{
    #region Private Fields
    
    private readonly ILogger<WatchdogService> _logger;
    private readonly IConfiguration _configuration;
    private readonly IHostApplicationLifetime _hostLifetime;
    
    private readonly string _serviceExecutionMode;
    private string _serviceExecutablePath;
    private readonly string _serviceJarPath;
    private readonly string _javaExecutablePath;
    private readonly string _javaArguments;
    private readonly TimeSpan _restartDelay;
    private readonly TimeSpan _healthCheckInterval;
    private readonly int _maxConsecutiveFailures;
    
    private Process? _watchedProcess;
    private int _consecutiveFailures = 0;
    private DateTime _lastStartTime = DateTime.MinValue;
    private readonly object _lifecycleLock = new();
    private bool _isStopping;
    private static readonly TimeSpan ProcessShutdownTimeout = TimeSpan.FromSeconds(10);
    
    #endregion

    public WatchdogService(ILogger<WatchdogService> logger, IConfiguration configuration, IHostApplicationLifetime hostLifetime)
    {
        _logger = logger;
        _configuration = configuration;
        _hostLifetime = hostLifetime;
        
        _serviceExecutionMode = _configuration["ServiceExecutionMode"] ?? "Executable";
        _serviceExecutablePath = ResolvePath(_configuration["ServiceExecutablePath"] ?? "cdr-client-service.exe");
        _serviceJarPath = ResolvePath(_configuration["ServiceJarPath"] ?? "../lib/cdr-client-service.jar");
        _javaExecutablePath = ResolvePath(_configuration["JavaExecutablePath"] ?? "java");
        _javaArguments = _configuration["JavaArguments"] ?? "";
        _restartDelay = TimeSpan.FromSeconds(_configuration.GetValue<int>("RestartDelaySeconds", 2));
        _healthCheckInterval = TimeSpan.FromSeconds(_configuration.GetValue<int>("HealthCheckIntervalSeconds", 30));
        _maxConsecutiveFailures = _configuration.GetValue<int>("MaxConsecutiveFailures", 5);

        _logger.LogInformation("Watchdog initialized in {Mode} mode", _serviceExecutionMode);
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
                var candidate = Path.GetFullPath(Path.Combine(baseDirectory, expandedPath));

                _logger.LogDebug("Attempting to resolve service executable using base directory '{BaseDirectory}' -> '{Candidate}'", baseDirectory, candidate);

                if (File.Exists(candidate))
                {
                    _logger.LogInformation("Resolved relative path '{OriginalPath}' to existing path '{ResolvedPath}'", path, candidate);
                    return candidate;
                }

                // If the candidate doesn't exist, try searching ancestor directories for a 'bin\<exe>' layout.
                var found = SearchAncestorBinsForExecutable(watchdogDirectory, expandedPath);
                if (!string.IsNullOrEmpty(found))
                {
                    _logger.LogInformation("Resolved relative path '{OriginalPath}' to discovered path '{ResolvedPath}' via ancestor bin search", path, found);
                    return found;
                }

                // As additional fallback, return the original candidate (may be used to report a clear error)
                _logger.LogWarning("Could not find executable at '{Candidate}' or via ancestor search; returning candidate for diagnostic/error reporting.", candidate);
                return candidate;
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

    // Search upward from the start directory for a 'bin' folder containing the requested executable.
    private string? SearchAncestorBinsForExecutable(string startDirectory, string exeRelativePath)
    {
        try
        {
            var dirInfo = new DirectoryInfo(startDirectory);

            while (dirInfo != null)
            {
                // Candidate 1: ancestor\bin\<exe>
                var candidate1 = Path.GetFullPath(Path.Combine(dirInfo.FullName, "bin", exeRelativePath));
                _logger.LogDebug("Searching for executable candidate: {Candidate}", candidate1);
                if (File.Exists(candidate1)) return candidate1;

                // Candidate 2: ancestor\app\bin\<exe> (covers layouts where 'app' is a sibling)
                var candidate2 = Path.GetFullPath(Path.Combine(dirInfo.FullName, "app", "bin", exeRelativePath));
                _logger.LogDebug("Searching for executable candidate: {Candidate}", candidate2);
                if (File.Exists(candidate2)) return candidate2;

                dirInfo = dirInfo.Parent;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Error during ancestor bin search");
        }

        return null;
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
        using var monitoringCancellation = CancellationTokenSource.CreateLinkedTokenSource(
            stoppingToken, _hostLifetime.ApplicationStopping);
        var monitoringToken = monitoringCancellation.Token;
        LogServiceStartup();
        
        try
        {
            while (!monitoringToken.IsCancellationRequested)
            {
                await MonitorServiceHealth(monitoringToken);
                await WaitForNextHealthCheck(monitoringToken);
            }
        }
        catch (OperationCanceledException) when (monitoringToken.IsCancellationRequested)
        {
            _logger.LogInformation("Watchdog monitoring cancelled - service shutdown requested");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unexpected error in watchdog monitoring loop");
            throw;
        }
        finally
        {
            await PerformShutdownCleanup();
        }
    }

    private void LogServiceStartup()
    {
        if (_serviceExecutionMode.Equals("Jar", StringComparison.OrdinalIgnoreCase))
        {
            _logger.LogInformation(
                "CDRClientWatchdog Service started in JAR mode. Monitoring: Java={JavaPath}, JAR={JarPath}, Arguments={Arguments}, Health check: {Interval}s, Restart delay: {Delay}s, Max failures: {MaxFailures}",
                _javaExecutablePath, _serviceJarPath, _javaArguments, _healthCheckInterval.TotalSeconds, _restartDelay.TotalSeconds, _maxConsecutiveFailures);
        }
        else
        {
            _logger.LogInformation(
                "CDRClientWatchdog Service started in Executable mode. Monitoring: {ExecutablePath}, Health check: {Interval}s, Restart delay: {Delay}s, Max failures: {MaxFailures}",
                _serviceExecutablePath, _healthCheckInterval.TotalSeconds, _restartDelay.TotalSeconds, _maxConsecutiveFailures);
        }
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
            await Task.Delay(TimeSpan.FromSeconds(10), cancellationToken);
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
        return _watchedProcess != null && !_watchedProcess.HasExited;
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
        if (_watchedProcess != null && DateTime.Now - _lastStartTime > TimeSpan.FromMinutes(5))
        {
            await Task.Run(() =>
            {
                _logger.LogDebug("CDR Client service is running (PID: {ProcessId}, Started: {StartTime}, Uptime: {Uptime})", 
                    _watchedProcess.Id, _lastStartTime, DateTime.Now - _lastStartTime);
            });
        }
    }

    private async Task HandleStoppedService(CancellationToken cancellationToken)
    {
        if (_watchedProcess?.HasExited == true)
        {
            await AnalyzeServiceExit();
        }

        await AttemptServiceRestartIfNeeded(cancellationToken);
    }

    private async Task AnalyzeServiceExit()
    {
        if (_watchedProcess == null) return;

        var exitCode = _watchedProcess.ExitCode;
        var runDuration = DateTime.Now - _lastStartTime;

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
        _watchedProcess?.Dispose();
        _watchedProcess = null;
    }

    private async Task AttemptServiceRestartIfNeeded(CancellationToken cancellationToken)
    {
        if (!cancellationToken.IsCancellationRequested && ShouldRestartService())
        {
            await WaitBeforeRestart(cancellationToken);
            await StartManagedService(cancellationToken);
        }
    }

    private bool ShouldRestartService()
    {
        lock (_lifecycleLock)
        {
            return !_isStopping &&
                   !_hostLifetime.ApplicationStopping.IsCancellationRequested &&
                   _consecutiveFailures < _maxConsecutiveFailures &&
                   (_consecutiveFailures > 0 || _watchedProcess == null);
        }
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

    private async Task StartManagedService(CancellationToken cancellationToken)
    {
        try
        {
            // Serialize the last shutdown check and process creation with StopAsync.
            lock (_lifecycleLock)
            {
                if (cancellationToken.IsCancellationRequested || !ShouldRestartService())
                {
                    return;
                }

                if (!ValidateServiceExecutable())
                {
                    return;
                }

                var processStartInfo = CreateProcessStartInfo();
                _watchedProcess = StartServiceProcess(processStartInfo);

                if (_watchedProcess == null)
                {
                    throw new InvalidOperationException("Process.Start returned no CDR Client service process.");
                }

                RecordSuccessfulStart();
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error starting CDR Client service");
            _consecutiveFailures++;
            if (_consecutiveFailures >= _maxConsecutiveFailures)
            {
                await HandleMaxFailuresReached();
            }
        }
    }

    private bool ValidateServiceExecutable()
    {
        if (_serviceExecutionMode.Equals("Jar", StringComparison.OrdinalIgnoreCase))
        {
            // Validate JAR mode requirements
            if (!File.Exists(_javaExecutablePath))
            {
                _logger.LogError(
                    "Java executable not found at configured path: {Path}. " +
                    "Please verify the JavaExecutablePath configuration in appsettings.json. " +
                    "Stopping watchdog service due to configuration error.",
                    _javaExecutablePath);
                _hostLifetime.StopApplication();
                return false;
            }

            if (!File.Exists(_serviceJarPath))
            {
                _logger.LogError(
                    "Service JAR not found at configured path: {Path}. " +
                    "Please verify the ServiceJarPath configuration in appsettings.json. " +
                    "Stopping watchdog service due to configuration error.",
                    _serviceJarPath);
                _hostLifetime.StopApplication();
                return false;
            }

            return true;
        }
        else
        {
            // Validate Executable mode requirements
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
    }

    private ProcessStartInfo CreateProcessStartInfo()
    {
        ProcessStartInfo startInfo;

        if (_serviceExecutionMode.Equals("Jar", StringComparison.OrdinalIgnoreCase))
        {
            // Running as JAR with Java - use JAR directory as working directory
            var workingDirectory = Path.GetDirectoryName(_serviceJarPath) ?? Environment.CurrentDirectory;

            startInfo = new ProcessStartInfo
            {
                FileName = _javaExecutablePath,
                WorkingDirectory = workingDirectory,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            // Use ArgumentList instead of Arguments string for proper quoting
            // Parse JavaArguments and add each argument separately
            if (!string.IsNullOrWhiteSpace(_javaArguments))
            {
                foreach (var arg in ParseJavaArguments(_javaArguments))
                {
                    startInfo.ArgumentList.Add(arg);
                    _logger.LogDebug("Added argument: {Arg}", arg);
                }
            }

            // Add the JAR file argument
            startInfo.ArgumentList.Add("-jar");
            startInfo.ArgumentList.Add(_serviceJarPath);

            _logger.LogInformation("Starting CDR Client service in JAR mode with {Count} arguments from working directory: {WorkingDirectory}",
                startInfo.ArgumentList.Count, workingDirectory);
            _logger.LogDebug("Full command: {Command} {Arguments}",
                _javaExecutablePath,
                string.Join(" ", startInfo.ArgumentList.Select(a => a.Contains(" ") ? $"\"{a}\"" : a)));
        }
        else
        {
            // Running as executable - use executable directory as working directory
            var workingDirectory = Path.GetDirectoryName(_serviceExecutablePath) ?? Environment.CurrentDirectory;

            _logger.LogInformation("Starting CDR Client service in Executable mode: {Path} from working directory: {WorkingDirectory}",
                _serviceExecutablePath, workingDirectory);

            startInfo = new ProcessStartInfo
            {
                FileName = _serviceExecutablePath,
                Arguments = string.Empty,
                WorkingDirectory = workingDirectory,
                UseShellExecute = false,
                CreateNoWindow = true
            };
        }

        return startInfo;
    }

    /// <summary>
    /// Parses Java arguments from the configuration string.
    /// Specially handles -D system properties to keep -Dkey=value together even when value contains spaces.
    /// This is critical for paths like "C:\Program Files\..." which would otherwise be split incorrectly.
    /// </summary>
    private IEnumerable<string> ParseJavaArguments(string arguments)
    {
        if (string.IsNullOrWhiteSpace(arguments))
            yield break;

        var tokens = System.Text.RegularExpressions.Regex.Split(arguments, @"\s+");

        for (int i = 0; i < tokens.Length; i++)
        {
            var token = tokens[i].Trim();
            if (string.IsNullOrEmpty(token))
                continue;

            // Check if this is a -D property with an = sign
            if (token.StartsWith("-D") && token.Contains('='))
            {
                // This is a -D property. The value part might be split across multiple tokens if it has spaces.
                // We need to re-assemble it.

                var equalsIndex = token.IndexOf('=');
                var key = token.Substring(0, equalsIndex + 1); // Include the =
                var valuePart = token.Substring(equalsIndex + 1);

                // If the value part looks like a path (contains : or \) and doesn't end properly,
                // it might be incomplete due to space splitting
                if ((valuePart.Contains('\\') || valuePart.Contains('/') || valuePart.Contains(':')) &&
                    i + 1 < tokens.Length)
                {
                    // Reconstruct the complete value by consuming tokens until we hit the next argument (starts with -)
                    var completeValue = new System.Text.StringBuilder(valuePart);

                    while (i + 1 < tokens.Length && !tokens[i + 1].StartsWith("-"))
                    {
                        i++;
                        completeValue.Append(' ');
                        completeValue.Append(tokens[i]);
                    }

                    yield return key + completeValue.ToString();
                }
                else
                {
                    yield return token;
                }
            }
            else
            {
                // Handle quoted arguments
                if (token.StartsWith("\"") && token.EndsWith("\""))
                {
                    yield return token.Substring(1, token.Length - 2);
                }
                else
                {
                    yield return token;
                }
            }
        }
    }

    private Process? StartServiceProcess(ProcessStartInfo startInfo)
    {
        return Process.Start(startInfo);
    }

    private void RecordSuccessfulStart()
    {
        if (_watchedProcess == null) return;
        
        _lastStartTime = DateTime.Now;
        _logger.LogInformation("CDR Client service started successfully (PID: {ProcessId})", _watchedProcess.Id);
    }

    private async Task StopService()
    {
        var process = _watchedProcess;
        if (process == null)
        {
            _logger.LogInformation("No CDR Client service process to stop");
            return;
        }

        try
        {
            if (process.HasExited)
            {
                _logger.LogInformation("CDR Client service process already exited (PID was: {ProcessId})", process.Id);
                return;
            }

            _logger.LogInformation("Terminating CDR Client service process tree (PID: {ProcessId})", process.Id);
            using var timeout = new CancellationTokenSource(ProcessShutdownTimeout);
            try
            {
                process.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException) when (process.HasExited)
            {
                _logger.LogInformation("CDR Client service exited before termination (PID: {ProcessId})", process.Id);
            }

            await process.WaitForExitAsync(timeout.Token);
            _logger.LogInformation("CDR Client service process exited (PID: {ProcessId}, exit code: {ExitCode})",
                process.Id, process.ExitCode);
        }
        catch (OperationCanceledException ex)
        {
            _logger.LogError(ex, "CDR Client service did not exit within {TimeoutSeconds} seconds; package resources may still be in use",
                ProcessShutdownTimeout.TotalSeconds);
            throw new TimeoutException("Timed out waiting for the CDR Client service process to exit.", ex);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error terminating CDR Client service process tree; package resources may still be in use");
            throw;
        }
        finally
        {
            CleanupServiceProcess();
        }
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        _logger.LogInformation("=== Watchdog service stop requested ===");
        lock (_lifecycleLock)
        {
            _isStopping = true;
        }

        // The monitoring loop owns the process and performs cleanup in its finally block.
        await base.StopAsync(cancellationToken);
        if (ExecuteTask != null)
        {
            try
            {
                await ExecuteTask.WaitAsync(cancellationToken);
            }
            catch (OperationCanceledException) when (ExecuteTask.IsCanceled && !cancellationToken.IsCancellationRequested)
            {
                // BackgroundService can cancel its scheduled task before ExecuteAsync starts.
                _logger.LogInformation("Watchdog monitoring was cancelled before it started");
            }
        }
        _logger.LogInformation("=== Watchdog service stopped successfully ===");
    }

    #endregion
}
