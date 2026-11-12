using System.Collections.Concurrent;
using System.Diagnostics;
using CdrClientWatchdog;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

public sealed class ManagedServiceProcessTests
{
    [Fact]
    public async Task StopWithoutAProcessIsSafeToRepeat()
    {
        var process = new ManagedServiceProcess(NullLogger<WatchdogService>.Instance, (Process?)null);

        await process.StopAsync();
        await process.StopAsync();

        Assert.False(process.HasProcess);
    }

    [Fact]
    public async Task StopDisposesAnAlreadyExitedProcess()
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = OperatingSystem.IsWindows() ? Path.Combine(Environment.SystemDirectory, "cmd.exe") : "/bin/sh",
            UseShellExecute = false,
            CreateNoWindow = true
        };
        startInfo.ArgumentList.Add(OperatingSystem.IsWindows() ? "/c" : "-c");
        startInfo.ArgumentList.Add("exit 0");
        using var exitedProcess = Process.Start(startInfo)!;
        await exitedProcess.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
        var process = new ManagedServiceProcess(NullLogger<WatchdogService>.Instance, exitedProcess);

        Assert.True(process.HasProcess);
        Assert.True(process.HasExited);
        Assert.Equal(0, process.ExitCode);

        await process.StopAsync();

        Assert.False(process.HasProcess);
        await process.StopAsync();
    }

    [Fact]
    public void ProcessDetailsRequireATrackedProcess()
    {
        var process = new ManagedServiceProcess(NullLogger<WatchdogService>.Instance, (Process?)null);

        Assert.Throws<InvalidOperationException>(() => process.Id);
        Assert.Throws<InvalidOperationException>(() => process.ExitCode);
        Assert.Throws<InvalidOperationException>(() => process.HasExited);
    }

    [Fact]
    public void ShutdownWaitRemainsFiveSeconds()
    {
        Assert.Equal(TimeSpan.FromSeconds(5), ManagedServiceProcess.ShutdownTimeout);
    }

    [Fact]
    public async Task AcceptedShutdownSignalAvoidsForcedTermination()
    {
        var logger = new CapturingLogger();
        using var running = StartLongRunningProcess();
        var processId = running.Id;
        // Simulates the service exiting on its own after receiving the signal.
        var signal = new FakeShutdownSignal(_ => running.Kill());
        var process = new ManagedServiceProcess(logger, running, signal, TimeSpan.FromSeconds(10));

        await process.StopAsync();

        Assert.Equal(new[] { processId }, signal.SentTo);
        Assert.Equal(1, signal.DisposedScopes);
        Assert.False(process.HasProcess);
        Assert.Contains(logger.Messages, m => m.StartsWith("curaLINE Client service shut down gracefully"));
        Assert.DoesNotContain(logger.Messages, m => m.StartsWith("Terminating curaLINE Client service process tree"));
    }

    [Fact]
    public async Task UndeliverableShutdownSignalFallsBackToTermination()
    {
        var logger = new CapturingLogger();
        using var running = StartLongRunningProcess();
        var signal = new FakeShutdownSignal(_ => { }, delivered: false);
        var process = new ManagedServiceProcess(logger, running, signal, TimeSpan.FromSeconds(10));

        await process.StopAsync();

        Assert.Single(signal.SentTo);
        Assert.Contains(logger.Messages, m => m.StartsWith("Terminating curaLINE Client service process tree"));
        Assert.Contains(logger.Messages, m => m.StartsWith("curaLINE Client service process exited"));
        Assert.DoesNotContain(logger.Messages, m => m.StartsWith("curaLINE Client service shut down gracefully"));
    }

    [Fact]
    public async Task FailingShutdownSignalFallsBackToTermination()
    {
        var logger = new CapturingLogger();
        using var running = StartLongRunningProcess();
        var signal = new FakeShutdownSignal(_ => throw new InvalidOperationException("BANG!"));
        var process = new ManagedServiceProcess(logger, running, signal, TimeSpan.FromSeconds(10));

        await process.StopAsync();

        Assert.Contains(logger.Messages, m => m.StartsWith("Sending the shutdown signal to the curaLINE Client service failed"));
        Assert.Contains(logger.Messages, m => m.StartsWith("Terminating curaLINE Client service process tree"));
        Assert.Contains(logger.Messages, m => m.StartsWith("curaLINE Client service process exited"));
    }

    [Fact]
    public async Task IgnoredShutdownSignalFallsBackToTerminationAfterTimeout()
    {
        var logger = new CapturingLogger();
        using var running = StartLongRunningProcess();
        var signal = new FakeShutdownSignal(_ => { });
        var process = new ManagedServiceProcess(logger, running, signal, TimeSpan.FromMilliseconds(200));

        await process.StopAsync();

        Assert.Equal(1, signal.DisposedScopes);
        Assert.Contains(logger.Messages, m => m.StartsWith("curaLINE Client service did not shut down within"));
        Assert.Contains(logger.Messages, m => m.StartsWith("Terminating curaLINE Client service process tree"));
        Assert.Contains(logger.Messages, m => m.StartsWith("curaLINE Client service process exited"));
    }

    [Fact]
    public async Task ZeroGracefulShutdownTimeoutSkipsTheSignal()
    {
        var logger = new CapturingLogger();
        using var running = StartLongRunningProcess();
        var signal = new FakeShutdownSignal(_ => { });
        var process = new ManagedServiceProcess(logger, running, signal, TimeSpan.Zero);

        await process.StopAsync();

        Assert.Empty(signal.SentTo);
        Assert.Contains(logger.Messages, m => m.StartsWith("Terminating curaLINE Client service process tree"));
        Assert.Contains(logger.Messages, m => m.StartsWith("curaLINE Client service process exited"));
    }

    [Fact]
    public async Task StartPreparesTheShutdownSignal()
    {
        var signal = new FakeShutdownSignal(_ => { });
        var process = new ManagedServiceProcess(NullLogger<WatchdogService>.Instance, null, signal, TimeSpan.Zero);

        process.Start(LongRunningStartInfo());
        try
        {
            Assert.Equal(1, signal.Preparations);
        }
        finally
        {
            await process.StopAsync();
        }
    }

    [Fact]
    public async Task ConsoleShutdownSignalStopsAProcessGracefully()
    {
        var logger = new CapturingLogger();
        var signal = new ConsoleShutdownSignal(logger);
        var process = new ManagedServiceProcess(logger, null, signal, TimeSpan.FromSeconds(10));

        process.Start(LongRunningStartInfo(direct: true));
        await process.StopAsync();

        Assert.Contains(logger.Messages, m => m.StartsWith("curaLINE Client service shut down gracefully"));
        Assert.DoesNotContain(logger.Messages, m => m.StartsWith("Terminating curaLINE Client service process tree"));
    }

    [Fact]
    public void ConsoleShutdownSignalLetsTheProcessInheritTheWatchdogConsoleOnWindows()
    {
        var startInfo = new ProcessStartInfo { CreateNoWindow = true };

        new ConsoleShutdownSignal(NullLogger<WatchdogService>.Instance).PrepareChildProcessStart(startInfo);

        // CREATE_NO_WINDOW would leave the process without a console that Ctrl+C could be sent to.
        Assert.Equal(!OperatingSystem.IsWindows(), startInfo.CreateNoWindow);
    }

    [Fact]
    public void MaximumStopDurationCoversGracefulAndForcedStop()
    {
        var settings = CreateSettings(new Dictionary<string, string?> { ["GracefulShutdownTimeoutSeconds"] = "20" });

        Assert.Equal(TimeSpan.FromSeconds(20) + ManagedServiceProcess.ShutdownTimeout,
            ManagedServiceProcess.GetMaximumStopDuration(settings));
    }

    [Fact]
    public void GracefulShutdownTimeoutHasADefault()
    {
        var settings = CreateSettings(new Dictionary<string, string?>());

        Assert.Equal(TimeSpan.FromSeconds(20), settings.GracefulShutdownTimeout);
    }

    [Fact]
    public void NegativeGracefulShutdownTimeoutDisablesGracefulShutdown()
    {
        var settings = CreateSettings(new Dictionary<string, string?> { ["GracefulShutdownTimeoutSeconds"] = "-1" });

        Assert.Equal(TimeSpan.Zero, settings.GracefulShutdownTimeout);
        Assert.Equal(ManagedServiceProcess.ShutdownTimeout, ManagedServiceProcess.GetMaximumStopDuration(settings));
    }

    private static WatchdogSettings CreateSettings(Dictionary<string, string?> values)
    {
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(values).Build();
        return new WatchdogSettings(configuration, new ServicePathResolver(NullLogger<WatchdogService>.Instance));
    }

    private static Process StartLongRunningProcess() => Process.Start(LongRunningStartInfo())!;

    // direct: start the long-running program itself rather than through a shell, so that it receives the signal.
    private static ProcessStartInfo LongRunningStartInfo(bool direct = false)
    {
        var startInfo = new ProcessStartInfo { UseShellExecute = false, CreateNoWindow = true };
        if (OperatingSystem.IsWindows())
        {
            startInfo.FileName = Path.Combine(Environment.SystemDirectory, direct ? "PING.EXE" : "cmd.exe");
            var arguments = direct ? new[] { "-n", "120", "127.0.0.1" } : new[] { "/c", "ping -n 120 127.0.0.1 >nul" };
            foreach (var argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }
        }
        else
        {
            startInfo.FileName = direct ? "sleep" : "/bin/sh";
            var arguments = direct ? new[] { "120" } : new[] { "-c", "sleep 120" };
            foreach (var argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }
        }
        return startInfo;
    }

    private sealed class FakeShutdownSignal(Action<int> onSend, bool delivered = true) : IShutdownSignal
    {
        private int _disposedScopes;
        public int Preparations { get; private set; }
        public List<int> SentTo { get; } = new();
        public int DisposedScopes => _disposedScopes;

        public void PrepareChildProcessStart(ProcessStartInfo startInfo) => Preparations++;

        public IDisposable? Send(int processId)
        {
            SentTo.Add(processId);
            onSend(processId);
            return delivered ? new Scope(() => Interlocked.Increment(ref _disposedScopes)) : null;
        }

        private sealed class Scope(Action dispose) : IDisposable
        {
            public void Dispose() => dispose();
        }
    }

    private sealed class CapturingLogger : ILogger<WatchdogService>
    {
        public ConcurrentQueue<string> Messages { get; } = new();
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => true;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter) => Messages.Enqueue(formatter(state, exception));
    }
}
