@echo off
setlocal enabledelayedexpansion

echo Please input tenant-id:
set /p tenantId=
powershell -Command "(Get-Content %1) -replace 'tenant-id: .*', 'tenant-id: %tenantId%' | Set-Content %1"
echo tenant-id updated in %1

echo Please input client-id:
set /p clientId=
powershell -Command "(Get-Content %1) -replace 'client-id: .*', 'client-id: %clientId%' | Set-Content %1"
echo client-id updated in %1

echo Please input client-secret:
set /p clientSecret=
powershell -Command "(Get-Content %1) -replace 'client-secret: .*', 'client-secret: %clientSecret%' | Set-Content %1"
echo client-secret updated in %1

echo restart the application
