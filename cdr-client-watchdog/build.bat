@echo off
REM Build script for CDR Client Watchdog Service
REM Usage: build.bat [SELF_CONTAINED] [CONFIGURATION] [RUNTIME]
REM   SELF_CONTAINED: true/false (default: true)
REM   CONFIGURATION: Release/Debug (default: Release)  
REM   RUNTIME: win-x64/win-x86 (default: win-x64)

set "SELF_CONTAINED=%~1"
set "CONFIGURATION=%~2"
set "RUNTIME=%~3"

if "%SELF_CONTAINED%"=="" set "SELF_CONTAINED=true"
if "%CONFIGURATION%"=="" set "CONFIGURATION=Release"
if "%RUNTIME%"=="" set "RUNTIME=win-x64"

echo Building CDR Client Watchdog Service...

REM Check if .NET SDK is available - prefer local (if called from Gradle), fall back to system
set "DOTNET_PATH=..\build\dotnet-sdk\dotnet.exe"
if exist "%DOTNET_PATH%" (
    echo Using local .NET SDK ^(downloaded by Gradle^)
) else (
    set "DOTNET_PATH=dotnet"
    echo Using system .NET SDK
)

REM Test the dotnet command
"%DOTNET_PATH%" --version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Error: .NET SDK command failed.
    echo Please either:
    echo   1. Install .NET 8.0 SDK system-wide, or
    echo   2. Run 'gradlew buildWatchdog' to auto-download a local SDK
    pause
    exit /b 1
)

REM Get and display version
for /f "delims=" %%i in ('"%DOTNET_PATH%" --version 2^>nul') do set "DOTNET_VERSION=%%i"
echo Using .NET SDK version: %DOTNET_VERSION%

REM Clean previous builds
if exist "publish" (
    rmdir /s /q "publish"
    echo Cleaned previous build artifacts
)

REM Build the project
echo Building for %RUNTIME% ^(Self-contained: %SELF_CONTAINED%^)...
"%DOTNET_PATH%" publish -c "%CONFIGURATION%" -r "%RUNTIME%" --self-contained %SELF_CONTAINED% -o publish --verbosity minimal

if %ERRORLEVEL% == 0 (
    echo.
    echo Build completed successfully!
    echo Output directory: %cd%\publish
    echo.
    echo To integrate with Conveyor:
    echo 1. The conveyor.conf has been updated to include the watchdog service
    echo 2. Run your Conveyor build process normally
    echo 3. The watchdog will be included in the Windows installation
    echo 4. Use 'install-watchdog.bat' to install the service after deployment
) else (
    echo Build failed!
    exit /b 1
)

echo.
echo Build script completed.
pause
