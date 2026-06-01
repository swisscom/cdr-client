using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.ServiceProcess;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

#nullable enable

namespace CuraLineClientUpdateService;

/// <summary>
/// Windows service that automatically checks for and applies updates to curaLINE Client on Windows Server.
/// Downloads JAR artifacts from GitHub releases and manages watchdog updates.
/// Designed for Windows Server environments (especially 2019 and older) using advanced manual installation.
/// </summary>
public class UpdateService : BackgroundService
{
    #region Private Fields

    private readonly ILogger<UpdateService> _logger;
    private readonly IConfiguration _configuration;
    private readonly IHostApplicationLifetime _hostLifetime;
    private readonly HttpClient _httpClient;

    private readonly TimeSpan _updateCheckInterval;
    private readonly string _gitHubRepository;
    private readonly string _watchdogServiceName;
    private readonly string _installationPath;
    private readonly string _javaExecutablePath;

    private readonly Dictionary<string, string> _currentVersions;
    private readonly Dictionary<string, ArtifactConfig> _artifacts;

    #endregion

    #region Helper Classes

    private class ArtifactConfig
    {
        public string FileName { get; set; } = string.Empty;
        public string TargetPath { get; set; } = string.Empty;
    }

    private class GitHubRelease
    {
        public string tag_name { get; set; } = string.Empty;
        public string name { get; set; } = string.Empty;
        public bool prerelease { get; set; }
        public List<GitHubAsset> assets { get; set; } = new();
    }

    private class GitHubAsset
    {
        public string name { get; set; } = string.Empty;
        public string browser_download_url { get; set; } = string.Empty;
    }

    #endregion

    public UpdateService(
        ILogger<UpdateService> logger,
        IConfiguration configuration,
        IHostApplicationLifetime hostLifetime)
    {
        _logger = logger;
        _configuration = configuration;
        _hostLifetime = hostLifetime;
        _httpClient = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        _currentVersions = _configuration.GetSection("CurrentVersions").Get<Dictionary<string, string>>()
            ?? new Dictionary<string, string> { { "Service", "1.0.0" }, { "Watchdog", "1.0.0" } };
        var serviceVersion = _currentVersions.GetValueOrDefault("UpdateService", "1.0.0");
        _httpClient.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("curaLINEClientUpdateService", serviceVersion));
        _updateCheckInterval = TimeSpan.FromHours(_configuration.GetValue<int>("UpdateCheckIntervalHours", 2));
        _gitHubRepository = _configuration["GitHubRepository"] ?? throw new InvalidOperationException("GitHubRepository not configured");
        _watchdogServiceName = _configuration["WatchdogServiceName"] ?? "CDRClientWatchdog";
        _installationPath = ResolveInstallationPath(_configuration["InstallationPath"]);
        _javaExecutablePath = _configuration["JavaExecutablePath"] ?? "bin/jre/bin/java.exe";

        _artifacts = _configuration.GetSection("Artifacts").Get<Dictionary<string, ArtifactConfig>>()
            ?? new Dictionary<string, ArtifactConfig>();

