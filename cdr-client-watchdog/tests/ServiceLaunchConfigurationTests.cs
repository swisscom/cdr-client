using CdrClientWatchdog;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

public sealed class ServiceLaunchConfigurationTests
{
    [Fact]
    public void SettingsPreserveConfigurationDefaults()
    {
        using var directory = new TestDirectory();
        var settings = CreateSettings(directory, new());

        Assert.Equal("Executable", settings.ServiceExecutionMode);
        Assert.False(settings.IsJarMode);
        Assert.Equal(Path.Combine(directory.Root, "cdr-client-service.exe"), settings.ServiceExecutablePath);
        Assert.Equal(Path.GetFullPath(Path.Combine(directory.Root, "../lib/cdr-client-service.jar")), settings.ServiceJarPath);
        Assert.True(Path.IsPathRooted(settings.JavaExecutablePath));
        Assert.Equal("java", Path.GetFileName(settings.JavaExecutablePath));
        Assert.Equal("", settings.JavaArguments);
        Assert.Equal(TimeSpan.FromSeconds(2), settings.RestartDelay);
        Assert.Equal(TimeSpan.FromSeconds(30), settings.HealthCheckInterval);
        Assert.Equal(5, settings.MaxConsecutiveFailures);
    }

    [Theory]
    [InlineData("Executable")]
    [InlineData("unknown")]
    public void ExecutableLaunchUsesItsDirectoryAndNoShell(string mode)
    {
        using var directory = new TestDirectory();
        var executable = directory.CreateFile(Path.Combine("app with spaces", "service.exe"));
        var settings = CreateSettings(directory, new()
        {
            ["ServiceExecutionMode"] = mode,
            ["ServiceExecutablePath"] = executable
        });
        var launch = new ServiceLaunchConfiguration(settings, NullLogger<WatchdogService>.Instance);

        Assert.True(launch.Validate());
        var startInfo = launch.CreateProcessStartInfo();
        Assert.Equal(executable, startInfo.FileName);
        Assert.Equal(Path.GetDirectoryName(executable), startInfo.WorkingDirectory);
        Assert.Empty(startInfo.Arguments);
        Assert.Empty(startInfo.ArgumentList);
        Assert.False(startInfo.UseShellExecute);
        Assert.True(startInfo.CreateNoWindow);
    }

    [Fact]
    public void JarLaunchPreservesSpacedPathsAndPlacesJvmArgumentsBeforeJar()
    {
        using var directory = new TestDirectory();
        var java = directory.CreateFile(Path.Combine("java runtime", "java.exe"));
        var jar = directory.CreateFile(Path.Combine("service lib", "service.jar"));
        var settings = CreateSettings(directory, new()
        {
            ["ServiceExecutionMode"] = "jAr",
            ["JavaExecutablePath"] = java,
            ["ServiceJarPath"] = jar,
            ["JavaArguments"] = @"-Xmx512m -Dconfig=C:\Program Files\Swisscom\config.yaml -Dmode=test"
        });
        var launch = new ServiceLaunchConfiguration(settings, NullLogger<WatchdogService>.Instance);

        Assert.True(launch.Validate());
        var startInfo = launch.CreateProcessStartInfo();
        Assert.Equal(java, startInfo.FileName);
        Assert.Equal(Path.GetDirectoryName(jar), startInfo.WorkingDirectory);
        Assert.Equal(new[]
        {
            "-Xmx512m", @"-Dconfig=C:\Program Files\Swisscom\config.yaml", "-Dmode=test", "-jar", jar
        }, startInfo.ArgumentList);
        Assert.False(startInfo.UseShellExecute);
        Assert.True(startInfo.CreateNoWindow);
    }

    [Theory]
    [InlineData("Executable", false, false)]
    [InlineData("Jar", false, true)]
    [InlineData("Jar", true, false)]
    public void MissingRequiredFilesFailValidation(string mode, bool hasJava, bool hasJar)
    {
        using var directory = new TestDirectory();
        var java = Path.Combine(directory.Root, "java.exe");
        var jar = Path.Combine(directory.Root, "service.jar");
        if (hasJava) directory.CreateFile("java.exe");
        if (hasJar) directory.CreateFile("service.jar");
        var settings = CreateSettings(directory, new()
        {
            ["ServiceExecutionMode"] = mode,
            ["ServiceExecutablePath"] = Path.Combine(directory.Root, "missing.exe"),
            ["JavaExecutablePath"] = java,
            ["ServiceJarPath"] = jar
        });

        Assert.False(new ServiceLaunchConfiguration(settings, NullLogger<WatchdogService>.Instance).Validate());
    }

