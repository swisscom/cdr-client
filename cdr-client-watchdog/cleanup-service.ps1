# CDR Client Watchdog Service Cleanup Script
# This script ensures complete removal of the watchdog service during uninstallation

param(
    [switch]$Silent = $false
)

function Write-Log {
    param([string]$Message, [string]$Level = "Info")
    
    if (-not $Silent) {
        $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
        Write-Host "[$timestamp] [$Level] $Message"
    }
}

function Stop-WatchdogService {
    try {
        $service = Get-Service -Name "CDR Client Watchdog" -ErrorAction SilentlyContinue
        
        if ($service) {
            Write-Log "Found CDR Client Watchdog service in state: $($service.Status)"
            
            if ($service.Status -eq "Running") {
                Write-Log "Stopping CDR Client Watchdog service..."
                Stop-Service -Name "CDR Client Watchdog" -Force -ErrorAction SilentlyContinue
                
                # Wait for service to stop
                $timeout = 30
                $elapsed = 0
                while ($elapsed -lt $timeout) {
                    $service.Refresh()
                    if ($service.Status -eq "Stopped") {
                        Write-Log "Service stopped successfully"
                        break
                    }
                    Start-Sleep -Seconds 1
                    $elapsed++
                }
                
                if ($service.Status -ne "Stopped") {
                    Write-Log "Service did not stop within timeout, proceeding with force cleanup" "Warning"
                }
            }
        } else {
            Write-Log "CDR Client Watchdog service not found"
        }
    }
    catch {
        Write-Log "Error stopping service: $($_.Exception.Message)" "Error"
    }
}

function Remove-WatchdogService {
    try {
        # Use sc.exe for reliable service deletion
        $result = & sc.exe delete "CDR Client Watchdog" 2>&1
        
        if ($LASTEXITCODE -eq 0) {
            Write-Log "Service deleted successfully using sc.exe"
        } else {
            Write-Log "sc.exe delete failed: $result" "Warning"
            
            # Try alternative method using registry
            $servicePath = "HKLM:\SYSTEM\CurrentControlSet\Services\CDR Client Watchdog"
            if (Test-Path $servicePath) {
                Write-Log "Attempting registry-based removal..."
                Remove-Item -Path $servicePath -Recurse -Force -ErrorAction SilentlyContinue
                Write-Log "Registry entry removed"
            }
        }
    }
    catch {
        Write-Log "Error removing service: $($_.Exception.Message)" "Error"
    }
}

function Stop-WatchdogProcesses {
    try {
        $processes = Get-Process -Name "CdrClientWatchdog" -ErrorAction SilentlyContinue
        
        if ($processes) {
            Write-Log "Found $($processes.Count) CdrClientWatchdog process(es), terminating..."
            
            foreach ($process in $processes) {
                try {
                    $process.Kill()
                    Write-Log "Terminated process PID $($process.Id)"
                }
                catch {
                    Write-Log "Failed to terminate process PID $($process.Id): $($_.Exception.Message)" "Warning"
                }
            }
            
            # Wait for processes to exit
            Start-Sleep -Seconds 2
        }
    }
    catch {
        Write-Log "Error stopping processes: $($_.Exception.Message)" "Error"
    }
}

function Verify-Cleanup {
    try {
        # Check if service still exists
        $service = Get-Service -Name "CDR Client Watchdog" -ErrorAction SilentlyContinue
        if ($service) {
            Write-Log "Warning: Service still exists after cleanup" "Warning"
            return $false
        }
        
        # Check if processes are still running
        $processes = Get-Process -Name "CdrClientWatchdog" -ErrorAction SilentlyContinue
        if ($processes) {
            Write-Log "Warning: $($processes.Count) CdrClientWatchdog process(es) still running" "Warning"
            return $false
        }
        
        Write-Log "Cleanup verification successful"
        return $true
    }
    catch {
        Write-Log "Error during verification: $($_.Exception.Message)" "Error"
        return $false
    }
}

# Main execution
Write-Log "Starting CDR Client Watchdog cleanup..."

# Stop the service
Stop-WatchdogService

# Stop any remaining processes
Stop-WatchdogProcesses

# Remove the service
Remove-WatchdogService

# Verify cleanup
$success = Verify-Cleanup

if ($success) {
    Write-Log "CDR Client Watchdog cleanup completed successfully"
    exit 0
} else {
    Write-Log "Cleanup completed with warnings. A system reboot may be required for complete removal." "Warning"
    exit 1
}
