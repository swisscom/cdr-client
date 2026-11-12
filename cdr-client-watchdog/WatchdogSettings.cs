using Microsoft.Extensions.Configuration;
using System;

namespace CdrClientWatchdog;

internal sealed class WatchdogSettings
{
    public string ServiceExecutionMode { get; }
    public string ServiceExecutablePath { get; }
    public string ServiceJarPath { get; }
    public string JavaExecutablePath { get; }
    public string JavaArguments { get; }
    public TimeSpan RestartDelay { get; }
    public TimeSpan HealthCheckInterval { get; }
    public int MaxConsecutiveFailures { get; }
    public TimeSpan GracefulShutdownTimeout { get; }
    public bool IsJarMode => ServiceExecutionMode.Equals("Jar", StringComparison.OrdinalIgnoreCase);

    internal const int DefaultGracefulShutdownTimeoutSeconds = 20;

    public WatchdogSettings(IConfiguration configuration, ServicePathResolver pathResolver)
    {
        ServiceExecutionMode = configuration["ServiceExecutionMode"] ?? "Executable";
        ServiceExecutablePath = pathResolver.ResolvePath(configuration["ServiceExecutablePath"] ?? "cdr-client-service.exe");
        ServiceJarPath = pathResolver.ResolvePath(configuration["ServiceJarPath"] ?? "../lib/cdr-client-service.jar");
        JavaExecutablePath = pathResolver.ResolvePath(configuration["JavaExecutablePath"] ?? "java");
        JavaArguments = configuration["JavaArguments"] ?? "";
        RestartDelay = TimeSpan.FromSeconds(configuration.GetValue<int>("RestartDelaySeconds", 2));
        HealthCheckInterval = TimeSpan.FromSeconds(configuration.GetValue<int>("HealthCheckIntervalSeconds", 30));
        MaxConsecutiveFailures = configuration.GetValue<int>("MaxConsecutiveFailures", 5);
        // 0 disables the graceful shutdown; the service process is then terminated immediately.
        GracefulShutdownTimeout = TimeSpan.FromSeconds(
            Math.Max(0, configuration.GetValue<int>("GracefulShutdownTimeoutSeconds", DefaultGracefulShutdownTimeoutSeconds)));
    }
}
