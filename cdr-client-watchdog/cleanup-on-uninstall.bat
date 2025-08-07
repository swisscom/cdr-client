@echo off
REM This script is called automatically during uninstallation
REM It ensures the watchdog service is properly removed

REM Stop and remove the service silently
sc stop "CDR Client Watchdog" >nul 2>&1
timeout /t 3 /nobreak >nul
taskkill /F /IM "CdrClientWatchdog.exe" >nul 2>&1
sc delete "CDR Client Watchdog" >nul 2>&1

REM Clean up registry if needed
reg delete "HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\CDR Client Watchdog" /f >nul 2>&1

exit /b 0
