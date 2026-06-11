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
        try
        {
            _logger.LogInformation("curaLINEClientUpdateService started. Check interval: {Interval} hours", _updateCheckInterval.TotalHours);

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
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // Graceful shutdown requested — this is expected, do not log as error
            _logger.LogInformation("curaLINEClientUpdateService shutting down gracefully");
        }
        catch (Exception ex)
        {
            // Unhandled exception in the service loop — log critical and request stop
            // Without this, BackgroundService transitions to Faulted but the process stays alive doing nothing
            _logger.LogCritical(ex, "Unrecoverable error in update service loop. Requesting application stop.");
            _hostLifetime.StopApplication();
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
            _logger.LogInformation("Checking for updates from update site...");
            _logger.LogDebug("Current versions - Service: {Service}, Watchdog: {Watchdog}",
                _currentVersions.GetValueOrDefault("Service", "unknown"),
                _currentVersions.GetValueOrDefault("Watchdog", "unknown"));

            var manifestInfo = await GetLatestReleaseFromManifest(cancellationToken);
            if (manifestInfo == null)
            {
                _logger.LogWarning("Could not retrieve latest release information from update site");
                return;
            }

            var remoteVersion = manifestInfo.version;
            var currentServiceVersion = _currentVersions.GetValueOrDefault("Service", "1.0.0");

            _logger.LogInformation("Latest release: {Remote} (current service: {Current})",
                remoteVersion, currentServiceVersion);

            // Check if version is pinned
            if (!string.IsNullOrEmpty(_pinnedVersion) && !IsNewerVersion(_pinnedVersion, currentServiceVersion))
            {
                _logger.LogInformation("Version pinned to {PinnedVersion}. Skipping update check.", _pinnedVersion);
                return;
            }
            else if (!string.IsNullOrEmpty(_pinnedVersion) && IsNewerVersion(_pinnedVersion, currentServiceVersion))
            {
                _logger.LogInformation("Pinned version {PinnedVersion} is newer than current service version {Current}. Checking if pinned version is available in release manifest...",
                    _pinnedVersion, currentServiceVersion);

                if (manifestInfo.version != _pinnedVersion)
                {
                    _logger.LogWarning("Pinned version {PinnedVersion} does not match latest release version {Remote}. Skipping update check.",
                        _pinnedVersion, manifestInfo.version);
                    return;
                }
                _logger.LogInformation("Pinned version {PinnedVersion} matches latest release. Proceeding with update check...", _pinnedVersion);
            }

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
        catch (OperationCanceledException)
        {
            // Propagate cancellation — the caller (ExecuteAsync) handles graceful shutdown
            throw;
        }
        catch (Exception ex)
        {
            // Log and continue — transient failures (network, IO) should not crash the service.
            // The next polling cycle will retry.
            _logger.LogError(ex, "Error during update check. Will retry at next interval.");
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

            _logger.LogDebug("Latest version from update site: {Version}", latestInfo.version);

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
        catch (OperationCanceledException)
        {
            // Propagate cancellation for graceful shutdown
            throw;
        }
        catch (HttpRequestException ex)
        {
            // Network errors are transient — log and return null so the caller retries next cycle
            _logger.LogWarning(ex, "Network error fetching latest release from update site");
            return null;
        }
        catch (JsonException ex)
        {
            // Malformed JSON from the server — log and return null
            _logger.LogWarning(ex, "Failed to parse release manifest JSON");
            return null;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unexpected error fetching latest release from update site");
            return null;
        }
    }

    private bool IsNewerVersion(string newVersion, string currentVersion)
    {
        try
        {
            // Handle semantic versioning with suffixes like "1.0.0-SNAPSHOT"
            // Strip suffix and compare base versions, then consider suffix if base versions are equal
            var (newBase, newSuffix) = ParseSemanticVersion(newVersion);
            var (currentBase, currentSuffix) = ParseSemanticVersion(currentVersion);

            var newV = Version.Parse(newBase);
            var current = Version.Parse(currentBase);

            // If base versions differ, that determines the result
            if (newV > current) return true;
            if (newV < current) return false;

            // Base versions are equal - check suffixes
            // A version without suffix (release) is newer than one with suffix (pre-release)
            // e.g., "1.0.0" > "1.0.0-SNAPSHOT"
            if (string.IsNullOrEmpty(newSuffix) && !string.IsNullOrEmpty(currentSuffix))
                return true;  // New version is release, current is pre-release
            if (!string.IsNullOrEmpty(newSuffix) && string.IsNullOrEmpty(currentSuffix))
                return false; // New version is pre-release, current is release

            // Both have suffixes or both don't - they're equal
            return false;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to compare versions: '{NewVersion}' vs '{CurrentVersion}'. Treating as no update available.", newVersion, currentVersion);
            return false;
        }
    }

    private (string baseVersion, string? suffix) ParseSemanticVersion(string version)
    {
        // Handle versions like "1.0.0-SNAPSHOT" by splitting on the first hyphen
        var parts = version.Split(new[] { '-' }, 2);
        return parts.Length == 2
            ? (parts[0], parts[1])
            : (version, null);
    }

    private async Task ApplyUpdates(ManifestInfo manifest, string newVersion, CancellationToken cancellationToken)
    {
        var updatedComponents = new List<string>();
        var backupPath = Path.Combine(_installationPath, $"backup-{DateTime.UtcNow:yyyyMMdd-HHmmss}");
        var downloadedFiles = new Dictionary<string, string>();

        try
        {
            // Step 1: Download and verify ALL artifacts first (don't touch anything yet)
            // This step is cancellable - if cancelled, no files have been modified
            _logger.LogInformation("Downloading and verifying artifacts...");

            foreach (var manifestArtifact in manifest.artifacts)
            {
                // Check for cancellation at safe checkpoint (before downloading)
                cancellationToken.ThrowIfCancellationRequested();

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

            // Final cancellation check before entering critical section
            cancellationToken.ThrowIfCancellationRequested();

            _logger.LogInformation("All artifacts downloaded and verified successfully. Proceeding with update...");

            // ============================================================================
            // CRITICAL SECTION: Beyond this point, operations should NOT be cancelled
            // to prevent partial updates. We must complete the update or rollback.
            // ============================================================================

            // Step 2: Create backup of current files
            _logger.LogInformation("Creating backup at: {BackupPath}", backupPath);
            CreateBackup(backupPath, downloadedFiles.Keys);

            // Step 3: Stop the watchdog service (which stops the CDR service)
            _logger.LogInformation("Stopping watchdog service: {ServiceName}", _watchdogServiceName);
            StopWatchdogService();

            // Verify service is actually stopped before proceeding
            VerifyWatchdogStopped();

            // Step 4: Apply updates for each component
            foreach (var (componentName, downloadedPath) in downloadedFiles)
            {
                try
                {
                    await ApplyComponentUpdate(componentName, downloadedPath);
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
            // This can be cancelled (if shutdown requested after update), but update is complete
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(10), cancellationToken);
                VerifyWatchdogRunning();
            }
            catch (OperationCanceledException)
            {
                _logger.LogWarning("Service verification cancelled, but update completed successfully");
            }

            _logger.LogInformation("Update process completed successfully! Updated components: {Components}",
                string.Join(", ", updatedComponents));

            // Step 8: Cleanup - delete old backups and downloaded files
            CleanupOldBackups();
            CleanupDownloadedFiles(downloadedFiles);
        }
        catch (OperationCanceledException)
        {
            _logger.LogWarning("Update operation cancelled. Updated components: {Count}", updatedComponents.Count);

            // If we were cancelled during download phase (updatedComponents is empty), just cleanup
            if (updatedComponents.Count == 0)
            {
                _logger.LogInformation("Cancellation occurred before any file modifications - safe to abort");
                CleanupDownloadedFiles(downloadedFiles);
            }
            else
            {
                // This shouldn't happen due to our non-cancellable critical section,
                // but handle it defensively
                _logger.LogError("Cancellation occurred after file modifications - triggering rollback");
                HandleUpdateFailure(
                    new InvalidOperationException("Update cancelled after partial modifications"),
                    updatedComponents,
                    backupPath,
                    downloadedFiles);
            }
            throw; // Re-throw to exit the update check loop
        }
        catch (Exception ex)
        {
            HandleUpdateFailure(ex, updatedComponents, backupPath, downloadedFiles);
        }
    }

    private void CleanupDownloadedFiles(Dictionary<string, string> downloadedFiles)
    {
        foreach (var path in downloadedFiles.Values)
        {
            try
            {
                if (File.Exists(path))
                {
                    File.Delete(path);
                }
            }
            catch (Exception cleanupEx)
            {
                _logger.LogWarning(cleanupEx, "Failed to delete temp file: {Path}", path);
            }
        }
    }

    private void HandleUpdateFailure(
        Exception ex,
        List<string> updatedComponents,
        string backupPath,
        Dictionary<string, string> downloadedFiles)
    {
        _logger.LogError(ex, "Update failed at stage: {Stage}",
            updatedComponents.Count == 0 ? "before file modifications" : $"after updating {updatedComponents.Count} component(s)");

        try
        {
            if (updatedComponents.Count > 0)
            {
                // Files were modified - need to restore from backup
                _logger.LogWarning("Attempting rollback from backup...");
                RestoreFromBackup(backupPath);
                StartWatchdogService();
                _logger.LogInformation("Rollback successful. System restored to previous state.");
            }
            else
            {
                // Failed before modifying any files - no restore needed, just cleanup
                _logger.LogInformation("No files were modified before failure - no restore needed. Cleaning up backup...");
                if (Directory.Exists(backupPath))
                {
                    try
                    {
                        Directory.Delete(backupPath, recursive: true);
                        _logger.LogInformation("Backup directory cleaned up: {BackupPath}", backupPath);
                    }
                    catch (Exception cleanupEx)
                    {
                        _logger.LogWarning(cleanupEx, "Failed to delete backup directory (non-critical): {BackupPath}", backupPath);
                    }
                }

                // Ensure watchdog is running (might have been stopped)
                using (var service = new ServiceController(_watchdogServiceName))
                {
                    service.Refresh();
                    if (service.Status != ServiceControllerStatus.Running)
                    {
                        _logger.LogInformation("Restarting watchdog service after failed update...");
                        StartWatchdogService();
                    }
                }
            }

            // Cleanup downloaded temp files regardless of restore/cleanup path
            CleanupDownloadedFiles(downloadedFiles);
        }
        catch (Exception rollbackEx)
        {
            _logger.LogCritical(rollbackEx, "ROLLBACK/CLEANUP FAILED! Manual intervention required. Backup location: {BackupPath}", backupPath);
        }
    }

    private async Task ApplyComponentUpdate(string componentName, string downloadedFilePath)
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

                // Delete the contents of the directory to remove old files
                _logger.LogDebug("Deleting contents of directory: {TargetPath}", targetPath);
                DeleteDirectoryContentsWithRetry(targetPath);
                _logger.LogDebug("Deleted old files to ensure clean update");
            }
            else
            {
                _logger.LogDebug("Target directory does not exist - this appears to be a fresh installation");
                Directory.CreateDirectory(targetPath);
            }

            // Step 2: Extract new files
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
        string? tempFilePath = null;
        try
        {
            _logger.LogDebug("Downloading from: {Url}", url);

            var response = await _httpClient.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                _logger.LogError("Download failed with status: {Status}", response.StatusCode);
                return null;
            }

            tempFilePath = Path.Combine(Path.GetTempPath(), $"cdr-update-{Guid.NewGuid()}-{fileName}");

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
        catch (OperationCanceledException)
        {
            // Cleanup partially downloaded file and propagate cancellation
            if (tempFilePath != null && File.Exists(tempFilePath))
            {
                try { File.Delete(tempFilePath); } catch { /* best effort cleanup */ }
            }
            throw;
        }
        catch (HttpRequestException ex)
        {
            _logger.LogError(ex, "Network error downloading file from: {Url}", url);
            if (tempFilePath != null && File.Exists(tempFilePath))
            {
                try { File.Delete(tempFilePath); } catch { /* best effort cleanup */ }
            }
            return null;
        }
        catch (IOException ex)
        {
            _logger.LogError(ex, "IO error downloading or verifying file from: {Url}", url);
            if (tempFilePath != null && File.Exists(tempFilePath))
            {
                try { File.Delete(tempFilePath); } catch { /* best effort cleanup */ }
            }
            return null;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unexpected error downloading or verifying file from: {Url}", url);
            if (tempFilePath != null && File.Exists(tempFilePath))
            {
                try { File.Delete(tempFilePath); } catch { /* best effort cleanup */ }
            }
            return null;
        }
    }

    private void DeleteDirectoryContentsWithRetry(string path, int maxRetries = 5)
    {
        if (!Directory.Exists(path))
        {
            _logger.LogDebug("Directory does not exist, nothing to clean: {Path}", path);
            return;
        }

        var tryCount = 0;
        while (tryCount < maxRetries)
        {
            tryCount++;
            try
            {
                var directory = new DirectoryInfo(path);

                // Delete all files in the directory
                foreach (var file in directory.GetFiles())
                {
                    file.Delete();
                }

                // Delete all subdirectories recursively
                foreach (var subDirectory in directory.GetDirectories())
                {
                    subDirectory.Delete(recursive: true);
                }

                _logger.LogDebug("Successfully deleted contents of directory: {Path}", path);
                return;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                if (tryCount == maxRetries)
                {
                    // Final attempt - let exception propagate
                    throw;
                }

                // File might still be locked by recently stopped process - wait and retry
                _logger.LogWarning("Failed to delete directory contents (attempt {Attempt}/{MaxRetries}): {Message}. Retrying in 2 seconds...",
                    tryCount, maxRetries, ex.Message);
                Thread.Sleep(TimeSpan.FromSeconds(2));
            }
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
                        DeleteDirectoryContentsWithRetry(targetPath);
                    }
                    else
                    {
                        Directory.CreateDirectory(targetPath);
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

    private void VerifyWatchdogStopped()
    {
        try
        {
            var maxWaitTime = TimeSpan.FromSeconds(60);
            var checkInterval = TimeSpan.FromSeconds(2);
            var stopwatch = Stopwatch.StartNew();

            while (stopwatch.Elapsed < maxWaitTime)
            {
                using var service = new ServiceController(_watchdogServiceName);
                service.Refresh();

                if (service.Status == ServiceControllerStatus.Stopped)
                {
                    _logger.LogInformation("Verified watchdog service is stopped - safe to proceed with file operations (verified after {Elapsed:F1}s)",
                        stopwatch.Elapsed.TotalSeconds);
                    return;
                }

                _logger.LogWarning("Watchdog service status is {Status} (expected Stopped). Waiting {CheckInterval}s before retry... ({Elapsed:F1}s elapsed)",
                    service.Status, checkInterval.TotalSeconds, stopwatch.Elapsed.TotalSeconds);

                Thread.Sleep(checkInterval);
            }

            // Final check after timeout
            using (var service = new ServiceController(_watchdogServiceName))
            {
                service.Refresh();
                _logger.LogError("Watchdog service is not stopped after {Timeout}s. Status: {Status}. Cannot safely perform file operations.",
                    maxWaitTime.TotalSeconds, service.Status);
                throw new InvalidOperationException($"Watchdog service must be stopped before updates. Current status after {maxWaitTime.TotalSeconds}s: {service.Status}");
            }
        }
        catch (InvalidOperationException)
        {
            // Re-throw our custom exception as-is
            throw;
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
        // Update in-memory version immediately so the next check cycle uses the correct version
        _currentVersions[componentName] = newVersion;

        try
        {
            var configPath = Path.Combine(AppContext.BaseDirectory, "appsettings.json");
            if (!File.Exists(configPath))
            {
                _logger.LogWarning("Could not find appsettings.json to update version");
                return;
            }

            var configContent = await File.ReadAllTextAsync(configPath);

            var options = new JsonSerializerOptions
            {
                WriteIndented = true,
                PropertyNameCaseInsensitive = true
            };

            using var jsonDoc = JsonDocument.Parse(configContent);
            using var stream = new MemoryStream();
            using (var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true }))
            {
                writer.WriteStartObject();

                foreach (var property in jsonDoc.RootElement.EnumerateObject())
                {
                    if (property.Name == "CurrentVersions")
                    {
                        writer.WriteStartObject("CurrentVersions");
                        foreach (var versionProperty in property.Value.EnumerateObject())
                        {
                            if (versionProperty.Name == componentName)
                            {
                                writer.WriteString(versionProperty.Name, newVersion);
                            }
                            else
                            {
                                versionProperty.WriteTo(writer);
                            }
                        }
                        writer.WriteEndObject();
                    }
                    else
                    {
                        property.WriteTo(writer);
                    }
                }

                writer.WriteEndObject();
            }

            var updatedContent = System.Text.Encoding.UTF8.GetString(stream.ToArray());
            await File.WriteAllTextAsync(configPath, updatedContent);
            _logger.LogInformation("Updated {Component} version in configuration to: {Version}", componentName, newVersion);
        }
        catch (OperationCanceledException)
        {
            // Propagate — should not silently swallow shutdown signals
            throw;
        }
        catch (Exception ex)
        {
            // Non-fatal: in-memory state is already updated, config file will be stale but
            // the service will still function correctly until next restart
            _logger.LogWarning(ex, "Could not persist version update to configuration file (in-memory state is correct)");
        }
    }

    #endregion
}
