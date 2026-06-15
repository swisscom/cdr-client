@echo off
setlocal enabledelayedexpansion
REM Install curaLINEClientWatchdog Service
REM Run this script as Administrator

REM Configuration - change this to customize the service name
set SERVICE_NAME=CDRClientWatchdog
set DISPLAY_NAME=curaLINE Client Service
set DESCRIPTION=Monitors and restarts curaLINE Client service when it fails

echo Installing !DISPLAY_NAME!...

REM Determine the correct path to CdrClientWatchdog.exe
REM Check if it's in the current directory (manual installation)
if exist "%~dp0CdrClientWatchdog.exe" (
    set "WATCHDOG_PATH=%~dp0CdrClientWatchdog.exe"
    echo Detected manual installation: using !WATCHDOG_PATH!
) else if exist "%~dp0watchdog\CdrClientWatchdog.exe" (
    REM Check if it's in the watchdog subdirectory (Conveyor installation)
    set "WATCHDOG_PATH=%~dp0watchdog\CdrClientWatchdog.exe"
    echo Detected Conveyor installation: using !WATCHDOG_PATH!
) else (
    echo ERROR: CdrClientWatchdog.exe not found!
    echo Looked in:
    echo   - %~dp0CdrClientWatchdog.exe
    echo   - %~dp0watchdog\CdrClientWatchdog.exe
    echo.
    echo Please ensure CdrClientWatchdog.exe is in the correct location.
    pause
    exit /b 1
)

REM Stop service if it exists
sc stop "!SERVICE_NAME!" >nul 2>&1

REM Delete service if it exists
sc delete "!SERVICE_NAME!" >nul 2>&1

REM Create the service
sc create "!SERVICE_NAME!" ^
    binPath= "\"!WATCHDOG_PATH!\" --service-name !SERVICE_NAME!" ^
    start= auto ^
    DisplayName= "!DISPLAY_NAME!"

set "WAIT_SECONDS=7"

if %ERRORLEVEL% == 0 (
    echo Service installed successfully.

    echo Add description to service...
    sc description !SERVICE_NAME! !DESCRIPTION!
    
    REM Start the service
    echo Starting service...
    sc start !SERVICE_NAME! >nul 2>&1

    REM Wait a fixed number of seconds, then check once if the service is RUNNING
    REM Increase WAIT_SECONDS if your service needs more time to initialize on slower machines.
    echo "Waiting !WAIT_SECONDS! second(s) for the service to transition to RUNNING..."
    timeout /t !WAIT_SECONDS! >nul

    REM Check if the service reports RUNNING (case-insensitive)
    sc query "!SERVICE_NAME!" | findstr /I "RUNNING" >nul && (
        echo Service reached RUNNING state.
        echo.
        echo The !SERVICE_NAME! service is now monitoring your curaLINE Client service.
        echo It will automatically restart the service if it exits with a non-zero exit code.
    ) || (
        echo Warning: Service did not reach RUNNING within !WAIT_SECONDS! seconds. Manual check is required.
        REM Show current sc query output for debugging
        sc query "!SERVICE_NAME!"
    )
) else (
    echo Error: Failed to install service. Make sure you're running as Administrator.
)

echo.
pause
