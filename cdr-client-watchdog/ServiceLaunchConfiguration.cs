using Microsoft.Extensions.Logging;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace CdrClientWatchdog;

internal sealed class ServiceLaunchConfiguration
{
    private readonly WatchdogSettings _settings;
    private readonly ILogger _logger;

    public ServiceLaunchConfiguration(WatchdogSettings settings, ILogger<WatchdogService> logger)
    {
        _settings = settings;
        _logger = logger;
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
