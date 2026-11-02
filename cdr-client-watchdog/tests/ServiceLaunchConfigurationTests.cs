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

    private static WatchdogSettings CreateSettings(TestDirectory directory, Dictionary<string, string?> values) =>
        new(new ConfigurationBuilder().AddInMemoryCollection(values).Build(),
            new ServicePathResolver(NullLogger<WatchdogService>.Instance, Path.Combine(directory.Root, "watchdog")));
}
