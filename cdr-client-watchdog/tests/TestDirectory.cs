internal sealed class TestDirectory : IDisposable
{
    public string Root { get; } = Path.Combine(Path.GetTempPath(), $"watchdog-tests-{Guid.NewGuid():N}");

    public TestDirectory() => Directory.CreateDirectory(Root);

    public string CreateFile(string relativePath)
    {
        var path = Path.Combine(Root, relativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, "");
        return path;
    }

    public void Dispose() => Directory.Delete(Root, recursive: true);
}
