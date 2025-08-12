@echo off
REM Register CDRClientWatchdog Event Log Source using proper .NET APIs
REM Run this script as Administrator

echo Registering CDRClientWatchdog Event Log Source...

REM Use PowerShell to properly manage Event Log sources
powershell -ExecutionPolicy Bypass -Command "& { try { if ([System.Diagnostics.EventLog]::SourceExists('CDRClientWatchdog')) { Write-Host 'Event log source CDRClientWatchdog already exists.' } else { [System.Diagnostics.EventLog]::CreateEventSource('CDRClientWatchdog', 'Application'); Write-Host 'Event log source CDRClientWatchdog created successfully.' } } catch { Write-Host 'Error: Make sure you are running as Administrator.' -ForegroundColor Red } }"

if %ERRORLEVEL% == 0 (
    echo Event log source registration completed successfully.
) else (
    echo Warning: Failed to register event log source. Make sure you're running as Administrator.
)

echo.
echo All log entries will now appear under "CDRClientWatchdog" in Windows Event Viewer.
echo.
pause
