@echo off
setlocal enabledelayedexpansion
REM Uninstallation script for curaLINE Client update service
REM Run this script as Administrator to remove the update service

REM Configuration - change this to customize the service name (reflect what was used during installation)
set SERVICE_NAME=curaLINEClientUpdateService

echo Uninstalling curaLINE Client update service...

REM Stop the service
echo Stopping service...
sc stop "!SERVICE_NAME!"

REM Wait a bit for service to stop
timeout /t 3 /nobreak >nul

REM Delete the service
echo Removing service...
sc delete "!SERVICE_NAME!"

if %ERRORLEVEL% NEQ 0 (
    echo Failed to remove service!
    exit /b %ERRORLEVEL%
)

echo Service uninstalled successfully!
echo.
echo Note: Event log source '!SERVICE_NAME!' was not removed.
echo This is intentional to preserve historical logs.
echo.
pause

