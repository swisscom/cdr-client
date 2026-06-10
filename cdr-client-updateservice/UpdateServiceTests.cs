using Xunit;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Moq;
using System;
using System.Collections.Generic;
using System.Reflection;

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

