@echo off
setlocal

set "batchFilePath=%~dp0"

set "pidFile=%batchFilePath%..\..\logs\client.pid"

set /p PID=<%pidFile%
taskkill /F /PID %PID%

exit /b 0

endlocal