using Microsoft.Extensions.Logging;
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

#nullable enable

namespace CdrClientWatchdog;

/// <summary>
/// Asks a process to terminate gracefully, i.e. the Windows counterpart of SIGTERM.
/// </summary>
internal interface IShutdownSignal
{
    /// <summary>
    /// Adjusts <paramref name="startInfo"/> so that the managed process can receive the signal.
    /// Must be called right before the managed process is started.
    /// </summary>
    void PrepareChildProcessStart(ProcessStartInfo startInfo);

    /// <returns>
    /// A scope that must be disposed once the process has exited or the wait for it has been abandoned,
    /// or <c>null</c> if the signal could not be sent.
    /// </returns>
    IDisposable? Send(int processId);
}

internal sealed class DisabledShutdownSignal : IShutdownSignal
{
    public static readonly DisabledShutdownSignal Instance = new();
    public void PrepareChildProcessStart(ProcessStartInfo startInfo) { }
    public IDisposable? Send(int processId) => null;
}

/// <summary>
/// Sends Ctrl+C to the managed process on Windows, which the JVM handles like SIGINT by running its shutdown hooks.
/// On other platforms (development and tests only) SIGTERM is sent instead.
/// </summary>
/// <remarks>
/// Ctrl+C can only be sent to processes that share the console of the sender. A service has no console, and a child
/// started with CREATE_NO_WINDOW has none either. The watchdog therefore allocates a hidden console and lets the
/// managed process inherit it.
/// </remarks>
internal sealed class ConsoleShutdownSignal : IShutdownSignal
{
    private const uint CtrlCEvent = 0;
    private const int SigTerm = 15;
    private const int ErrorAccessDenied = 5;
    private const int SwHide = 0;

    // The console and its Ctrl+C handling are process-wide state.
    private static readonly SemaphoreSlim ConsoleGate = new(1, 1);
    private readonly ILogger _logger;
    private bool _hasConsole;

    public ConsoleShutdownSignal(ILogger logger) => _logger = logger;

    public void PrepareChildProcessStart(ProcessStartInfo startInfo)
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        _hasConsole = EnsureConsole();
        if (!_hasConsole)
        {
            return;
        }

        startInfo.CreateNoWindow = false;
        // The "ignore Ctrl+C" flag is inherited by child processes
        if (!SetConsoleCtrlHandler(IntPtr.Zero, false))
        {
            _logger.LogWarning("Could not enable Ctrl+C handling for the curaLINE Client service (Win32 error {Error})",
                Marshal.GetLastWin32Error());
        }
    }

    private bool EnsureConsole()
    {
        if (AllocConsole())
        {
            var window = GetConsoleWindow();
            if (window != IntPtr.Zero)
            {
                ShowWindow(window, SwHide);
            }
            _logger.LogInformation("Allocated a hidden console for the curaLINE Client service");
            return true;
        }

        var error = Marshal.GetLastWin32Error();
        // Access denied: the watchdog already has a console, e.g. when it runs interactively.
        if (error == ErrorAccessDenied)
        {
            return true;
        }

        _logger.LogWarning("Could not allocate a console for the curaLINE Client service; it cannot be shut down gracefully (Win32 error {Error})",
            error);
        return false;
    }

    public IDisposable? Send(int processId) => OperatingSystem.IsWindows() ? SendCtrlC(processId) : SendSigTerm(processId);

    private IDisposable? SendCtrlC(int processId)
    {
        if (!_hasConsole)
        {
            _logger.LogWarning("The curaLINE Client service (PID: {ProcessId}) does not share a console with the watchdog; Ctrl+C cannot be sent",
                processId);
            return null;
        }

        ConsoleGate.Wait();
        // Ctrl+C is delivered to every process attached to the console, including the watchdog itself.
        if (!SetConsoleCtrlHandler(IntPtr.Zero, true))
        {
            _logger.LogWarning("Could not protect the watchdog from its own Ctrl+C (Win32 error {Error})", Marshal.GetLastWin32Error());
            ConsoleGate.Release();
            return null;
        }

        if (!GenerateConsoleCtrlEvent(CtrlCEvent, 0))
        {
            _logger.LogWarning("Could not send Ctrl+C to the curaLINE Client service (PID: {ProcessId}, Win32 error {Error})",
                processId, Marshal.GetLastWin32Error());
            StopIgnoringCtrlC();
            return null;
        }

        // The event is delivered asynchronously; keep ignoring Ctrl+C until the caller is done waiting.
        return new Scope(StopIgnoringCtrlC);
    }

    private static void StopIgnoringCtrlC()
    {
        try
        {
            SetConsoleCtrlHandler(IntPtr.Zero, false);
        }
        finally
        {
            ConsoleGate.Release();
        }
    }

    private IDisposable? SendSigTerm(int processId)
    {
        if (kill(processId, SigTerm) == 0)
        {
            return new Scope(() => { });
        }

        _logger.LogWarning("Could not send SIGTERM to the curaLINE Client service (PID: {ProcessId}, errno {Error})",
            processId, Marshal.GetLastPInvokeError());
        return null;
    }

    private sealed class Scope(Action dispose) : IDisposable
    {
        private Action? _dispose = dispose;
        public void Dispose() => Interlocked.Exchange(ref _dispose, null)?.Invoke();
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AllocConsole();

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetConsoleWindow();

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetConsoleCtrlHandler(IntPtr handlerRoutine, bool add);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GenerateConsoleCtrlEvent(uint dwCtrlEvent, uint dwProcessGroupId);

    [DllImport("libc", SetLastError = true)]
    private static extern int kill(int pid, int sig);
}
