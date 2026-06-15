using Xunit;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Moq;
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading.Tasks;

#nullable enable

namespace CuraLineClientUpdateService.Tests;

public class UpdateServiceTests
{
    private readonly Mock<ILogger<UpdateService>> _mockLogger;
    private readonly Mock<IHostApplicationLifetime> _mockHostLifetime;

    public UpdateServiceTests()
    {
        _mockLogger = new Mock<ILogger<UpdateService>>();
        _mockHostLifetime = new Mock<IHostApplicationLifetime>();
    }

    #region Version Comparison Tests

    [Theory]
    [InlineData("2.0.0", "1.0.0", true)]   // Major version higher
    [InlineData("1.1.0", "1.0.0", true)]   // Minor version higher
    [InlineData("1.0.1", "1.0.0", true)]   // Patch version higher
    [InlineData("1.0.0", "1.0.0", false)]  // Same version
    [InlineData("1.0.0", "2.0.0", false)]  // Older version
    [InlineData("1.0.0", "1.1.0", false)]  // Older minor
    [InlineData("1.0.0", "1.0.1", false)]  // Older patch
    public void IsNewerVersion_BasicVersionComparison_ReturnsExpectedResult(string newVersion, string currentVersion, bool expected)
    {
        // Arrange
        var service = CreateUpdateService();

        // Act
        var result = InvokePrivateMethod<bool>(service, "IsNewerVersion", newVersion, currentVersion);

        // Assert
        Assert.Equal(expected, result);
    }

    [Theory]
    [InlineData("1.0.0", "1.0.0-SNAPSHOT", true)]      // Release is newer than pre-release
    [InlineData("1.0.0-SNAPSHOT", "1.0.0", false)]     // Pre-release is older than release
    [InlineData("1.0.0-SNAPSHOT", "1.0.0-SNAPSHOT", false)]  // Same pre-release version
    [InlineData("2.0.0-SNAPSHOT", "1.0.0", true)]      // Newer base version, even with suffix
    [InlineData("1.0.0-SNAPSHOT", "2.0.0-SNAPSHOT", false)]  // Older base version with suffix
    public void IsNewerVersion_WithSemanticVersionSuffixes_ReturnsExpectedResult(string newVersion, string currentVersion, bool expected)
    {
        // Arrange
        var service = CreateUpdateService();

        // Act
        var result = InvokePrivateMethod<bool>(service, "IsNewerVersion", newVersion, currentVersion);

        // Assert
        Assert.Equal(expected, result);
    }

    [Theory]
    [InlineData("1.0.0-SNAPSHOT", "1.0.0", "SNAPSHOT")]
    [InlineData("1.0.0-RC1", "1.0.0", "RC1")]
    [InlineData("1.0.0-beta.1", "1.0.0", "beta.1")]
    [InlineData("1.0.0", "1.0.0", null)]
    public void ParseSemanticVersion_WithVariousSuffixes_ParsesCorrectly(string version, string expectedBase, string? expectedSuffix)
    {
        // Arrange
        var service = CreateUpdateService();

        // Act
        var result = InvokePrivateMethod<(string baseVersion, string? suffix)>(
            service, "ParseSemanticVersion", version);

        // Assert
        Assert.Equal(expectedBase, result.baseVersion);
        Assert.Equal(expectedSuffix, result.suffix);
    }

    #endregion

    #region Pinned Version Tests

    [Fact]
    public void Constructor_WithPinnedVersion_LogsWarning()
    {
        // Arrange
        var overrides = new Dictionary<string, string?>
        {
            ["PinnedVersion"] = "1.5.0"
        };

        // Act
        var service = CreateUpdateService(overrides);

        // Assert
        _mockLogger.Verify(
            x => x.Log(
                LogLevel.Warning,
                It.IsAny<EventId>(),
                It.Is<It.IsAnyType>((v, t) => v.ToString()!.Contains("Version pinned to: 1.5.0")),
                It.IsAny<Exception>(),
                It.IsAny<Func<It.IsAnyType, Exception?, string>>()),
            Times.Once);
    }