    [Theory]
    [InlineData("")]
    [InlineData("  \t ")]
    public void EmptyJavaArgumentsProduceNoArguments(string arguments)
    {
        Assert.Empty(ServiceLaunchConfiguration.ParseJavaArguments(arguments));
    }

    [Fact]
    public void JavaArgumentParsingPreservesExistingPropertyAndQuoteHandling()
    {
        var arguments = " -Xmx512m\t-Dflag=true -Dconfig=/opt/Client Config/app.yaml -ea \"literal\" ";

        Assert.Equal(new[]
        {
            "-Xmx512m", "-Dflag=true", "-Dconfig=/opt/Client Config/app.yaml", "-ea", "literal"
        }, ServiceLaunchConfiguration.ParseJavaArguments(arguments));
    }

    [Fact]
    public void PathPropertyConsumesFollowingNonOptionTokensAsBefore()
    {
        Assert.Equal(new[] { "-Dconfig=/opt/Client Config/app.yaml extra", "-ea" },
            ServiceLaunchConfiguration.ParseJavaArguments("-Dconfig=/opt/Client Config/app.yaml extra -ea"));
    }

    [Theory]
    [InlineData("java version \"25.0.1\"", 25)]
    [InlineData("openjdk 25.0.1 2025-10-21", 25)]
    [InlineData("openjdk version \"26-ea\" 2026-03-17", 26)]
    [InlineData("openjdk version \"25\" 2025-09-16\nOpenJDK Runtime Environment Zulu25+1", 25)]
    [InlineData("Picked up JAVA_TOOL_OPTIONS: -Xmx1g\nopenjdk version \"25.0.1\" 2025-10-21 LTS", 25)]
    [InlineData("OpenJDK 64-Bit Server VM warning: Options -Xverify:none are deprecated\nopenjdk version \"21.0.5\"", 21)]
    [InlineData("OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes\r\nopenjdk version \"21.0.5\" 2024-10-15 LTS", 21)]
    [InlineData("OpenJDK 64-Bit Server VM warning: x\nopenjdk version \"25.0.1\" 2025-10-21 LTS", 25)]
    [InlineData("openjdk version \"21.0.5\" 2024-10-15 LTS\nOpenJDK Runtime Environment Temurin-21.0.5+11\nOpenJDK 64-Bit Server VM Temurin-21.0.5+11", 21)]
    public void JavaVersionParserReadsMajorVersion(string output, int expectedMajorVersion)
    {
        Assert.Equal(expectedMajorVersion, ServiceLaunchConfiguration.ParseJavaMajorVersion(output));
    }

    [Theory]
    [InlineData("")]
    [InlineData("Error: LinkageError occurred while loading main class")]
    [InlineData("OpenJDK 64-Bit Server VM warning: Ignoring option MaxPermSize")]
    public void JavaVersionParserRejectsUnrecognizedOutput(string output)
    {
        Assert.Null(ServiceLaunchConfiguration.ParseJavaMajorVersion(output));
    }

    [Theory]
    [InlineData("openjdk version \"21.0.5\" 2024-10-15 LTS", false)]
    [InlineData("openjdk version \"25.0.1\" 2025-10-21 LTS", true)]
    [InlineData("openjdk version \"26-ea\" 2026-03-17", true)]
    [InlineData("Error: could not open jvm.cfg", true)]
    public async Task JavaVersionCheckBlocksOnlyDetectedOlderVersions(string output, bool expected)
    {
        using var directory = new TestDirectory();
        var launch = CreateJarLaunch(directory, (_, _) => Task.FromResult(output), TimeSpan.FromSeconds(30));

        Assert.Equal(expected, await launch.CheckJavaVersionAsync(CancellationToken.None));
    }

    [Fact]
    public async Task JavaVersionCheckAllowsStartupWhenJavaCannotBeExecuted()
    {
        using var directory = new TestDirectory();
        var launch = CreateJarLaunch(directory,
            (_, _) => Task.FromException<string>(new InvalidOperationException("cannot start")), TimeSpan.FromSeconds(30));

        Assert.True(await launch.CheckJavaVersionAsync(CancellationToken.None));
    }

