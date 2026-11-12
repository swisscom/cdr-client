using Microsoft.Extensions.Logging;
using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;

#nullable enable

namespace CdrClientWatchdog;

internal interface IManagedServiceProcess
{
    bool HasProcess { get; }
    bool HasExited { get; }
    int Id { get; }
    int ExitCode { get; }
    void Start(ProcessStartInfo startInfo);
    void Clear();
    Task StopAsync();
}

internal sealed class ManagedServiceProcess : IManagedServiceProcess
{
    internal static readonly TimeSpan ShutdownTimeout = TimeSpan.FromSeconds(5);

    internal static TimeSpan GetMaximumStopDuration(WatchdogSettings settings) => settings.GracefulShutdownTimeout + ShutdownTimeout;

    private readonly ILogger _logger;
    private readonly IShutdownSignal _shutdownSignal;
    private readonly TimeSpan _gracefulShutdownTimeout;
    private Process? _process;

    public ManagedServiceProcess(ILogger<WatchdogService> logger, WatchdogSettings settings)
        : this(logger, null, new ConsoleShutdownSignal(logger), settings.GracefulShutdownTimeout)
    {
    }

    internal ManagedServiceProcess(ILogger logger, Process? process)
        : this(logger, process, DisabledShutdownSignal.Instance, TimeSpan.Zero)
    {
    }

    internal ManagedServiceProcess(ILogger logger, Process? process, IShutdownSignal shutdownSignal, TimeSpan gracefulShutdownTimeout)
    {
        _logger = logger;
        _process = process;
        _shutdownSignal = shutdownSignal;
        _gracefulShutdownTimeout = gracefulShutdownTimeout;
    }

    private Process CurrentProcess => _process ?? throw new InvalidOperationException("No curaLINE Client service process is being monitored.");
    public bool HasProcess => _process != null;
    public bool HasExited => CurrentProcess.HasExited;
    public int Id => CurrentProcess.Id;
    public int ExitCode => CurrentProcess.ExitCode;

    public void Start(ProcessStartInfo startInfo)
    {
        _shutdownSignal.PrepareChildProcessStart(startInfo);
        _process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("Process.Start returned no curaLINE Client service process.");
    }

    public void Clear()
    {
        _process?.Dispose();
        _process = null;
    }

    public async Task StopAsync()
    {
        var process = _process;
        if (process == null)
        {
            _logger.LogInformation("No curaLINE Client service process to stop");
            return;
        }

        try
        {
            if (process.HasExited)
            {
                _logger.LogInformation("curaLINE Client service process already exited (PID was: {ProcessId})", process.Id);
                return;
            }

            if (await TryStopGracefullyAsync(process))
            {
                return;
            }

            _logger.LogInformation("Terminating curaLINE Client service process tree (PID: {ProcessId})", process.Id);
            using var timeout = new CancellationTokenSource(ShutdownTimeout);
            try
            {
                process.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException) when (process.HasExited)
            {
                _logger.LogInformation("curaLINE Client service exited before termination (PID: {ProcessId})", process.Id);
            }

            await process.WaitForExitAsync(timeout.Token);
            _logger.LogInformation("curaLINE Client service process exited (PID: {ProcessId}, exit code: {ExitCode})",
                process.Id, process.ExitCode);
        }
        catch (OperationCanceledException ex)
        {
            _logger.LogError(ex, "curaLINE Client service did not exit within {TimeoutSeconds} seconds; package resources may still be in use",
                ShutdownTimeout.TotalSeconds);
            throw new TimeoutException("Timed out waiting for the curaLINE Client service process to exit.", ex);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error terminating curaLINE Client service process tree; package resources may still be in use");
            throw;
        }
        finally
        {
            Clear();
        }
    }

    // Forced termination (TerminateProcess) skips JVM shutdown hooks, so in-flight uploads would be cut off.
    // Ctrl+C is the closest Windows equivalent of SIGTERM: the JVM runs its shutdown hooks before exiting.
    private async Task<bool> TryStopGracefullyAsync(Process process)
    {
        if (_gracefulShutdownTimeout <= TimeSpan.Zero)
        {
            return false;
        }

        _logger.LogInformation("Requesting graceful shutdown of curaLINE Client service (PID: {ProcessId})", process.Id);
        IDisposable? signal;
        try
        {
            signal = _shutdownSignal.Send(process.Id);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Sending the shutdown signal to the curaLINE Client service failed");
            signal = null;
        }

        if (signal == null)
        {
            return false;
        }

        using (signal)
        {
            using var timeout = new CancellationTokenSource(_gracefulShutdownTimeout);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
                _logger.LogInformation("curaLINE Client service shut down gracefully (PID: {ProcessId}, exit code: {ExitCode})",
                    process.Id, process.ExitCode);
                return true;
            }
            catch (OperationCanceledException)
            {
                _logger.LogWarning("curaLINE Client service did not shut down within {TimeoutSeconds} seconds of the shutdown signal",
                    _gracefulShutdownTimeout.TotalSeconds);
                return false;
            }
        }
    }
}
