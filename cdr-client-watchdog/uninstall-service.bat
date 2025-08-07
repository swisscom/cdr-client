@echo off
REM Uninstall CDRClientWatchdog Service
REM Run this script as Administrator

echo Uninstalling CDRClientWatchdog Service...

REM Check if service exists
sc query "CDRClientWatchdog" >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Service "CDRClientWatchdog" is not installed.
    goto :END
)

REM Stop the service
echo Stopping service...
sc stop "CDRClientWatchdog" >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo Service stopped successfully.
) else (
    echo Service was not running or failed to stop.
)

REM Wait a moment for the service to stop completely
echo Waiting for service to stop completely...
timeout /t 5 /nobreak >nul

REM Force kill any remaining processes
echo Checking for remaining processes...
taskkill /F /IM "CdrClientWatchdog.exe" >nul 2>&1

REM Delete the service
echo Removing service...
sc delete "CDRClientWatchdog"

if %ERRORLEVEL% == 0 (
    echo Service uninstalled successfully.
) else (
    echo Warning: Error uninstalling service. Trying alternative method...
    
    REM Alternative method using reg delete (sometimes needed for stubborn services)
    reg delete "HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\CDRClientWatchdog" /f >nul 2>&1
    if %ERRORLEVEL% == 0 (
        echo Service registry entry removed.
    )
)

REM Verify service is gone
echo Verifying service removal...
sc query "CDRClientWatchdog" >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Service successfully removed.
) else (
    echo Warning: Service may still be present. A reboot may be required.
)

:END
echo.
pause