    [Fact]
    public async Task JavaVersionCheckTimeoutAllowsStartupAndCancelsTheProbe()
    {
        using var directory = new TestDirectory();
        var probeToken = CancellationToken.None;
        var launch = CreateJarLaunch(directory, async (_, token) =>
        {
            probeToken = token;
            await Task.Delay(Timeout.Infinite, token);
            return "";
        }, TimeSpan.FromMilliseconds(50));

        Assert.True(await launch.CheckJavaVersionAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.True(probeToken.IsCancellationRequested);
    }

    [Fact]
    public async Task JavaVersionCheckPropagatesShutdownCancellation()
    {
        using var directory = new TestDirectory();
        using var shutdown = new CancellationTokenSource();
        var launch = CreateJarLaunch(directory, async (_, token) =>
        {
            shutdown.Cancel();
            await Task.Delay(Timeout.Infinite, token);
            return "";
        }, TimeSpan.FromSeconds(30));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => launch.CheckJavaVersionAsync(shutdown.Token));
    }

    [Theory]
    [InlineData("Executable", true)]
    [InlineData("Jar", false)]
    public async Task JavaVersionCheckSkipsWhenNotInJarModeOrJavaIsMissing(string mode, bool javaExists)
    {
        using var directory = new TestDirectory();
        var settings = CreateSettings(directory, new()
        {
            ["ServiceExecutionMode"] = mode,
            ["JavaExecutablePath"] = javaExists ? directory.CreateFile("java.exe") : Path.Combine(directory.Root, "missing.exe"),
            ["ServiceJarPath"] = directory.CreateFile("service.jar")
        });
        var launch = new ServiceLaunchConfiguration(settings, NullLogger<WatchdogService>.Instance,
            (_, _) => throw new InvalidOperationException("Java must not be probed"), TimeSpan.FromSeconds(30));

        Assert.True(await launch.CheckJavaVersionAsync(CancellationToken.None));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task JavaVersionCheckRunsRealProcessAndKillsItOnTimeout(bool hangs)
    {
        using var directory = new TestDirectory();
        var java = CreateFakeJava(directory, hangs);
        var settings = CreateSettings(directory, new()
        {
            ["ServiceExecutionMode"] = "Jar",
            ["JavaExecutablePath"] = java,
            ["ServiceJarPath"] = directory.CreateFile("service.jar")
        });
        var launch = new ServiceLaunchConfiguration(settings, NullLogger<WatchdogService>.Instance,
            ServiceLaunchConfiguration.ReadJavaVersionOutputAsync, TimeSpan.FromSeconds(hangs ? 1 : 30));

        // A hanging probe is treated as "unknown" (allowed); a completed one reports Java 21 (blocked).
        Assert.Equal(hangs, await launch.CheckJavaVersionAsync(CancellationToken.None).WaitAsync(TimeSpan.FromSeconds(15)));
    }

    private static string CreateFakeJava(TestDirectory directory, bool hangs)
    {
        if (OperatingSystem.IsWindows())
        {
            var script = directory.CreateFile("java.cmd");
            File.WriteAllText(script, hangs
                ? "@ping -n 60 127.0.0.1 >nul\r\n"
                : "@echo openjdk version \"21.0.5\" 2024-10-15 LTS 1>&2\r\n");
            return script;
        }

        var path = directory.CreateFile("java");
        File.WriteAllText(path, hangs
            ? "#!/bin/sh\nsleep 60\n"
            : "#!/bin/sh\necho 'openjdk version \"21.0.5\" 2024-10-15 LTS' >&2\n");
        File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        return path;
    }

    private static ServiceLaunchConfiguration CreateJarLaunch(TestDirectory directory,
        Func<string, CancellationToken, Task<string>> readJavaVersionOutput, TimeSpan timeout)
    {
        var settings = CreateSettings(directory, new()
        {
            ["ServiceExecutionMode"] = "Jar",
            ["JavaExecutablePath"] = directory.CreateFile("java.exe"),
            ["ServiceJarPath"] = directory.CreateFile("service.jar")
        });
        return new ServiceLaunchConfiguration(settings, NullLogger<WatchdogService>.Instance, readJavaVersionOutput, timeout);
    }

    private static WatchdogSettings CreateSettings(TestDirectory directory, Dictionary<string, string?> values) =>
        new(new ConfigurationBuilder().AddInMemoryCollection(values).Build(),
            new ServicePathResolver(NullLogger<WatchdogService>.Instance, Path.Combine(directory.Root, "watchdog")));
}
