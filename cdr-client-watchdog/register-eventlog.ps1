# Register CDRClientWatchdog Event Log Source using proper .NET APIs
# Run this script as Administrator

Write-Host "Registering CDRClientWatchdog Event Log Source..." -ForegroundColor Green

try {
    $targetSource = "CDRClientWatchdog"
    
    # Check if the source already exists
    if ([System.Diagnostics.EventLog]::SourceExists($targetSource)) {
        Write-Host "Event log source '$targetSource' already exists and is properly registered." -ForegroundColor Green
    } else {
        # Register the event log source using proper .NET API
        Write-Host "Creating event log source: $targetSource" -ForegroundColor Green
        [System.Diagnostics.EventLog]::CreateEventSource($targetSource, "Application")
        Write-Host "Event log source registered successfully!" -ForegroundColor Green
    }
    
    # Verify registration
    if ([System.Diagnostics.EventLog]::SourceExists($targetSource)) {
        Write-Host "Verification: Event log source '$targetSource' is properly registered." -ForegroundColor Green
        
        # Test writing a log entry
        Write-Host "Testing event log write..." -ForegroundColor Green
        $eventLog = New-Object System.Diagnostics.EventLog("Application")
        $eventLog.Source = $targetSource
        $eventLog.WriteEntry("CDRClientWatchdog Event Log source registration completed successfully.", [System.Diagnostics.EventLogEntryType]::Information)
        Write-Host "Test log entry written successfully!" -ForegroundColor Green
    } else {
        Write-Host "Warning: Event log source registration verification failed." -ForegroundColor Red
    }
    
} catch {
    Write-Host "Error during event log source registration: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Make sure you're running this script as Administrator." -ForegroundColor Yellow
    Write-Host "Note: The .NET logging framework will automatically create the source when the service starts." -ForegroundColor Gray
}

Write-Host ""
Write-Host "Event log source registration completed." -ForegroundColor Green
Write-Host "All log entries will now appear under 'CDRClientWatchdog' in Windows Event Viewer." -ForegroundColor Green
Write-Host ""
Read-Host "Press Enter to continue"
