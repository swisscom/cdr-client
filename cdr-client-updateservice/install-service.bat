@echo off
setlocal
REM Installation script for curaLINE Client Update Service
REM Run this script as Administrator after installing the main curaLINE Client.
REM Assumes CuraLineClientUpdateService.exe sits next to this script.

REM Configuration - change this to customize the service name
set "SERVICE_NAME=curaLINEClientUpdateService"
set "DISPLAY_NAME=curaLINE Client Update Service"
set "DESCRIPTION=Automatically manages updates for curaLINE Client on Windows Server"
set "SERVICE_PATH=%~dp0CuraLineClientUpdateService.exe"

REM Reading the LocalSystem registry hive succeeds only for elevated processes.
reg query "HKU\S-1-5-19" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Administrator privileges are required.
    echo Right-click this script and choose "Run as administrator".
    goto :fail
)

if not exist "%SERVICE_PATH%" (
    echo ERROR: Update service executable not found: %SERVICE_PATH%
    echo Please ensure CuraLineClientUpdateService.exe is next to this script.
    goto :fail
)

echo Installing %DISPLAY_NAME%...

REM Remove any previous installation. Both commands fail on a clean machine,
REM which is expected and therefore not treated as an error.
sc stop "%SERVICE_NAME%" >nul 2>&1
if not errorlevel 1 (
    REM A stop was actually initiated. Give the SCM a moment to finish it,
    REM otherwise the delete below only marks the service for deletion.
    echo Stopping the running service...
    ping -n 6 127.0.0.1 >nul
)
sc delete "%SERVICE_NAME%" >nul 2>&1

sc create "%SERVICE_NAME%" ^
    binPath= "\"%SERVICE_PATH%\" --serviceName \"%SERVICE_NAME%\"" ^
    start= auto ^
    DisplayName= "%DISPLAY_NAME%"
if errorlevel 1 (
    echo.
    echo ERROR: Failed to create the service.
    echo If sc reported "marked for deletion", close the Services console and any
    echo open service property dialogs, then run this script again.
    goto :fail
)

sc description "%SERVICE_NAME%" "%DESCRIPTION%"
if errorlevel 1 echo WARNING: Could not set the service description.

echo Starting service...
sc start "%SERVICE_NAME%"
if errorlevel 1 (
    echo.
    echo ERROR: Failed to start the service.
    echo Check the Windows event log ^(source "%SERVICE_NAME%"^) for details.
    goto :fail
)

REM sc start returns while the service is still START_PENDING, so wait briefly
REM before reporting the state.
ping -n 4 127.0.0.1 >nul

echo.
sc query "%SERVICE_NAME%"
echo.
echo %SERVICE_NAME% installed. Confirm that STATE above reads RUNNING.
echo.
pause
exit /b 0

:fail
echo.
echo Installation did not complete successfully.
echo.
pause
exit /b 1
