@echo off
REM Install CDRClientWatchdog Service
REM Run this script as Administrator

echo Installing CDRClientWatchdog Service...

REM Stop service if it exists
sc stop "CDRClientWatchdog" >nul 2>&1

REM Delete service if it exists
sc delete "CDRClientWatchdog" >nul 2>&1

REM Create the service
sc create "CDRClientWatchdog" ^
    binPath= "\"%~dp0watchdog\CdrClientWatchdog.exe\"" ^
    start= auto ^
    DisplayName= "CDRClientWatchdog Service" ^
    description= "Monitors and restarts CDR Client service when it fails"

if %ERRORLEVEL% == 0 (
    echo Service installed successfully.
    
    REM Start the service
    echo Starting service...
    sc start "CDRClientWatchdog"
    
    if %ERRORLEVEL% == 0 (
        echo Service started successfully.
        echo.
        echo The CDRClientWatchdog service is now monitoring your CDR Client service.
        echo It will automatically restart the service if it exits with a non-zero exit code.
    ) else (
        echo Warning: Service installed but failed to start. You may need to start it manually.
    )
) else (
    echo Error: Failed to install service. Make sure you're running as Administrator.
)

echo.
pause
