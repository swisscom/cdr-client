@echo off
REM Build script for curaLINE Client update service
REM This script builds the C# update service and prepares it for distribution

echo Building curaLINE Client update service...

REM Clean previous build
if exist publish rmdir /s /q publish

REM Build and publish
dotnet publish -c Release -r win-x64 --self-contained false -o publish

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

echo Build completed successfully!
echo Output: publish/