        _logger.LogInformation("curaLINEClientUpdateService initialized. Installation path: {InstallationPath}, Service version: {Version}, Repository: {Repo}",
            _installationPath, _currentVersions.GetValueOrDefault("Service", "unknown"), _gitHubRepository);
    }

    #region Service Execution

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("curaLINEClientUpdateService started. Check interval: {Interval} hours", _updateCheckInterval.TotalHours);

        try
        {
            // Wait a bit before first check to allow system to stabilize
            _logger.LogInformation("Waiting 30 seconds for system stabilization before first update check...");
            await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken);

            // Perform first update check at startup
            _logger.LogInformation("Performing initial update check at startup...");
            await CheckAndApplyUpdates(stoppingToken);

            // Continue with regular interval checks
            while (!stoppingToken.IsCancellationRequested)
            {
                _logger.LogDebug("Next update check in {Hours} hours", _updateCheckInterval.TotalHours);
                await Task.Delay(_updateCheckInterval, stoppingToken);
                await CheckAndApplyUpdates(stoppingToken);
            }
        }
        catch (OperationCanceledException)
        {
            _logger.LogInformation("Update service cancelled - service shutdown requested");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unexpected error in update service loop");
        }
    }

    public override async Task StopAsync(CancellationToken cancellationToken)
    {
        _logger.LogInformation("curaLINEClientUpdateService stopping");
        await base.StopAsync(cancellationToken);
        _httpClient.Dispose();
        _logger.LogInformation("curaLINEClientUpdateService stopped");
    }

    #endregion

    #region Update Check and Apply

    private async Task CheckAndApplyUpdates(CancellationToken cancellationToken)
    {
        try
        {
            _logger.LogInformation("Checking for updates on GitHub...");
            _logger.LogDebug("Current versions - Service: {Service}, Watchdog: {Watchdog}, UpdateService: {Update}",
                _currentVersions.GetValueOrDefault("Service", "unknown"),
                _currentVersions.GetValueOrDefault("Watchdog", "unknown"));

            var latestRelease = await GetLatestGitHubRelease(cancellationToken);
            if (latestRelease == null)
            {
                _logger.LogWarning("Could not retrieve latest release information from GitHub");
                return;
            }

            var remoteVersion = latestRelease.tag_name;
            var currentServiceVersion = _currentVersions.GetValueOrDefault("Service", "1.0.0");

            _logger.LogInformation("Latest GitHub release: {Remote} (current service: {Current})",
                remoteVersion, currentServiceVersion);

            if (IsNewerVersion(remoteVersion, currentServiceVersion))
            {
                _logger.LogInformation("New release {Version} available. Checking which components have updates...", remoteVersion);

                // List available artifacts in the release (INFO level so users can see what's available)
                _logger.LogInformation("Release {Version} contains {Count} artifact(s):", remoteVersion, latestRelease.assets.Count);
                if (latestRelease.assets.Count == 0)
                {
                    _logger.LogWarning("Release {Version} has no artifacts!", remoteVersion);
                    return;
                }

                foreach (var asset in latestRelease.assets)
                {
                    _logger.LogInformation("  - Available: {FileName}", asset.name);
                }

                await ApplyUpdates(latestRelease, remoteVersion, cancellationToken);
            }
            else
            {
                _logger.LogInformation("No new releases available. Current version {Current} is up to date.", currentServiceVersion);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error during update check");
        }
    }

    private async Task<GitHubRelease?> GetLatestGitHubRelease(CancellationToken cancellationToken)
    {
        try
        {
            var apiUrl = $"https://api.github.com/repos/{_gitHubRepository}/releases/latest";
            _logger.LogDebug("Fetching latest release from: {Url}", apiUrl);

            var response = await _httpClient.GetAsync(apiUrl, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                _logger.LogWarning("Failed to fetch latest release. Status: {Status}", response.StatusCode);
                return null;
            }

            var jsonContent = await response.Content.ReadAsStringAsync(cancellationToken);
            var release = JsonSerializer.Deserialize<GitHubRelease>(jsonContent, new JsonSerializerOptions
            {
                PropertyNameCaseInsensitive = true
            });

            return release;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error fetching latest GitHub release");
            return null;
        }
    }

    private bool IsNewerVersion(string remoteVersion, string currentVersion)
    {
        try
        {
            var remote = Version.Parse(remoteVersion);
            var current = Version.Parse(currentVersion);
            return remote > current;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error comparing versions: {Remote} vs {Current}", remoteVersion, currentVersion);
            return false;
        }
    }

    private async Task ApplyUpdates(GitHubRelease release, string newVersion, CancellationToken cancellationToken)
    {
        var updatedComponents = new List<string>();

        try
        {
            // Step 1: Download all available artifacts
            var downloadedFiles = new Dictionary<string, string>();
            var skippedComponents = new List<string>();

            foreach (var (componentName, artifactConfig) in _artifacts)
            {
                var fileName = artifactConfig.FileName.Replace("{version}", newVersion);
                var asset = release.assets.FirstOrDefault(a => a.name == fileName);

                if (asset == null)
                {
                    // Artifact not in this release - likely unchanged, so skip it
                    _logger.LogInformation("Artifact not found in release {Version}: Looking for '{FileName}'. Component likely unchanged, keeping current version.",
                        newVersion, fileName);
                    skippedComponents.Add(componentName);
                    continue;
                }

                _logger.LogInformation("Downloading {Component}: {FileName}", componentName, fileName);
                var downloadedPath = await DownloadFile(asset.browser_download_url, fileName, cancellationToken);

                if (downloadedPath != null)
                {
                    downloadedFiles[componentName] = downloadedPath;
                    _logger.LogInformation("Downloaded {Component} to: {Path}", componentName, downloadedPath);
                }
                else
                {
                    _logger.LogWarning("Failed to download {Component} from release {Version}", componentName, newVersion);
                }
            }

            if (downloadedFiles.Count == 0)
            {
                _logger.LogWarning("No artifacts were downloaded from release {Version}. All components may already be up to date.", newVersion);
                _logger.LogWarning("This could also mean the release didn't provide any artifacts. Please check the GitHub release page:");
                _logger.LogWarning("  https://github.com/{Repo}/releases/tag/{Tag}", _gitHubRepository, release.tag_name);
                _logger.LogWarning("Expected artifacts: {Expected}", string.Join(", ", _artifacts.Select(a => a.Value.FileName.Replace("{version}", newVersion))));
                return;
            }

            _logger.LogInformation("Downloaded {Count} artifact(s), skipped {Skipped} unchanged component(s)",
                downloadedFiles.Count, skippedComponents.Count);

            // Step 2: Stop the watchdog service (which stops the CDR service)
            _logger.LogInformation("Stopping watchdog service: {ServiceName}", _watchdogServiceName);
            StopWatchdogService();
            await Task.Delay(TimeSpan.FromSeconds(5), cancellationToken);

            // Step 3: Apply updates for each component
            foreach (var (componentName, downloadedPath) in downloadedFiles)
            {
                try
                {
                    await ApplyComponentUpdate(componentName, downloadedPath, cancellationToken);
                    updatedComponents.Add(componentName);
                    _logger.LogInformation("Successfully updated {Component}", componentName);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Failed to update {Component}", componentName);
                }
            }

            // Step 4: Update current versions in config for components that were updated
            foreach (var componentName in updatedComponents)
            {
                await UpdateCurrentVersionInConfig(componentName, newVersion);
                _logger.LogInformation("Updated {Component} version to: {Version}", componentName, newVersion);
            }

            // Log current state of all components
            _logger.LogInformation("Component versions after update:");
            _logger.LogInformation("  Service: {Version}",
                updatedComponents.Contains("Service") ? newVersion : _currentVersions.GetValueOrDefault("Service", "unknown"));
            _logger.LogInformation("  Watchdog: {Version}",
                updatedComponents.Contains("Watchdog") ? newVersion : _currentVersions.GetValueOrDefault("Watchdog", "unknown"));

            // Step 5: Restart the watchdog service
            _logger.LogInformation("Restarting watchdog service: {ServiceName}", _watchdogServiceName);
            StartWatchdogService();

            _logger.LogInformation("Update process completed! Updated components: {Components}",
                string.Join(", ", updatedComponents));

            // Cleanup downloaded files
            foreach (var path in downloadedFiles.Values)
            {
                try
                {
                    if (File.Exists(path))
                        File.Delete(path);
                }
                catch { /* Ignore cleanup errors */ }
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error applying updates. Attempting to restart watchdog service...");

            try
            {
                StartWatchdogService();
            }
            catch (Exception restartEx)
            {
                _logger.LogError(restartEx, "Failed to restart watchdog service after update failure");
            }
        }
    }

    private async Task ApplyComponentUpdate(string componentName, string downloadedFilePath, CancellationToken cancellationToken)
    {
        if (!_artifacts.TryGetValue(componentName, out var artifactConfig))
        {
            throw new InvalidOperationException($"No configuration found for component: {componentName}");
        }

        var targetPath = Path.Combine(_installationPath, artifactConfig.TargetPath);
        var fileExtension = Path.GetExtension(downloadedFilePath).ToLowerInvariant();

        if (fileExtension == ".jar")
        {
            // For JAR files, simply copy to target location
            var targetDirectory = Path.GetDirectoryName(targetPath);
            if (!string.IsNullOrEmpty(targetDirectory) && !Directory.Exists(targetDirectory))
            {
                Directory.CreateDirectory(targetDirectory);
            }

            _logger.LogInformation("Copying JAR to: {TargetPath}", targetPath);
            File.Copy(downloadedFilePath, targetPath, overwrite: true);
        }
        else if (fileExtension == ".zip")
        {
            // For ZIP files (like watchdog), extract to target directory
            if (Directory.Exists(targetPath))
            {
                // Backup existing files before updating
                var backupPath = targetPath + ".backup";
                if (Directory.Exists(backupPath))
                {
                    Directory.Delete(backupPath, recursive: true);
                }
                Directory.Move(targetPath, backupPath);
            }

            Directory.CreateDirectory(targetPath);
            _logger.LogInformation("Extracting ZIP to: {TargetPath}", targetPath);
            ZipFile.ExtractToDirectory(downloadedFilePath, targetPath);
        }
        else
        {
            throw new NotSupportedException($"Unsupported file type for updates: {fileExtension}");
        }

        await Task.CompletedTask;
    }

    #endregion

    #region File Operations

    private async Task<string?> DownloadFile(string url, string fileName, CancellationToken cancellationToken)
    {
        try
        {
            _logger.LogDebug("Downloading from: {Url}", url);

            var response = await _httpClient.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                _logger.LogError("Download failed with status: {Status}", response.StatusCode);
                return null;
            }

            var tempFilePath = Path.Combine(Path.GetTempPath(), $"cdr-update-{Guid.NewGuid()}-{fileName}");

            using (var fileStream = new FileStream(tempFilePath, FileMode.Create, FileAccess.Write, FileShare.None, bufferSize: 8192, useAsync: true))
            {
                await response.Content.CopyToAsync(fileStream, cancellationToken);
            }

            _logger.LogDebug("Downloaded {FileName} to: {Path}", fileName, tempFilePath);
            return tempFilePath;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error downloading file from: {Url}", url);
            return null;
        }
    }

    #endregion

    #region Service Management

    private void StopWatchdogService()
    {
        try
        {
            using var service = new ServiceController(_watchdogServiceName);

            if (service.Status == ServiceControllerStatus.Running || service.Status == ServiceControllerStatus.StartPending)
            {
                _logger.LogInformation("Watchdog service status: {Status}. Stopping...", service.Status);
                service.Stop();
                service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(30));
                _logger.LogInformation("Watchdog service stopped successfully");
            }
            else
            {
                _logger.LogInformation("Watchdog service is not running (status: {Status})", service.Status);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error stopping watchdog service");
            throw;
        }
    }

    private void StartWatchdogService()
    {
        try
        {
            using var service = new ServiceController(_watchdogServiceName);

            if (service.Status == ServiceControllerStatus.Stopped)
            {
                _logger.LogInformation("Starting watchdog service...");
                service.Start();
                service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
                _logger.LogInformation("Watchdog service started successfully");
            }
            else
            {
                _logger.LogInformation("Watchdog service is already running (status: {Status})", service.Status);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error starting watchdog service");
            throw;
        }
    }

    #endregion

    #region Configuration Management

    private string ResolveInstallationPath(string? configuredPath)
    {
        if (!string.IsNullOrEmpty(configuredPath))
        {
            return configuredPath;
        }

        // Auto-detect installation path by going up from current directory
        var currentPath = AppContext.BaseDirectory;
        var dir = new DirectoryInfo(currentPath);

        // We're likely in: InstallPath/bin/updateservice/
        // So go up 2 levels
        if (dir.Parent?.Parent != null)
        {
            return dir.Parent.Parent.FullName;
        }

        _logger.LogWarning("Could not auto-detect installation path, using current directory");
        return currentPath;
    }

    private async Task UpdateCurrentVersionInConfig(string componentName, string newVersion)
    {
        try
        {
            var configPath = Path.Combine(AppContext.BaseDirectory, "appsettings.json");
            if (!File.Exists(configPath))
            {
                _logger.LogWarning("Could not find appsettings.json to update version");
                return;
            }

            var configContent = await File.ReadAllTextAsync(configPath);

            // Update the specific component version in CurrentVersions section
            var pattern = $"(\"{componentName}\"\\s*:\\s*)\"[^\"]+\"";
            var replacement = $"$1\"{newVersion}\"";
            var updatedContent = Regex.Replace(configContent, pattern, replacement);

            await File.WriteAllTextAsync(configPath, updatedContent);
            _logger.LogInformation("Updated {Component} version in configuration to: {Version}", componentName, newVersion);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not update version in configuration file");
        }
    }

    #endregion
}