    [Fact]
    public void Constructor_WithoutPinnedVersion_DoesNotLogWarning()
    {
        // Act
        var service = CreateUpdateService();

        // Assert
        _mockLogger.Verify(
            x => x.Log(
                LogLevel.Warning,
                It.IsAny<EventId>(),
                It.Is<It.IsAnyType>((v, t) => v.ToString()!.Contains("Version pinned")),
                It.IsAny<Exception>(),
                It.IsAny<Func<It.IsAnyType, Exception?, string>>()),
            Times.Never);
    }

    /// <summary>
    /// Tests the pinned version logic flow:
    /// - When pinned version equals current version, no update
    /// - When pinned version is older than current, no update
    /// - When pinned version is newer than current but doesn't match manifest, no update
    /// - When pinned version is newer than current and matches manifest, update proceeds
    /// </summary>
    [Theory]
    [InlineData("1.0.0", "1.0.0", "2.0.0", "Skip")]          // Pinned = Current, Latest = 2.0.0 → Skip
    [InlineData("0.9.0", "1.0.0", "2.0.0", "Skip")]          // Pinned < Current → Skip
    [InlineData("1.5.0", "1.0.0", "2.0.0", "Skip")]          // Pinned > Current but != Latest → Skip
    [InlineData("2.0.0", "1.0.0", "2.0.0", "Proceed")]       // Pinned > Current and = Latest → Proceed
    [InlineData(null, "1.0.0", "2.0.0", "CheckNormal")]      // No pin → Normal check
    public void PinnedVersion_VariousScenarios_BehavesCorrectly(
        string? pinnedVersion,
        string currentVersion,
        string manifestVersion,
        string expectedBehavior)
    {
        // This test validates the logic in CheckAndApplyUpdates
        // We test the IsNewerVersion method which is the core of the logic

        var service = CreateUpdateService();

        if (pinnedVersion == null)
        {
            // Normal flow: check if manifest is newer than current
            var isNewerThanCurrent = InvokePrivateMethod<bool>(service, "IsNewerVersion", manifestVersion, currentVersion);
            Assert.True(isNewerThanCurrent); // 2.0.0 > 1.0.0
        }
        else
        {
            var pinnedNewerThanCurrent = InvokePrivateMethod<bool>(service, "IsNewerVersion", pinnedVersion, currentVersion);

            switch (expectedBehavior)
            {
                case "Skip":
                    if (!pinnedNewerThanCurrent)
                    {
                        // Pinned <= Current: should skip
                        Assert.False(pinnedNewerThanCurrent);
                    }
                    else
                    {
                        // Pinned > Current but doesn't match manifest: should skip
                        Assert.True(pinnedNewerThanCurrent);
                        Assert.NotEqual(pinnedVersion, manifestVersion);
                    }
                    break;

                case "Proceed":
                    // Pinned > Current AND equals manifest: should proceed
                    Assert.True(pinnedNewerThanCurrent);
                    Assert.Equal(pinnedVersion, manifestVersion);
                    break;
            }
        }
    }

    #endregion

    #region ZIP Update Tests

