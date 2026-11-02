using Microsoft.Extensions.Logging;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace CdrClientWatchdog;

internal sealed class ServiceLaunchConfiguration
{
    private const int MinimumJavaMajorVersion = 25;
    private static readonly TimeSpan DefaultJavaVersionCheckTimeout = TimeSpan.FromSeconds(10);
    // Atomic group + lookahead stop JVM banners like "OpenJDK 64-Bit Server VM warning" from matching as Java 64 (or 6).
    private static readonly Regex JavaVersionPattern = new(
        @"(?:^|\s)(?:java|openjdk)(?:\s+version)?\s+[""']?(?<version>(?>\d+))(?!-bit\b)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private readonly WatchdogSettings _settings;
    private readonly ILogger _logger;
    private readonly Func<string, CancellationToken, Task<string>> _readJavaVersionOutput;
    private readonly TimeSpan _javaVersionCheckTimeout;

    public ServiceLaunchConfiguration(WatchdogSettings settings, ILogger logger)
        : this(settings, logger, ReadJavaVersionOutputAsync, DefaultJavaVersionCheckTimeout)
    {
    }

    internal ServiceLaunchConfiguration(WatchdogSettings settings, ILogger logger,
        Func<string, CancellationToken, Task<string>> readJavaVersionOutput, TimeSpan javaVersionCheckTimeout)
    {
        _settings = settings;
        _logger = logger;
        _readJavaVersionOutput = readJavaVersionOutput;
        _javaVersionCheckTimeout = javaVersionCheckTimeout;
    }

    public bool Validate()
    {
        if (_settings.IsJarMode)
        {
            if (!File.Exists(_settings.JavaExecutablePath))
            {
                _logger.LogError(
                    "Java executable not found at configured path: {Path}. " +
                    "Please verify the JavaExecutablePath configuration in appsettings.json. " +
                    "Stopping watchdog service due to configuration error.",
                    _settings.JavaExecutablePath);
                return false;
            }

            if (!File.Exists(_settings.ServiceJarPath))
            {
                _logger.LogError(
                    "Service JAR not found at configured path: {Path}. " +
                    "Please verify the ServiceJarPath configuration in appsettings.json. " +
                    "Stopping watchdog service due to configuration error.",
                    _settings.ServiceJarPath);
                return false;
            }

            return true;
        }

        if (File.Exists(_settings.ServiceExecutablePath))
        {
            return true;
        }

        _logger.LogError(
            "Service executable not found at configured path: {Path}. " +
            "Please verify the ServiceExecutablePath configuration in appsettings.json. " +
            "Current working directory: {WorkingDirectory}. " +
            "Watchdog executable location: {WatchdogPath}. " +
            "Stopping watchdog service due to configuration error. Fix the ServiceExecutablePath and restart the service.",
            _settings.ServiceExecutablePath, Environment.CurrentDirectory, Environment.ProcessPath);
        return false;
    }

    // Runs once before the monitoring loop, outside the lifecycle lock, so a service stop cancels it immediately.
    // Only a successfully detected, too-old Java blocks startup. If the version cannot be determined
    // (unusual vendor output, timeout, launcher quirks), we warn and let the service start anyway.
    public async Task<bool> CheckJavaVersionAsync(CancellationToken cancellationToken)
    {
        // Only JAR mode uses a user-provided Java; Executable mode bundles its runtime.
        // Missing files are reported by Validate() right before the launch.
        if (!_settings.IsJarMode || !File.Exists(_settings.JavaExecutablePath))
            return true;

        var majorVersion = await TryDetectJavaMajorVersionAsync(cancellationToken);
        if (majorVersion is null)
        {
            _logger.LogWarning(
                "Could not determine the Java version of {JavaPath}; skipping the Java {MinimumVersion}+ check",
                _settings.JavaExecutablePath, MinimumJavaMajorVersion);
            return true;
        }

        if (majorVersion < MinimumJavaMajorVersion)
        {
            _logger.LogError(
                "Unsupported Java version at {JavaPath}: detected Java {DetectedVersion}, Java {MinimumVersion} or newer is required. " +
                "Please verify the JavaExecutablePath configuration in appsettings.json. " +
                "Stopping watchdog service due to configuration error.",
                _settings.JavaExecutablePath, majorVersion, MinimumJavaMajorVersion);
            return false;
        }

        _logger.LogInformation("Java version check passed for {JavaPath}: detected Java {DetectedVersion}",
            _settings.JavaExecutablePath, majorVersion);
        return true;
    }

    private async Task<int?> TryDetectJavaMajorVersionAsync(CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(_javaVersionCheckTimeout);
        try
        {
            var output = await _readJavaVersionOutput(_settings.JavaExecutablePath, timeout.Token);
            var majorVersion = ParseJavaMajorVersion(output);
            if (majorVersion is null)
                _logger.LogWarning("Unrecognized `java -version` output: {Output}", output.Trim());
            return majorVersion;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            _logger.LogWarning("Java version check timed out after {TimeoutSeconds} seconds",
                _javaVersionCheckTimeout.TotalSeconds);
            return null;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogWarning(ex, "Java version check failed unexpectedly using {JavaPath}", _settings.JavaExecutablePath);
            return null;
        }
    }

    internal static async Task<string> ReadJavaVersionOutputAsync(string javaPath, CancellationToken cancellationToken)
    {
        var startInfo = new ProcessStartInfo
        {
            FileName = javaPath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        startInfo.ArgumentList.Add("-version");

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException($"Could not start {javaPath}");
        var standardOutput = process.StandardOutput.ReadToEndAsync();
        var standardError = process.StandardError.ReadToEndAsync();
        try
        {
            await process.WaitForExitAsync(cancellationToken);
        }
        catch (OperationCanceledException)
        {
            TryKill(process);
            throw;
        }

        // `java -version` writes to stderr, newer builds may use stdout; inspect both.
        return $"{await standardError}{Environment.NewLine}{await standardOutput}";
    }

    private static void TryKill(Process process)
    {
        try
        {
            process.Kill(entireProcessTree: true);
        }
        catch (Exception)
        {
            // Process already exited or cannot be killed; nothing more to do for a best-effort check.
        }
    }

    internal static int? ParseJavaMajorVersion(string versionOutput)
    {
        if (string.IsNullOrWhiteSpace(versionOutput))
            return null;

        var match = JavaVersionPattern.Match(versionOutput);
        if (!match.Success)
            return null;

        return int.TryParse(match.Groups["version"].Value, out var major) ? major : null;
    }

    public ProcessStartInfo CreateProcessStartInfo()
    {
        if (_settings.IsJarMode)
        {
            var workingDirectory = Path.GetDirectoryName(_settings.ServiceJarPath) ?? Environment.CurrentDirectory;
            var startInfo = new ProcessStartInfo
            {
                FileName = _settings.JavaExecutablePath,
                WorkingDirectory = workingDirectory,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            foreach (var argument in ParseJavaArguments(_settings.JavaArguments))
            {
                startInfo.ArgumentList.Add(argument);
                _logger.LogDebug("Added argument: {Arg}", argument);
            }

            startInfo.ArgumentList.Add("-jar");
            startInfo.ArgumentList.Add(_settings.ServiceJarPath);
            _logger.LogInformation("Starting curaLINE Client service in JAR mode with {Count} arguments from working directory: {WorkingDirectory}",
                startInfo.ArgumentList.Count, workingDirectory);
            _logger.LogDebug("Full command: {Command} {Arguments}",
                _settings.JavaExecutablePath,
                string.Join(" ", startInfo.ArgumentList.Select(a => a.Contains(" ") ? $"\"{a}\"" : a)));
            return startInfo;
        }

        var executableDirectory = Path.GetDirectoryName(_settings.ServiceExecutablePath) ?? Environment.CurrentDirectory;
        _logger.LogInformation("Starting curaLINE Client service in Executable mode: {Path} from working directory: {WorkingDirectory}",
            _settings.ServiceExecutablePath, executableDirectory);
        return new ProcessStartInfo
        {
            FileName = _settings.ServiceExecutablePath,
            Arguments = string.Empty,
            WorkingDirectory = executableDirectory,
            UseShellExecute = false,
            CreateNoWindow = true
        };
    }

    internal static IEnumerable<string> ParseJavaArguments(string arguments)
    {
        if (string.IsNullOrWhiteSpace(arguments))
            yield break;

        var tokens = Regex.Split(arguments, @"\s+");
        for (var i = 0; i < tokens.Length; i++)
        {
            var token = tokens[i].Trim();
            if (string.IsNullOrEmpty(token))
                continue;

            if (token.StartsWith("-D") && token.Contains('='))
            {
                var equalsIndex = token.IndexOf('=');
                var key = token.Substring(0, equalsIndex + 1);
                var valuePart = token.Substring(equalsIndex + 1);

                // Preserve unquoted paths containing spaces until the next JVM option.
                if ((valuePart.Contains('\\') || valuePart.Contains('/') || valuePart.Contains(':')) &&
                    i + 1 < tokens.Length)
                {
                    var completeValue = new StringBuilder(valuePart);
                    while (i + 1 < tokens.Length && !tokens[i + 1].StartsWith("-"))
                    {
                        i++;
                        completeValue.Append(' ');
                        completeValue.Append(tokens[i]);
                    }
                    yield return key + completeValue.ToString();
                }
                else
                {
                    yield return token;
                }
            }
            else if (token.StartsWith("\"") && token.EndsWith("\""))
            {
                yield return token.Substring(1, token.Length - 2);
            }
            else
            {
                yield return token;
            }
        }
    }
}
