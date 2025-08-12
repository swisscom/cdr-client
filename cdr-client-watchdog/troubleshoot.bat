@echo off
REM Troubleshooting script for CDRClientWatchdog Service
REM This script checks various aspects of the service configuration

echo CDRClientWatchdog Service Troubleshooting
echo ============================================
echo.

REM Show current directory
echo Current directory: %CD%
echo.

REM Show installation directory structure
echo Installation directory structure:
echo.
dir /B "%~dp0.." 2>nul
echo.

REM Look for CDR Client service executable
echo Searching for cdr-client-service.exe...
echo.

if exist "%~dp0..\..\bin\cdr-client-service.exe" (
    echo [FOUND] %~dp0..\..\bin\cdr-client-service.exe
) else (
    echo [NOT FOUND] %~dp0..\..\bin\cdr-client-service.exe
)

if exist "%~dp0..\cdr-client-service.exe" (
    echo [FOUND] %~dp0..\cdr-client-service.exe
) else (
    echo [NOT FOUND] %~dp0..\cdr-client-service.exe
)

if exist "%~dp0..\..\cdr-client-service.exe" (
    echo [FOUND] %~dp0..\..\cdr-client-service.exe
) else (
    echo [NOT FOUND] %~dp0..\..\cdr-client-service.exe
)

if exist "%~dp0..\bin\cdr-client-service.exe" (
    echo [FOUND] %~dp0..\bin\cdr-client-service.exe
) else (
    echo [NOT FOUND] %~dp0..\bin\cdr-client-service.exe
)

echo.

REM Check watchdog service configuration
echo Checking watchdog configuration...
echo.
if exist "%~dp0watchdog\appsettings.json" (
    echo Configuration file: %~dp0watchdog\appsettings.json
    echo Contents:
    type "%~dp0watchdog\appsettings.json"
) else (
    echo [ERROR] Configuration file not found: %~dp0watchdog\appsettings.json
)

echo.

REM Check service status
echo Checking service status...
sc query "CDRClientWatchdog" 2>nul
if %ERRORLEVEL% neq 0 (
    echo [INFO] CDRClientWatchdog service is not installed
) else (
    echo.
    echo Recent service events (if any):
    wevtutil qe Application /rd:true /f:text /c:5 /q:"*[System[Provider[@Name='CDRClientWatchdog']]]" 2>nul
)

echo.
echo Troubleshooting completed.
echo.
echo If the cdr-client-service.exe was not found, you may need to:
echo 1. Update the ServiceExecutablePath in appsettings.json
echo 2. Ensure the CDR Client application is properly installed
echo 3. Check the installation directory structure
echo.
pause
