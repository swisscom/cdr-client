using System.Diagnostics;
using CdrClientWatchdog;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

public sealed class ManagedServiceProcessTests
{
    [Fact]
    public async Task StopWithoutAProcessIsSafeToRepeat()
    {
        var process = new ManagedServiceProcess(NullLogger<WatchdogService>.Instance);

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
        var process = new ManagedServiceProcess(NullLogger<WatchdogService>.Instance);

        Assert.Throws<InvalidOperationException>(() => process.Id);
        Assert.Throws<InvalidOperationException>(() => process.ExitCode);
        Assert.Throws<InvalidOperationException>(() => process.HasExited);
    }

    [Fact]
    public void ShutdownWaitRemainsTenSeconds()
    {
        Assert.Equal(TimeSpan.FromSeconds(10), ManagedServiceProcess.ShutdownTimeout);
    }
}