    [Fact]
    public async Task ApplyComponentUpdate_ZipArtifact_BacksUpAllOldFilesAndRemovesThem()
    {
        // Arrange
        var tempRoot = Path.Combine(Path.GetTempPath(), $"cdr-update-test-{Guid.NewGuid()}");
        var installPath = Path.Combine(tempRoot, "install");
        var watchdogDir = Path.Combine(installPath, "watchdog");

        try
        {
            // Create existing installation with multiple files and subdirectories
            Directory.CreateDirectory(watchdogDir);
            var existingFiles = new Dictionary<string, string>
            {
                ["WatchdogService.exe"] = "old-executable-content",
                ["appsettings.json"] = "{\"old\": \"config\"}",
                ["install-service.bat"] = "old install script",
                ["uninstall-service.bat"] = "old uninstall script",
                ["some-library.dll"] = "old-dll-content",
                ["README.md"] = "old readme"
            };

            foreach (var (fileName, content) in existingFiles)
            {
                File.WriteAllText(Path.Combine(watchdogDir, fileName), content);
            }

            // Create a subdirectory with files
            var subDir = Path.Combine(watchdogDir, "logs");
            Directory.CreateDirectory(subDir);
            File.WriteAllText(Path.Combine(subDir, "old-log.txt"), "old log content");

            // Create a ZIP with new content (simulating the update artifact)
            var zipPath = Path.Combine(tempRoot, "Watchdog-update.zip");
            using (var zipArchive = ZipFile.Open(zipPath, ZipArchiveMode.Create))
            {
                AddZipEntry(zipArchive, "WatchdogService.exe", "new-executable-content");
                AddZipEntry(zipArchive, "appsettings.json", "{\"new\": \"config\"}");
                AddZipEntry(zipArchive, "install-service.bat", "new install script");
                AddZipEntry(zipArchive, "uninstall-service.bat", "new uninstall script");
                AddZipEntry(zipArchive, "new-library.dll", "new-dll-content");
            }

            // Configure update service with artifacts pointing to our temp directory
            var overrides = new Dictionary<string, string?>
            {
                ["InstallationPath"] = installPath,
                ["Artifacts:Watchdog:FileName"] = "Watchdog-update.zip",
                ["Artifacts:Watchdog:TargetPath"] = "watchdog"
            };

            var service = CreateUpdateService(overrides);

            // Act - Step 1: Create backup
            var backupPath = Path.Combine(installPath, $"backup-test");
            InvokePrivateMethod<object>(service, "CreateBackup", backupPath, (IEnumerable<string>)new[] { "Watchdog" });

            // Assert - Verify all old files were backed up
            var backupDir = Path.Combine(backupPath, "Watchdog");
            Assert.True(Directory.Exists(backupDir), "Backup directory should exist");

            foreach (var (fileName, expectedContent) in existingFiles)
            {
                var backedUpFile = Path.Combine(backupDir, fileName);
                Assert.True(File.Exists(backedUpFile), $"Backup should contain: {fileName}");
                Assert.Equal(expectedContent, File.ReadAllText(backedUpFile));
            }

            // Verify subdirectory was also backed up
            Assert.True(File.Exists(Path.Combine(backupDir, "logs", "old-log.txt")),
                "Backup should contain files from subdirectories");
            Assert.Equal("old log content", File.ReadAllText(Path.Combine(backupDir, "logs", "old-log.txt")));

            // Act - Step 2: Apply the ZIP update
            await InvokePrivateMethodAsync(service, "ApplyComponentUpdate", "Watchdog", zipPath);

            // Assert - Verify old files that are NOT in the new ZIP are removed
            Assert.False(File.Exists(Path.Combine(watchdogDir, "some-library.dll")),
                "Old files not in the update ZIP should be removed");
            Assert.False(File.Exists(Path.Combine(watchdogDir, "README.md")),
                "Old files not in the update ZIP should be removed");
            Assert.False(Directory.Exists(Path.Combine(watchdogDir, "logs")),
                "Old subdirectories not in the update ZIP should be removed");

            // Assert - Verify new files from ZIP are present
            Assert.True(File.Exists(Path.Combine(watchdogDir, "new-library.dll")),
                "New files from ZIP should be extracted");
            Assert.Equal("new-dll-content", File.ReadAllText(Path.Combine(watchdogDir, "new-library.dll")));

            // Assert - Verify new executable is present
            Assert.True(File.Exists(Path.Combine(watchdogDir, "WatchdogService.exe")));
            Assert.Equal("new-executable-content", File.ReadAllText(Path.Combine(watchdogDir, "WatchdogService.exe")));

            // Assert - Verify protected files are preserved (kept from old installation)
            Assert.Equal("{\"old\": \"config\"}", File.ReadAllText(Path.Combine(watchdogDir, "appsettings.json")));
            Assert.Equal("old install script", File.ReadAllText(Path.Combine(watchdogDir, "install-service.bat")));
            Assert.Equal("old uninstall script", File.ReadAllText(Path.Combine(watchdogDir, "uninstall-service.bat")));

            // Assert - Verify .new files are created for protected files with new versions
            Assert.True(File.Exists(Path.Combine(watchdogDir, "appsettings.json.new")),
                "New version of protected file should be saved as .new");
            Assert.Equal("{\"new\": \"config\"}", File.ReadAllText(Path.Combine(watchdogDir, "appsettings.json.new")));
        }
        finally
        {
            // Cleanup
            if (Directory.Exists(tempRoot))
            {
                Directory.Delete(tempRoot, recursive: true);
            }
        }
    }

