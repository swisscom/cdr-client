using Microsoft.Extensions.Logging;
using System;
using System.IO;
using System.Linq;

#nullable enable

namespace CdrClientWatchdog;

internal sealed class ServicePathResolver
{
    private readonly ILogger _logger;
    private readonly string _watchdogDirectory;

    public ServicePathResolver(ILogger<WatchdogService> logger)
        : this(logger, Path.GetDirectoryName(Environment.ProcessPath) ?? Environment.CurrentDirectory)
    {
    }

    internal ServicePathResolver(ILogger logger, string watchdogDirectory)
    {
        _logger = logger;
        _watchdogDirectory = watchdogDirectory;
    }

    public string ResolvePath(string path)
    {
        try
        {
            var expandedPath = Environment.ExpandEnvironmentVariables(path);
            if (Path.IsPathRooted(expandedPath))
            {
                _logger.LogInformation("Using absolute service executable path: {Path}", expandedPath);
                return expandedPath;
            }

            var baseDirectory = DetermineBaseDirectory(_watchdogDirectory);
            var candidate = Path.GetFullPath(Path.Combine(baseDirectory, expandedPath));
            _logger.LogDebug("Attempting to resolve service executable using base directory '{BaseDirectory}' -> '{Candidate}'",
                baseDirectory, candidate);

            if (File.Exists(candidate))
            {
                _logger.LogInformation("Resolved relative path '{OriginalPath}' to existing path '{ResolvedPath}'", path, candidate);
                return candidate;
            }

            var found = SearchAncestorBinsForExecutable(_watchdogDirectory, expandedPath);
            if (!string.IsNullOrEmpty(found))
            {
                _logger.LogInformation("Resolved relative path '{OriginalPath}' to discovered path '{ResolvedPath}' via ancestor bin search",
                    path, found);
                return found;
            }

            _logger.LogWarning("Could not find executable at '{Candidate}' or via ancestor search; returning candidate for diagnostic/error reporting.",
                candidate);
            return candidate;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error resolving path: {Path}. Using original path as fallback.", path);
            return path;
        }
    }

    private string? SearchAncestorBinsForExecutable(string startDirectory, string exeRelativePath)
    {
        try
        {
            var directory = new DirectoryInfo(startDirectory);
            while (directory != null)
            {
                var candidate = Path.GetFullPath(Path.Combine(directory.FullName, "bin", exeRelativePath));
                _logger.LogDebug("Searching for executable candidate: {Candidate}", candidate);
                if (File.Exists(candidate)) return candidate;

                candidate = Path.GetFullPath(Path.Combine(directory.FullName, "app", "bin", exeRelativePath));
                _logger.LogDebug("Searching for executable candidate: {Candidate}", candidate);
                if (File.Exists(candidate)) return candidate;

                directory = directory.Parent;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Error during ancestor bin search");
        }

        return null;
    }

    internal string DetermineBaseDirectory(string watchdogDirectory)
    {
        if (watchdogDirectory.Contains("WindowsApps", StringComparison.OrdinalIgnoreCase))
        {
            var resolvedDirectory = ResolveMsixBaseDirectory(watchdogDirectory);
            _logger.LogInformation("Detected MSIX environment, resolved base directory: {Directory}", resolvedDirectory);
            return resolvedDirectory;
        }

        var parentDirectory = Path.GetDirectoryName(watchdogDirectory);
        if (watchdogDirectory.Contains("bin\\watchdog", StringComparison.OrdinalIgnoreCase))
        {
            _logger.LogInformation("Detected traditional Conveyor structure, using parent directory: {Directory}", parentDirectory);
        }
        else
        {
            _logger.LogInformation("Detected traditional installation, using parent directory: {Directory}", parentDirectory);
        }
        return parentDirectory ?? watchdogDirectory;
    }

    private string ResolveMsixBaseDirectory(string watchdogDirectory)
    {
        if (watchdogDirectory.Contains("app\\bin\\watchdog", StringComparison.OrdinalIgnoreCase))
        {
            var pathParts = watchdogDirectory.Split('\\');
            var appIndex = Array.FindIndex(pathParts, part => part.Equals("app", StringComparison.OrdinalIgnoreCase));
            if (appIndex >= 0)
            {
                return Path.Combine(string.Join("\\", pathParts.Take(appIndex)), "bin");
            }

            _logger.LogWarning("Expected 'app' directory not found in MSIX path, using parent directory");
            return Path.GetDirectoryName(watchdogDirectory) ?? watchdogDirectory;
        }

        return FindMsixRootAndAddBin(watchdogDirectory);
    }

    private string FindMsixRootAndAddBin(string watchdogDirectory)
    {
        var pathParts = watchdogDirectory.Split('\\');
        for (var i = pathParts.Length - 1; i >= 0; i--)
        {
            var part = pathParts[i];
            if (part.Contains("_") &&
                (part.Contains("x64", StringComparison.OrdinalIgnoreCase) ||
                 part.Contains("x86", StringComparison.OrdinalIgnoreCase)))
            {
                _logger.LogDebug("Found MSIX package directory: {Directory} at index {Index}", part, i);
                var resolvedPath = Path.Combine(string.Join("\\", pathParts.Take(i + 1)), "bin");
                _logger.LogInformation("Found MSIX package root, resolved path: {Path}", resolvedPath);
                return resolvedPath;
            }
        }

        _logger.LogDebug("No MSIX package directory pattern found in path");
        _logger.LogWarning("MSIX package directory pattern not found, using parent directory");
        return Path.GetDirectoryName(watchdogDirectory) ?? watchdogDirectory;
    }
}
