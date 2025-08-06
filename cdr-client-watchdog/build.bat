@echo off
REM Build the CDR Client Watchdog Service
REM This script builds the service for Windows x64

echo Building CDR Client Watchdog Service...

REM Check if .NET SDK is available
dotnet --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Error: .NET SDK is not installed or not in PATH.
    echo Please install .NET 8.0 SDK or later.
    pause
    exit /b 1
)

REM Build the project
echo Building for Windows x64...
dotnet publish -c Release -r win-x64 --self-contained false -o .\publish

if %ERRORLEVEL% == 0 (
    echo.
    echo Build completed successfully!
    echo Output directory: .\publish
    echo.
    echo To install the service:
    echo 1. Copy the contents of the 'publish' folder to your desired installation directory
    echo 2. Update the ServiceExecutablePath in appsettings.json to point to your cdr-client-service.exe
    echo 3. Run install-service.bat as Administrator
) else (
    echo Build failed!
)

echo.
pause
