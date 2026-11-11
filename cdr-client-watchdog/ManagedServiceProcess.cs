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
    internal static readonly TimeSpan ShutdownTimeout = TimeSpan.FromSeconds(10);
    private readonly ILogger _logger;
    private Process? _process;

    public ManagedServiceProcess(ILogger<WatchdogService> logger) : this(logger, null)
    {
    }

    internal ManagedServiceProcess(ILogger logger, Process? process)
    {
        _logger = logger;
        _process = process;
    }

    private Process CurrentProcess => _process ?? throw new InvalidOperationException("No curaLINE Client service process is being monitored.");
    public bool HasProcess => _process != null;
    public bool HasExited => CurrentProcess.HasExited;
    public int Id => CurrentProcess.Id;
    public int ExitCode => CurrentProcess.ExitCode;

    public void Start(ProcessStartInfo startInfo)
    {
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
}
