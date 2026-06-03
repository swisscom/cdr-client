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
    private const string UpdateBaseUrl = "https://cdr.health.swisscom.ch/share/downloads/manualInstallation";
    private readonly string _watchdogServiceName;
    private readonly string _installationPath;
    private readonly string _javaExecutablePath;
    private readonly string? _pinnedVersion;
    private readonly int _maxBackupsToKeep;

    private readonly Dictionary<string, string> _currentVersions;
    private readonly Dictionary<string, ArtifactConfig> _artifacts;

    #endregion

    #region Helper Classes

    private class ArtifactConfig
    {
        public string FileName { get; set; } = string.Empty;
        public string TargetPath { get; set; } = string.Empty;
    }

    private class LatestInfo
    {
        public string version { get; set; } = string.Empty;
        public string manifestUrl { get; set; } = string.Empty;
    }

    private class ManifestInfo
    {
        public string version { get; set; } = string.Empty;
        public string releaseDate { get; set; } = string.Empty;
        public List<ManifestArtifact> artifacts { get; set; } = new();
    }

    private class ManifestArtifact
    {
        public string name { get; set; } = string.Empty;
        public string fileName { get; set; } = string.Empty;
        public string url { get; set; } = string.Empty;
        public string checksum { get; set; } = string.Empty;
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

        // Configure HttpClient with system proxy support
        var handler = new HttpClientHandler
        {
            UseProxy = true,
            DefaultProxyCredentials = System.Net.CredentialCache.DefaultNetworkCredentials
        };
        _httpClient = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(10) };

        _currentVersions = _configuration.GetSection("CurrentVersions").Get<Dictionary<string, string>>()
            ?? new Dictionary<string, string> { { "Service", "1.0.0" }, { "Watchdog", "1.0.0" } };
        var serviceVersion = _currentVersions.GetValueOrDefault("UpdateService", "1.0.0");
        _httpClient.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("curaLINEClientUpdateService", serviceVersion));
        _updateCheckInterval = TimeSpan.FromHours(_configuration.GetValue<int>("UpdateCheckIntervalHours", 2));
        _watchdogServiceName = _configuration["WatchdogServiceName"] ?? "CDRClientWatchdog";
        _installationPath = ResolveInstallationPath(_configuration["InstallationPath"]);
        _javaExecutablePath = _configuration["JavaExecutablePath"] ?? "bin/jre/bin/java.exe";
        _pinnedVersion = _configuration["PinnedVersion"];
        _maxBackupsToKeep = _configuration.GetValue<int>("MaxBackupsToKeep", 3);

        _artifacts = _configuration.GetSection("Artifacts").Get<Dictionary<string, ArtifactConfig>>()
            ?? new Dictionary<string, ArtifactConfig>();

        _logger.LogInformation("curaLINEClientUpdateService initialized. Installation path: {InstallationPath}, Service version: {Version}, Update URL: {UpdateUrl}",
            _installationPath, _currentVersions.GetValueOrDefault("Service", "unknown"), UpdateBaseUrl);

        _logger.LogInformation("HttpClient configured with system proxy and default network credentials");

        if (!string.IsNullOrEmpty(_pinnedVersion))
        {
            _logger.LogWarning("Version pinned to: {PinnedVersion}. Automatic updates will be skipped.", _pinnedVersion);
        }
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
            // Check if version is pinned
            if (!string.IsNullOrEmpty(_pinnedVersion))
            {
                _logger.LogInformation("Version pinned to {PinnedVersion}. Skipping update check.", _pinnedVersion);
                return;
            }

            _logger.LogInformation("Checking for updates from Azure Storage...");
            _logger.LogDebug("Current versions - Service: {Service}, Watchdog: {Watchdog}",
                _currentVersions.GetValueOrDefault("Service", "unknown"),
                _currentVersions.GetValueOrDefault("Watchdog", "unknown"));

            var manifestInfo = await GetLatestReleaseFromManifest(cancellationToken);
            if (manifestInfo == null)
            {
                _logger.LogWarning("Could not retrieve latest release information from Azure Storage");
                return;
            }

            var remoteVersion = manifestInfo.version;
            var currentServiceVersion = _currentVersions.GetValueOrDefault("Service", "1.0.0");

            _logger.LogInformation("Latest release: {Remote} (current service: {Current})",
                remoteVersion, currentServiceVersion);

            if (IsNewerVersion(remoteVersion, currentServiceVersion))
            {
                _logger.LogInformation("New release {Version} available (released: {ReleaseDate}). Checking which components have updates...",
                    remoteVersion, manifestInfo.releaseDate);

                // List available artifacts in the release (INFO level so users can see what's available)
                _logger.LogInformation("Release {Version} contains {Count} artifact(s):", remoteVersion, manifestInfo.artifacts.Count);
                if (manifestInfo.artifacts.Count == 0)
                {
                    _logger.LogWarning("Release {Version} has no artifacts!", remoteVersion);
                    return;
                }

                foreach (var artifact in manifestInfo.artifacts)
                {
                    _logger.LogInformation("  - Available: {FileName} (checksum: {Checksum})",
                        artifact.fileName, artifact.checksum.Length > 16 ? artifact.checksum.Substring(0, 16) + "..." : artifact.checksum);
                }

                await ApplyUpdates(manifestInfo, remoteVersion, cancellationToken);
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

    private async Task<ManifestInfo?> GetLatestReleaseFromManifest(CancellationToken cancellationToken)
    {
        try
        {
            // Step 1: Fetch latest.json to get the current version
            var latestUrl = $"{UpdateBaseUrl}/latest.json";
            _logger.LogDebug("Fetching latest version info from: {Url}", latestUrl);

            var response = await _httpClient.GetAsync(latestUrl, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                _logger.LogWarning("Failed to fetch latest.json. Status: {Status}", response.StatusCode);
                return null;
            }

            var latestJsonContent = await response.Content.ReadAsStringAsync(cancellationToken);
            var latestInfo = JsonSerializer.Deserialize<LatestInfo>(latestJsonContent, new JsonSerializerOptions
            {
                PropertyNameCaseInsensitive = true
            });

            if (latestInfo == null || string.IsNullOrEmpty(latestInfo.version))
            {
                _logger.LogWarning("Invalid latest.json content");
                return null;
            }

            _logger.LogDebug("Latest version from Azure Storage: {Version}", latestInfo.version);

            // Step 2: Fetch the manifest.json for that version
            var manifestUrl = latestInfo.manifestUrl;
            _logger.LogDebug("Fetching manifest from: {Url}", manifestUrl);

            response = await _httpClient.GetAsync(manifestUrl, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                _logger.LogWarning("Failed to fetch manifest.json. Status: {Status}", response.StatusCode);
                return null;
            }

            var manifestJsonContent = await response.Content.ReadAsStringAsync(cancellationToken);
            var manifest = JsonSerializer.Deserialize<ManifestInfo>(manifestJsonContent, new JsonSerializerOptions
            {
                PropertyNameCaseInsensitive = true
            });

            return manifest;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error fetching latest release from Azure Storage");
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

    private async Task ApplyUpdates(ManifestInfo manifest, string newVersion, CancellationToken cancellationToken)
    {
        var updatedComponents = new List<string>();
        var backupPath = Path.Combine(_installationPath, $"backup-{DateTime.UtcNow:yyyyMMdd-HHmmss}");

        try
        {
            // Step 1: Download and verify ALL artifacts first (don't touch anything yet)
            _logger.LogInformation("Downloading and verifying artifacts...");
            var downloadedFiles = new Dictionary<string, string>();
            var skippedComponents = new List<string>();

            foreach (var manifestArtifact in manifest.artifacts)
            {
                // Check if this artifact matches one of our configured components
                var componentName = manifestArtifact.name;

                if (!_artifacts.ContainsKey(componentName))
                {
                    _logger.LogWarning("Artifact {Name} in manifest does not match any configured component, skipping", componentName);
                    continue;
                }

                _logger.LogInformation("Downloading {Component}: {FileName}", componentName, manifestArtifact.fileName);
                var downloadedPath = await DownloadAndVerifyFile(
                    manifestArtifact.url,
                    manifestArtifact.fileName,
                    manifestArtifact.checksum,
                    cancellationToken);

                if (downloadedPath != null)
                {
                    downloadedFiles[componentName] = downloadedPath;
                    _logger.LogInformation("Downloaded and verified {Component} to: {Path}", componentName, downloadedPath);
                }
                else
                {
                    _logger.LogError("Failed to download or verify {Component} from release {Version}", componentName, newVersion);
                    throw new InvalidOperationException($"Failed to download or verify {componentName}");
                }
            }

            if (downloadedFiles.Count == 0)
            {
                _logger.LogWarning("No artifacts were downloaded from release {Version}. All components may already be up to date.", newVersion);
                return;
            }

            _logger.LogInformation("All artifacts downloaded and verified successfully. Proceeding with update...");

            // Step 2: Create backup of current files
            _logger.LogInformation("Creating backup at: {BackupPath}", backupPath);
            CreateBackup(backupPath, downloadedFiles.Keys);

            // Step 3: Stop the watchdog service (which stops the CDR service)
            _logger.LogInformation("Stopping watchdog service: {ServiceName}", _watchdogServiceName);
            StopWatchdogService();
            await Task.Delay(TimeSpan.FromSeconds(5), cancellationToken);

            // Step 4: Apply updates for each component
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
                    throw; // This will trigger rollback
                }
            }

            // Step 5: Update current versions in config for components that were updated
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

            // Step 6: Restart the watchdog service
            _logger.LogInformation("Restarting watchdog service: {ServiceName}", _watchdogServiceName);
            StartWatchdogService();

            // Step 7: Verify service started successfully
            await Task.Delay(TimeSpan.FromSeconds(10), cancellationToken);
            VerifyWatchdogRunning();

            _logger.LogInformation("Update process completed successfully! Updated components: {Components}",
                string.Join(", ", updatedComponents));

            // Step 8: Cleanup - delete old backups and downloaded files
            CleanupOldBackups();

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
            _logger.LogError(ex, "Update failed! Attempting rollback from backup...");

            try
            {
                RestoreFromBackup(backupPath);
                StartWatchdogService();
                _logger.LogInformation("Rollback successful. System restored to previous state.");
            }
            catch (Exception rollbackEx)
            {
                _logger.LogCritical(rollbackEx, "ROLLBACK FAILED! Manual intervention required. Backup location: {BackupPath}", backupPath);
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
            // Files that should be preserved during updates to protect user modifications
            // These files are part of the initial installation and may contain local customizations
            var protectedFiles = new[] { "appsettings.json", "install-service.bat", "uninstall-service.bat" };
            var preservedFiles = new Dictionary<string, byte[]>();

            // Step 1: Preserve protected files (will exist after initial installation)
            if (Directory.Exists(targetPath))
            {
                foreach (var protectedFile in protectedFiles)
                {
                    var protectedFilePath = Path.Combine(targetPath, protectedFile);
                    if (File.Exists(protectedFilePath))
                    {
                        preservedFiles[protectedFile] = File.ReadAllBytes(protectedFilePath);
                        _logger.LogInformation("Preserving existing file (may contain user modifications): {FileName}", protectedFile);
                    }
                    else
                    {
                        _logger.LogWarning("Expected file not found: {FileName} - will use version from update", protectedFile);
                    }
                }

                // Delete the entire directory to remove old files
                Directory.Delete(targetPath, recursive: true);
                _logger.LogDebug("Deleted old directory to ensure clean update");
            }
            else
            {
                _logger.LogDebug("Target directory does not exist - this appears to be a fresh installation");
            }

            // Step 2: Extract new files
            Directory.CreateDirectory(targetPath);
            _logger.LogInformation("Extracting ZIP to: {TargetPath}", targetPath);
            ZipFile.ExtractToDirectory(downloadedFilePath, targetPath);

            // Step 3: Handle protected files
            foreach (var protectedFile in protectedFiles)
            {
                var protectedFilePath = Path.Combine(targetPath, protectedFile);
                var newFilePath = Path.Combine(targetPath, protectedFile + ".new");

                if (preservedFiles.ContainsKey(protectedFile))
                {
                    // We preserved the local version - save the new version as .new for manual review
                    if (File.Exists(protectedFilePath))
                    {
                        File.Move(protectedFilePath, newFilePath, overwrite: true);
                        _logger.LogInformation("New version of {FileName} saved as {NewFileName} (review and merge manually)",
                            protectedFile, protectedFile + ".new");
                    }

                    // Restore the preserved local file
                    File.WriteAllBytes(protectedFilePath, preservedFiles[protectedFile]);
                    _logger.LogInformation("Kept existing {FileName} (may contain user modifications)", protectedFile);
                }
                else
                {
                    // Fresh installation or file was missing - use the new version directly
                    _logger.LogInformation("Using new {FileName} from update package", protectedFile);
                }
            }
        }
        else
        {
            throw new NotSupportedException($"Unsupported file type for updates: {fileExtension}");
        }

        await Task.CompletedTask;
    }

    #endregion

    #region File Operations

    private async Task<string?> DownloadAndVerifyFile(string url, string fileName, string expectedChecksum, CancellationToken cancellationToken)
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

            // Verify checksum
            using (var sha256 = System.Security.Cryptography.SHA256.Create())
            {
                using (var fileStream = File.OpenRead(tempFilePath))
                {
                    var hash = await sha256.ComputeHashAsync(fileStream, cancellationToken);
                    var actualChecksum = "sha256:" + BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();

                    if (actualChecksum != expectedChecksum.ToLowerInvariant())
                    {
                        _logger.LogError("Checksum mismatch for {FileName}. Expected: {Expected}, Got: {Actual}",
                            fileName, expectedChecksum, actualChecksum);
                        File.Delete(tempFilePath);
                        return null;
                    }

                    _logger.LogInformation("Checksum verified for {FileName}: {Checksum}", fileName, actualChecksum.Substring(0, 16) + "...");
                }
            }

            return tempFilePath;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error downloading or verifying file from: {Url}", url);
            return null;
        }
    }

    #endregion

    #region Backup and Restore

    private void CreateBackup(string backupPath, IEnumerable<string> componentNames)
    {
        try
        {
            Directory.CreateDirectory(backupPath);

            foreach (var componentName in componentNames)
            {
                if (_artifacts.TryGetValue(componentName, out var artifactConfig))
                {
                    var sourcePath = Path.Combine(_installationPath, artifactConfig.TargetPath);
                    var extension = Path.GetExtension(sourcePath).ToLowerInvariant();

                    if (extension == ".jar" && File.Exists(sourcePath))
                    {
                        // Backup JAR file
                        var backupFile = Path.Combine(backupPath, $"{componentName}.jar");
                        File.Copy(sourcePath, backupFile, overwrite: true);
                        _logger.LogDebug("Backed up {Component} JAR to: {BackupFile}", componentName, backupFile);
                    }
                    else if (Directory.Exists(sourcePath))
                    {
                        // Backup directory (like watchdog)
                        var backupDir = Path.Combine(backupPath, componentName);
                        CopyDirectory(sourcePath, backupDir);
                        _logger.LogDebug("Backed up {Component} directory to: {BackupDir}", componentName, backupDir);
                    }
                }
            }

            _logger.LogInformation("Backup created successfully at: {BackupPath}", backupPath);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error creating backup");
            throw;
        }
    }

    private void RestoreFromBackup(string backupPath)
    {
        try
        {
            if (!Directory.Exists(backupPath))
            {
                _logger.LogError("Backup directory not found: {BackupPath}", backupPath);
                throw new DirectoryNotFoundException($"Backup not found: {backupPath}");
            }

            _logger.LogInformation("Restoring from backup: {BackupPath}", backupPath);

            foreach (var (componentName, artifactConfig) in _artifacts)
            {
                var targetPath = Path.Combine(_installationPath, artifactConfig.TargetPath);
                var backupFile = Path.Combine(backupPath, $"{componentName}.jar");
                var backupDir = Path.Combine(backupPath, componentName);

                if (File.Exists(backupFile))
                {
                    // Restore JAR file
                    File.Copy(backupFile, targetPath, overwrite: true);
                    _logger.LogInformation("Restored {Component} JAR from backup", componentName);
                }
                else if (Directory.Exists(backupDir))
                {
                    // Restore directory
                    if (Directory.Exists(targetPath))
                    {
                        Directory.Delete(targetPath, recursive: true);
                    }
                    CopyDirectory(backupDir, targetPath);
                    _logger.LogInformation("Restored {Component} directory from backup", componentName);
                }
            }

            _logger.LogInformation("Restore from backup completed successfully");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error restoring from backup");
            throw;
        }
    }

    private void CleanupOldBackups()
    {
        try
        {
            var backups = Directory.GetDirectories(_installationPath, "backup-*")
                .Select(d => new DirectoryInfo(d))
                .OrderByDescending(d => d.CreationTime)
                .Skip(_maxBackupsToKeep)
                .ToList();

            foreach (var backup in backups)
            {
                _logger.LogInformation("Deleting old backup: {BackupPath}", backup.FullName);
                backup.Delete(recursive: true);
            }

            if (backups.Count > 0)
            {
                _logger.LogInformation("Cleaned up {Count} old backup(s)", backups.Count);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Error cleaning up old backups (non-critical)");
        }
    }

    private void CopyDirectory(string sourceDir, string destinationDir)
    {
        var dir = new DirectoryInfo(sourceDir);

        if (!dir.Exists)
        {
            throw new DirectoryNotFoundException($"Source directory not found: {sourceDir}");
        }

        Directory.CreateDirectory(destinationDir);

        foreach (var file in dir.GetFiles())
        {
            var targetFilePath = Path.Combine(destinationDir, file.Name);
            file.CopyTo(targetFilePath, overwrite: true);
        }

        foreach (var subDir in dir.GetDirectories())
        {
            var newDestinationDir = Path.Combine(destinationDir, subDir.Name);
            CopyDirectory(subDir.FullName, newDestinationDir);
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
            service.Refresh();

            _logger.LogInformation("Watchdog service current status: {Status}", service.Status);

            // We expect the service to be stopped at this point (since we explicitly stopped it before updates)
            if (service.Status == ServiceControllerStatus.Running)
            {
                _logger.LogError("Watchdog service is already running - cannot verify update was applied! Service may have been started externally or stop operation failed. Triggering rollback for safety.");
                throw new InvalidOperationException(
                    "Watchdog service is already running when attempting to start after update. " +
                    "Cannot verify update integrity. This may indicate the service was started externally or stop failed.");
            }

            if (service.Status == ServiceControllerStatus.Stopped)
            {
                _logger.LogInformation("Starting watchdog service...");
                service.Start();
            }
            else if (service.Status == ServiceControllerStatus.Paused)
            {
                _logger.LogWarning("Watchdog service is paused (expected Stopped) - attempting to continue...");
                service.Continue();
            }
            else
            {
                _logger.LogInformation("Watchdog service is in transitional state: {Status}. Waiting for it to stabilize...", service.Status);
            }

            service.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(30));
            _logger.LogInformation("Watchdog service started successfully");
        }
        catch (System.ServiceProcess.TimeoutException ex)
        {
            _logger.LogError(ex, "Timeout waiting for watchdog service to start (waited 30 seconds)");
            throw new InvalidOperationException("Watchdog service failed to start within timeout period", ex);
        }
        catch (InvalidOperationException)
        {
            // Re-throw InvalidOperationException as-is (including our "already running" check)
            throw;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error starting watchdog service");
            throw;
        }
    }

    private void VerifyWatchdogRunning()
    {
        try
        {
            using var service = new ServiceController(_watchdogServiceName);
            service.Refresh();

            if (service.Status != ServiceControllerStatus.Running)
            {
                _logger.LogError("Watchdog service is not running after update. Status: {Status}", service.Status);
                throw new InvalidOperationException($"Watchdog service failed to start. Status: {service.Status}");
            }

            _logger.LogInformation("Verified watchdog service is running");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error verifying watchdog service status");
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