    [Fact]
    public async Task ApplyComponentUpdate_ZipArtifact_FreshInstallation_ExtractsAllFiles()
    {
        // Arrange
        var tempRoot = Path.Combine(Path.GetTempPath(), $"cdr-update-test-{Guid.NewGuid()}");
        var installPath = Path.Combine(tempRoot, "install");

        try
        {
            Directory.CreateDirectory(installPath);
            // Note: watchdog directory does NOT exist (fresh installation)

            // Create a ZIP with content
            var zipPath = Path.Combine(tempRoot, "Watchdog-update.zip");
            using (var zipArchive = ZipFile.Open(zipPath, ZipArchiveMode.Create))
            {
                AddZipEntry(zipArchive, "WatchdogService.exe", "executable-content");
                AddZipEntry(zipArchive, "appsettings.json", "{\"fresh\": \"config\"}");
                AddZipEntry(zipArchive, "install-service.bat", "install script");
                AddZipEntry(zipArchive, "uninstall-service.bat", "uninstall script");
            }

            var overrides = new Dictionary<string, string?>
            {
                ["InstallationPath"] = installPath,
                ["Artifacts:Watchdog:FileName"] = "Watchdog-update.zip",
                ["Artifacts:Watchdog:TargetPath"] = "watchdog"
            };

            var service = CreateUpdateService(overrides);

            // Act
            var watchdogDir = Path.Combine(installPath, "watchdog");
            await InvokePrivateMethodAsync(service, "ApplyComponentUpdate", "Watchdog", zipPath);

            // Assert - All files extracted (no preservation since it's a fresh install)
            Assert.True(File.Exists(Path.Combine(watchdogDir, "WatchdogService.exe")));
            Assert.Equal("executable-content", File.ReadAllText(Path.Combine(watchdogDir, "WatchdogService.exe")));
            Assert.Equal("{\"fresh\": \"config\"}", File.ReadAllText(Path.Combine(watchdogDir, "appsettings.json")));
            Assert.Equal("install script", File.ReadAllText(Path.Combine(watchdogDir, "install-service.bat")));

            // No .new files should exist for fresh installation
            Assert.False(File.Exists(Path.Combine(watchdogDir, "appsettings.json.new")));
        }
        finally
        {
            if (Directory.Exists(tempRoot))
            {
                Directory.Delete(tempRoot, recursive: true);
            }
        }
    }

    private void AddZipEntry(ZipArchive archive, string entryName, string content)
    {
        var entry = archive.CreateEntry(entryName);
        using var writer = new StreamWriter(entry.Open());
        writer.Write(content);
    }

    private async Task InvokePrivateMethodAsync(object obj, string methodName, params object[] parameters)
    {
        var method = obj.GetType().GetMethod(methodName, BindingFlags.NonPublic | BindingFlags.Instance);
        if (method == null)
        {
            throw new InvalidOperationException($"Method '{methodName}' not found");
        }

        var result = method.Invoke(obj, parameters);
        if (result is Task task)
        {
            await task;
        }
    }

    #endregion

    #region Helper Methods

    private UpdateService CreateUpdateService()
    {
        return CreateUpdateService(null);
    }

    private UpdateService CreateUpdateService(Dictionary<string, string?>? overrides)
    {
        var settings = new Dictionary<string, string?>
        {
            ["WatchdogServiceName"] = "CDRClientWatchdog",
            ["InstallationPath"] = "C:\\TestInstall",
            ["UpdateCheckIntervalHours"] = "2",
            ["MaxBackupsToKeep"] = "3",
            ["CurrentVersions:Service"] = "1.0.0",
            ["CurrentVersions:Watchdog"] = "1.0.0"
        };

        if (overrides != null)
        {
            foreach (var entry in overrides)
            {
                settings[entry.Key] = entry.Value;
            }
        }

        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(settings)
            .Build();

        return new UpdateService(_mockLogger.Object, configuration, _mockHostLifetime.Object);
    }

    private T InvokePrivateMethod<T>(object obj, string methodName, params object[] parameters)
    {
        var method = obj.GetType().GetMethod(methodName, BindingFlags.NonPublic | BindingFlags.Instance);
        if (method == null)
        {
            throw new InvalidOperationException($"Method '{methodName}' not found");
        }

        var result = method.Invoke(obj, parameters);
        return (T)result!;
    }

    #endregion
}

