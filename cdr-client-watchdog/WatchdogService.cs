using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using System;
using System.Threading;
using System.Threading.Tasks;

namespace CdrClientWatchdog;

/// <summary>
/// Monitors the curaLINE Client service, coordinates shutdown, and applies restart failure protection.
/// </summary>
public class WatchdogService : BackgroundService
{
    private readonly ILogger<WatchdogService> _logger;
    private readonly IHostApplicationLifetime _hostLifetime;
    private readonly WatchdogSettings _settings;
    private readonly ServiceLaunchConfiguration _launchConfiguration;
    private readonly IManagedServiceProcess _process;
    private readonly object _lifecycleLock = new();
    private int _consecutiveFailures;
    private DateTime _lastStartTime = DateTime.MinValue;
    private bool _isStopping;

    public WatchdogService(ILogger<WatchdogService> logger, IConfiguration configuration, IHostApplicationLifetime hostLifetime)
        : this(logger, hostLifetime, new WatchdogSettings(configuration, new ServicePathResolver(logger)))
    {
    }

    private WatchdogService(ILogger<WatchdogService> logger, IHostApplicationLifetime hostLifetime, WatchdogSettings settings)
        : this(logger, hostLifetime, settings, new ManagedServiceProcess(logger, settings),
            new ServiceLaunchConfiguration(settings, logger))
    {
    }

    internal WatchdogService(ILogger<WatchdogService> logger, IHostApplicationLifetime hostLifetime,
        WatchdogSettings settings, IManagedServiceProcess process, ServiceLaunchConfiguration launchConfiguration)
    {
        _logger = logger;
        _hostLifetime = hostLifetime;
        _settings = settings;
        _process = process;
        _launchConfiguration = launchConfiguration;
        _logger.LogInformation("Watchdog initialized in {Mode} mode", _settings.ServiceExecutionMode);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var monitoringCancellation = CancellationTokenSource.CreateLinkedTokenSource(
            stoppingToken, _hostLifetime.ApplicationStopping);
        var monitoringToken = monitoringCancellation.Token;
        LogServiceStartup();

        try
        {
            if (!await _launchConfiguration.CheckJavaVersionAsync(monitoringToken))
            {
                _hostLifetime.StopApplication();
                return;
            }

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
            _logger.LogInformation("curaLINE Client watchdog service stopping monitoring loop");
            await _process.StopAsync();
        }
    }

    private void LogServiceStartup()
    {
        if (_settings.IsJarMode)
        {
            _logger.LogInformation(
                "curaLINE Client watchdog service started in JAR mode. Monitoring: Java={JavaPath}, JAR={JarPath}, Arguments={Arguments}, Health check: {Interval}s, Restart delay: {Delay}s, Max failures: {MaxFailures}",
                _settings.JavaExecutablePath, _settings.ServiceJarPath, _settings.JavaArguments,
                _settings.HealthCheckInterval.TotalSeconds, _settings.RestartDelay.TotalSeconds, _settings.MaxConsecutiveFailures);
        }
        else
        {
            _logger.LogInformation(
                "curaLINE Client watchdog service started in Executable mode. Monitoring: {ExecutablePath}, Health check: {Interval}s, Restart delay: {Delay}s, Max failures: {MaxFailures}",
                _settings.ServiceExecutablePath, _settings.HealthCheckInterval.TotalSeconds,
                _settings.RestartDelay.TotalSeconds, _settings.MaxConsecutiveFailures);
        }
    }

    private async Task WaitForNextHealthCheck(CancellationToken cancellationToken)
    {
        try
        {
            await Task.Delay(_settings.HealthCheckInterval, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error during health check delay, using fallback delay");
            await Task.Delay(TimeSpan.FromSeconds(10), cancellationToken);
        }
    }

    private async Task MonitorServiceHealth(CancellationToken cancellationToken)
    {
        if (_process.HasProcess && !_process.HasExited)
        {
            if (_consecutiveFailures > 0)
            {
                _logger.LogInformation("curaLINE Client service is running normally. Resetting failure counter.");
                _consecutiveFailures = 0;
            }

            if (DateTime.Now - _lastStartTime > TimeSpan.FromMinutes(5))
            {
                await Task.Run(() =>
                    _logger.LogDebug("curaLINE Client service is running (PID: {ProcessId}, Started: {StartTime}, Uptime: {Uptime})",
                        _process.Id, _lastStartTime, DateTime.Now - _lastStartTime));
            }
            return;
        }

        if (_process.HasProcess && _process.HasExited)
        {
            AnalyzeServiceExit();
        }

        if (!cancellationToken.IsCancellationRequested && ShouldRestartService())
        {
            if (_consecutiveFailures > 0)
            {
                _logger.LogInformation("Waiting {Delay} seconds before restart attempt (consecutive failures: {Count})",
                    _settings.RestartDelay.TotalSeconds, _consecutiveFailures);
                await Task.Delay(_settings.RestartDelay, cancellationToken);
            }

            StartManagedService(cancellationToken);
        }
    }

    private void AnalyzeServiceExit()
    {
        var exitCode = _process.ExitCode;
        var runDuration = DateTime.Now - _lastStartTime;
        if (exitCode == 0)
        {
            _logger.LogInformation(
                "curaLINE Client service exited cleanly (exit code 0) after running for {Duration}. Stopping watchdog service as well.",
                runDuration);
            _consecutiveFailures = 0;
            _logger.LogInformation("Initiating watchdog service shutdown due to clean curaLINE Client service exit.");
            _hostLifetime.StopApplication();
        }
        else
        {
            _consecutiveFailures++;
            _logger.LogWarning(
                "curaLINE Client service exited with error code {ExitCode} after running for {Duration}. Consecutive failures: {Count}/{Max}",
                exitCode, runDuration, _consecutiveFailures, _settings.MaxConsecutiveFailures);
            if (_consecutiveFailures >= _settings.MaxConsecutiveFailures)
            {
                HandleMaxFailuresReached();
            }
        }

        _process.Clear();
    }

    private void HandleMaxFailuresReached()
    {
        _logger.LogError(
            "Maximum consecutive failures reached ({Count}). Stopping watchdog to prevent endless restart loop.",
            _settings.MaxConsecutiveFailures);
        _logger.LogInformation("Stopping watchdog Windows Service due to maximum consecutive failures.");
        _hostLifetime.StopApplication();
    }

    private bool ShouldRestartService()
    {
        lock (_lifecycleLock)
        {
            return !_isStopping &&
                   !_hostLifetime.ApplicationStopping.IsCancellationRequested &&
                   _consecutiveFailures < _settings.MaxConsecutiveFailures &&
                   (_consecutiveFailures > 0 || !_process.HasProcess);
        }
    }

    private void StartManagedService(CancellationToken cancellationToken)
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

                if (!_launchConfiguration.Validate())
                {
                    _hostLifetime.StopApplication();
                    return;
                }

                _process.Start(_launchConfiguration.CreateProcessStartInfo());
                _lastStartTime = DateTime.Now;
                _logger.LogInformation("curaLINE Client service started successfully (PID: {ProcessId})", _process.Id);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error starting curaLINE Client service");
            _consecutiveFailures++;
            if (_consecutiveFailures >= _settings.MaxConsecutiveFailures)
            {
                HandleMaxFailuresReached();
            }
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
}
