using CdrClientWatchdog;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

public sealed class ServicePathResolverTests
{
    [Fact]
    public void AbsolutePathIsUnchanged()
    {
        using var directory = new TestDirectory();
        var path = Path.Combine(directory.Root, "not-installed.exe");
        var resolver = CreateResolver(directory.Root);

        Assert.Equal(path, resolver.ResolvePath(path));
    }

    [Fact]
    public void RelativePathUsesParentOfWatchdogDirectory()
    {
        using var directory = new TestDirectory();
        var executable = directory.CreateFile("service.exe");
        var resolver = CreateResolver(Path.Combine(directory.Root, "watchdog"));

        Assert.Equal(executable, resolver.ResolvePath("service.exe"));
    }

    [Theory]
    [InlineData("bin")]
    [InlineData("app")]
    public void RelativePathFallsBackToAncestorBin(string layout)
    {
        using var directory = new TestDirectory();
        var relativePath = layout == "bin"
            ? Path.Combine("bin", "service.exe")
            : Path.Combine("app", "bin", "service.exe");
        var executable = directory.CreateFile(relativePath);
        var resolver = CreateResolver(Path.Combine(directory.Root, "tools", "watchdog"));

        Assert.Equal(executable, resolver.ResolvePath("service.exe"));
    }

    [Fact]
    public void ExistingPrimaryCandidateTakesPrecedenceOverAncestorBin()
    {
        using var directory = new TestDirectory();
        var primary = directory.CreateFile(Path.Combine("tools", "service.exe"));
        directory.CreateFile(Path.Combine("bin", "service.exe"));
        var resolver = CreateResolver(Path.Combine(directory.Root, "tools", "watchdog"));

        Assert.Equal(primary, resolver.ResolvePath("service.exe"));
    }

    [Fact]
    public void MissingRelativePathReturnsPrimaryCandidateForDiagnostics()
    {
        using var directory = new TestDirectory();
        var resolver = CreateResolver(Path.Combine(directory.Root, "watchdog"));

        Assert.Equal(Path.Combine(directory.Root, "missing.exe"), resolver.ResolvePath("missing.exe"));
    }

    [Fact]
    public void EnvironmentVariablesAreExpanded()
    {
        using var directory = new TestDirectory();
        var variable = $"WATCHDOG_TEST_{Guid.NewGuid():N}";
        Environment.SetEnvironmentVariable(variable, directory.Root);
        try
        {
            var resolver = CreateResolver(directory.Root);
            Assert.Equal(Path.Combine(directory.Root, "service.exe"),
                resolver.ResolvePath($"%{variable}%{Path.DirectorySeparatorChar}service.exe"));
        }
        finally
        {
            Environment.SetEnvironmentVariable(variable, null);
        }
    }

    [Theory]
    [InlineData("x64", @"app\bin\watchdog")]
    [InlineData("x64", @"bin\watchdog")]
    [InlineData("x86", @"bin\watchdog")]
    public void MsixLayoutsResolveToPackageBin(string architecture, string layout)
    {
        var packageRoot = $@"C:\Program Files\WindowsApps\Swisscom.Client_1.0.0.0_{architecture}__publisher";
        var resolver = CreateResolver($@"{packageRoot}\{layout}");

        Assert.Equal(Path.Combine(packageRoot, "bin"),
            resolver.DetermineBaseDirectory($@"{packageRoot}\{layout}"));
    }

    private static ServicePathResolver CreateResolver(string watchdogDirectory) =>
        new(NullLogger<WatchdogService>.Instance, watchdogDirectory);
}
