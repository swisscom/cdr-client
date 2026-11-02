using System.Collections.Concurrent;
using System.Diagnostics;
using System.Reflection;
using CdrClientWatchdog;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Xunit;

public sealed class WatchdogServiceTests
{
    [Theory]
    [InlineData(29)]
    [InlineData(31)]
    [InlineData(1)]
    public async Task BackendExitRestartsClientWhileWatchdogIsRunning(int exitCode)
    {
        using var lifetime = new TestLifetime();
        var logger = new TestLogger();
        var executable = OperatingSystem.IsWindows()
            ? Path.Combine(Environment.SystemDirectory, "cmd.exe")
            : "/bin/sh";
        using var watchdog = CreateWatchdog(lifetime, logger, executable, restartDelay: 1);
        using var process = StartShell($"exit {exitCode}");
        await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
        SetManagedProcess(watchdog, process);

        try
        {
            await watchdog.StartAsync(CancellationToken.None);
            await WaitUntil(() => logger.Messages.Any(m => m.StartsWith("CDR Client service started successfully")));

            Assert.False(lifetime.ApplicationStopping.IsCancellationRequested);
            Assert.Contains(logger.Messages, m => m.StartsWith($"CDR Client service exited with error code {exitCode}"));
            Assert.Contains(logger.Messages, m => m.StartsWith("Waiting 1 seconds before restart attempt"));
            Assert.Single(logger.Messages, m => m.StartsWith("CDR Client service started successfully"));
        }
        finally
        {
            await watchdog.StopAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(15));
        }
    }

    [Fact]
    public async Task CleanClientExitStopsWatchdogWithoutStartingAnotherClient()
    {
        using var lifetime = new TestLifetime();
        var logger = new TestLogger();
        using var watchdog = CreateWatchdog(lifetime, logger);
        using var process = StartShell("exit 0");
        await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
        SetManagedProcess(watchdog, process);

        await watchdog.StartAsync(CancellationToken.None);
        await watchdog.ExecuteTask!.WaitAsync(TimeSpan.FromSeconds(10));
        await watchdog.StopAsync(CancellationToken.None);

        Assert.True(lifetime.ApplicationStopping.IsCancellationRequested);
        Assert.DoesNotContain(logger.Messages, m => m.StartsWith("Starting CDR Client service"));
        Assert.DoesNotContain(logger.Messages, m => m.Contains("not found"));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task StopDuringRestartDelayCancelsRestartAndCleansUpOnce(bool applicationStopping)
    {
        using var lifetime = new TestLifetime();
        var logger = new TestLogger();
        using var watchdog = CreateWatchdog(lifetime, logger, restartDelay: 30);
        using var process = StartShell("exit 1");
        await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
        SetManagedProcess(watchdog, process);
        await watchdog.StartAsync(CancellationToken.None);
        await WaitUntil(() => logger.Messages.Any(m => m.StartsWith("Waiting 30 seconds")));

        if (applicationStopping)
        {
            lifetime.StopApplication();
            await watchdog.ExecuteTask!.WaitAsync(TimeSpan.FromSeconds(5));
        }
        await watchdog.StopAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5));
        await watchdog.StopAsync(CancellationToken.None);

        Assert.DoesNotContain(logger.Messages, m => m.StartsWith("Starting CDR Client service"));
        Assert.Equal(1, logger.Messages.Count(m => m == "CDRClientWatchdog Service stopping monitoring loop"));
    }

    [Fact]
    public async Task ApplicationStoppingPreventsInitialClientLaunch()
    {
        using var lifetime = new TestLifetime();
        var logger = new TestLogger();
        using var watchdog = CreateWatchdog(lifetime, logger);
        lifetime.StopApplication();

        await watchdog.StartAsync(CancellationToken.None);
        await watchdog.ExecuteTask!.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.DoesNotContain(logger.Messages, m => m.StartsWith("Starting CDR Client service"));
    }

    [Fact]
    public async Task StopBeforeStartPreventsClientLaunch()
    {
        using var lifetime = new TestLifetime();
        var logger = new TestLogger();
        using var watchdog = CreateWatchdog(lifetime, logger);

        await watchdog.StopAsync(CancellationToken.None);
        await watchdog.StartAsync(CancellationToken.None);
        await watchdog.StopAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5));

        Assert.DoesNotContain(logger.Messages, m => m.StartsWith("Starting CDR Client service"));
    }

    [Fact]
    public async Task RepeatedProcessStartFailuresStopAtConfiguredLimit()
    {
        var invalidExecutable = Path.GetTempFileName();
        try
        {
            using var lifetime = new TestLifetime();
            var logger = new TestLogger();
            using var watchdog = CreateWatchdog(lifetime, logger, executable: invalidExecutable);

            await watchdog.StartAsync(CancellationToken.None);
            await watchdog.ExecuteTask!.WaitAsync(TimeSpan.FromSeconds(10));

            Assert.True(lifetime.ApplicationStopping.IsCancellationRequested);
            Assert.Equal(3, logger.Messages.Count(m => m == "Error starting CDR Client service"));
        }
        finally
        {
            File.Delete(invalidExecutable);
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task StopTerminatesManagedProcessAndItsChildWithoutRestarting(bool applicationStopping)
    {
        var pidFile = Path.Combine(Path.GetTempPath(), $"watchdog-child-{Guid.NewGuid():N}.pid");
        using var lifetime = new TestLifetime();
        var logger = new TestLogger();
        using var watchdog = CreateWatchdog(lifetime, logger, healthInterval: 3600);
        using var parent = StartProcessTree(pidFile);
        Process? child = null;
        try
        {
            await WaitUntil(() => File.Exists(pidFile) &&
                                  int.TryParse(File.ReadAllText(pidFile).Trim(), out _));
            child = Process.GetProcessById(int.Parse(File.ReadAllText(pidFile).Trim()));
            SetManagedProcess(watchdog, parent);
            await watchdog.StartAsync(CancellationToken.None);
            await WaitUntil(() => logger.Messages.Any(m => m.Contains("Service started in Executable mode")));

            if (applicationStopping)
            {
                lifetime.StopApplication();
                await watchdog.ExecuteTask!.WaitAsync(TimeSpan.FromSeconds(15));
            }
            await watchdog.StopAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(15));

            await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
            Assert.True(child.HasExited);
            Assert.DoesNotContain(logger.Messages, m => m.StartsWith("Starting CDR Client service"));
            Assert.Equal(1, logger.Messages.Count(m => m.StartsWith("Terminating CDR Client service process tree")));
            Assert.Contains("=== Watchdog service stopped successfully ===", logger.Messages);
        }
        finally
        {
            await TerminateIfRunning(parent);
            if (child != null)
            {
                await TerminateIfRunning(child);
                child.Dispose();
            }
            File.Delete(pidFile);
        }
    }

    private static WatchdogService CreateWatchdog(TestLifetime lifetime, TestLogger logger,
        string? executable = null, int restartDelay = 0, int healthInterval = 1)
    {
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ServiceExecutablePath"] = executable ?? Environment.ProcessPath!,
            ["RestartDelaySeconds"] = restartDelay.ToString(),
            ["HealthCheckIntervalSeconds"] = healthInterval.ToString(),
            ["MaxConsecutiveFailures"] = "3"
        }).Build();
        return new WatchdogService(logger, configuration, lifetime);
    }

    private static void SetManagedProcess(WatchdogService watchdog, Process process)
    {
        typeof(WatchdogService).GetField("_watchedProcess", BindingFlags.Instance | BindingFlags.NonPublic)!
            .SetValue(watchdog, process);
    }

    private static Process StartShell(string command)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = OperatingSystem.IsWindows() ? Path.Combine(Environment.SystemDirectory, "cmd.exe") : "/bin/sh",
            UseShellExecute = false,
            CreateNoWindow = true
        };
        startInfo.ArgumentList.Add(OperatingSystem.IsWindows() ? "/c" : "-c");
        startInfo.ArgumentList.Add(command);
        return Process.Start(startInfo)!;
    }

    private static Process StartProcessTree(string pidFile)
    {
        if (!OperatingSystem.IsWindows())
        {
            return StartShell($"sleep 120 & echo $! > '{pidFile}'; wait");
        }

        var startInfo = new ProcessStartInfo
        {
            FileName = Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe"),
            UseShellExecute = false,
            CreateNoWindow = true
        };
        startInfo.ArgumentList.Add("-NoProfile");
        startInfo.ArgumentList.Add("-NonInteractive");
        startInfo.ArgumentList.Add("-Command");
        startInfo.ArgumentList.Add(
            "$child = Start-Process powershell.exe -ArgumentList '-NoProfile -NonInteractive -Command \"Start-Sleep -Seconds 120\"' -PassThru; " +
            $"[IO.File]::WriteAllText('{pidFile.Replace("'", "''")}', [string]$child.Id); $child.WaitForExit()");
        return Process.Start(startInfo)!;
    }

    private static async Task WaitUntil(Func<bool> condition)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        while (!condition())
        {
            await Task.Delay(20, timeout.Token);
        }
    }

    private static async Task TerminateIfRunning(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
            }
        }
        catch (InvalidOperationException)
        {
            // Watchdog cleanup may already have disposed its managed process.
        }
    }

    private sealed class TestLifetime : IHostApplicationLifetime, IDisposable
    {
        private readonly CancellationTokenSource _stopping = new();
        public CancellationToken ApplicationStarted => CancellationToken.None;
        public CancellationToken ApplicationStopping => _stopping.Token;
        public CancellationToken ApplicationStopped => CancellationToken.None;
        public void StopApplication() => _stopping.Cancel();
        public void Dispose() => _stopping.Dispose();
    }

    private sealed class TestLogger : ILogger<WatchdogService>
    {
        public ConcurrentQueue<string> Messages { get; } = new();
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => true;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter) => Messages.Enqueue(formatter(state, exception));
    }
}
