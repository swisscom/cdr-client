# curaLINE Client Advanced Installation Guide for Windows Server

This guide explains how to manually install and configure curaLINE Client on Windows Server using the release artifacts.

> ⚠️ **IMPORTANT**: This is an **advanced manual installation** method. If you're using **Windows Server 2022 or newer**, please use the default MSIX installer instead, which provides automatic updates and simplified management.
>
> This manual installation method is primarily for:
> - Windows Server 2019
> - Environments that cannot use MSIX packages
> - Advanced users requiring custom configurations

> ⚠️ **Caution**: The Update process won't handle JRE or .NET runtime updates. Newer curaLINE Client versions that may require newer runtimes will need manual updates of those runtimes by the administrator and will be broken until this was done.

## Overview

curaLINE Client on Windows Server uses a three-tier service architecture:
- **UpdateService** - Automatically downloads and applies updates from GitHub
- **Watchdog** - Monitors and restarts the main service if it crashes
- **CDR Service** - The main application (runs as a Java JAR)

## What You Need

From the GitHub release, download these files:
1. **cdr-client-service-{version}.jar** - Main application
2. **CdrClientWatchdog-{version}.zip** - Watchdog Windows service
3. **curaLINEClient-updateservice-{version}.zip** - Update manager service

## Prerequisites

- Windows Server (2019 or compatible version)
- Administrator privileges
- .NET 10 Runtime for Windows
- Java Runtime Environment (JRE) 21 or later

## Installation Steps

### 1. Create Installation Directory

Create the base installation directory structure:

```cmd
mkdir "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient"
cd "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient"
mkdir bin
mkdir bin\watchdog
mkdir bin\updateservice
mkdir lib
mkdir jre
mkdir logs
```

### 2. Install Java Runtime

Copy your JRE installation to `C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\jre\`

The structure should be:
```
C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\jre\
  └── bin\
      └── java.exe
```

### 3. Install curaLINE Service JAR

Copy the service JAR:
```cmd
copy cdr-client-service-{version}.jar "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\lib\cdr-client-service.jar"
```

### 3.5. Configure Application Settings

The curaLINE Client service requires configuration files for application settings and logging.

⚠️ **IMPORTANT:** Configuration files MUST be placed in `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\`, NOT in `C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\` due to Windows permissions. Services cannot reliably write to Program Files.

1. **Create Configuration Directory**:
   ```cmd
   mkdir "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient"
   mkdir "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\logs"
   mkdir "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf"
   ```

2. **Create Application Configuration** (`application-customer.yaml`):
   
   **Use Template from Repository:**
   
   Download the default template from the GitHub repository:
   - Navigate to `cdr-client-service/conf/default-application-customer.yaml` in the repository
   - Copy the contents to `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf\application-customer.yaml`
   - Customize the values for your environment

3. **Create Logging Configuration** (`logback-service.xml`):
   
   **Option A - Use Template from Repository:**
   
   Download the default template from the GitHub repository:
   - Navigate to `cdr-client-service/conf/logback-service.xml` in the repository
   - Copy the contents to `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf\logback-service.xml`
   - Adjust log paths if needed (LOG_DIR should point to C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/logs (forward slash are on purpose))
   
   **Option B - Create Basic Configuration:**
   
   Create `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf\logback-service.xml`:
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <configuration>
       <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
           <file>C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/logs/cdr-client-service.log</file>
           <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
               <fileNamePattern>C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/logs/cdr-client-service.%d{yyyy-MM-dd}.log</fileNamePattern>
               <maxHistory>30</maxHistory>
           </rollingPolicy>
           <encoder>
               <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
           </encoder>
       </appender>
       
       <root level="INFO">
           <appender-ref ref="FILE" />
       </root>
   </configuration>
   ```

4. **Set Permissions**:
   
   Ensure the service account can read/write to these directories:
   ```cmd
   icacls "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient" /grant "NT AUTHORITY\LOCAL SERVICE:(OI)(CI)F" /T
   ```
   
   Or if running as a specific user, grant permissions accordingly.

**Configuration Paths Explained:**
- `spring.config.additional-location`: Path to your custom application YAML configuration
- `logging.config`: Path to Logback XML configuration for logging
- `cdr.client.log.directory`: Directory where log files will be written

These paths are configured in the Watchdog's `JavaArguments` setting (see next step).

### 4. Install Watchdog Service

1. **Extract the ZIP**:
   ```cmd
   cd "%USERPROFILE%\Downloads"
   tar -xf CdrClientWatchdog-{version}.zip -C "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\watchdog"
   ```

2. **Configure the Watchdog**:
   
   Verify the paths defined in `C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\watchdog\appsettings.json`
   
   **JavaArguments Explained:**
   - `-Xmx512m`: Maximum heap size for Java (adjust based on your requirements)
   - `-Dspring.main.web-application-type=none`: Disables the web server (not needed for headless service)
   - `-Dspring.config.additional-location`: Path to your application configuration YAML
   - `-Dlogging.config`: Path to Logback logging configuration XML
   - `-Dcdr.client.log.directory`: Directory for log files
   
   **Note**: When running as a headless Windows service, the web server is completely disabled since there's no UI to access. The service runs in the background and performs its scheduled tasks.
   
   **Important**: The paths in `JavaArguments` must match the configuration files you created in Step 3.5.
   
3. **Install as Windows Service**: Run in Windows Terminal, as Admin
   ```cmd
   cd "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\watchdog"
   install-service.bat
   ```

### 5. Install Update Service

1. **Extract the ZIP**:
   ```cmd
   cd "%USERPROFILE%\Downloads"
   tar -xf curaLINEClient-updateservice-{version}.zip -C "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\updateservice"
   ```

2. **Configure the Update Service**:
   
   Verify paths defined in `C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\updateservice\appsettings.json`
   ```json
   {
     "UpdateCheckIntervalHours": 2,
     "GitHubRepository": "owner/cdr-client",
     "WatchdogServiceName": "CDRClientWatchdog",
     "InstallationPath": "C:\\Program Files\\Swisscom (Schweiz) AG\\curaLINEClient",
     "CurrentVersions": {
       "Service": "1.0.0",
       "Watchdog": "1.0.0"
     },
     "Artifacts": {
       "Service": {
         "FileName": "cdr-client-service.jar",
         "TargetPath": "lib/cdr-client-service.jar"
       },
       "Watchdog": {
         "FileName": "CdrClientWatchdog.zip",
         "TargetPath": "bin/watchdog/"
       }
     },
     "JavaExecutablePath": "jre/bin/java.exe"
   }
   ```
   Ensure that `WatchdogServiceName` matches any modifications made in the watchdog service installation step (`install-service.bat`) under [4. Install Watchdog Service step 3](#4-install-watchdog-service).
   
3. **Install as Windows Service**:
   ```cmd
   cd "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\updateservice"
   install-service.bat
   ```

4. **Verify Installation**:
   ```cmd
   sc query curaLINEClientUpdateService
   ```

### 6. Start Services

Start services in this order:

1. **Start Watchdog** (which automatically starts the curaLINE service):
   ```cmd
   sc start CDRClientWatchdog
   ```

2. **Start Update Service**:
   ```cmd
   sc start curaLINEClientUpdateService
   ```

## Verification

### Check Service Status

```cmd
sc query CDRClientWatchdog
sc query curaLINEClientUpdateService
```

Both should show `STATE: RUNNING`.

### View Event Logs

Open Event Viewer (`eventvwr.msc`):
1. Navigate to: **Windows Logs → Application**
2. Filter by sources:
   - `CDRClientWatchdog`
   - `curaLINEClientUpdateService`

Look for successful startup messages.

### Test Update Mechanism

To force an immediate update check:
```cmd
sc stop curaLINEClientUpdateService
sc start curaLINEClientUpdateService
```

Check Event Viewer for update check activity.

## Directory Structure (Final)

Your installation should look like this:

```
C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\
├── bin\
│   ├── watchdog\
│   │   ├── CdrClientWatchdog.exe
│   │   ├── appsettings.json
│   │   ├── install-service.bat
│   │   ├── uninstall-service.bat
│   │   ├── register-eventlog.bat
│   │   └── [other DLLs and dependencies]
│   └── updateservice\
│       ├── CuraLineClientUpdateService.exe
│       ├── appsettings.json
│       ├── install-service.bat
│       ├── uninstall-service.bat
│       ├── register-eventlog.bat
│       └── [other DLLs and dependencies]
├── lib\
│   └── cdr-client-service.jar
├── jre\
│   └── bin\
│       └── java.exe
└── conf\
    └── [optional: alternative location for config files]

C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\        (Application configuration)
├── conf\
│   ├── application-customer.yaml    (Spring Boot configuration)
│   └── logback-service.xml          (Logging configuration)
└── logs\                            (Log file directory)
    └── cdr-client-service.log
```

## How Automatic Updates Work

Once installed:

1. **UpdateService** checks GitHub for new releases (default: every 2 hours, configurable via `UpdateCheckIntervalHours` in `appsettings.json`)
2. When a new version is found:
   - Downloads the new `cdr-client-service-{version}.jar`
   - Stops the Watchdog service (which stops the CDR service)
   - Replaces the JAR in `lib/`
   - Updates `appsettings.json` with new version
   - Restarts the Watchdog service (which starts CDR with the new JAR)
3. **Zero downtime** updates (brief restart only)

## Troubleshooting

### Services Won't Start

1. **Check Event Logs** for error details
2. **Verify paths** in `appsettings.json` files
3. **Check permissions** - ensure service account can access files
4. **Verify .NET Runtime** is installed: `dotnet --info`
5. **Verify Java** is accessible: `C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\jre\bin\java.exe -version`
6. **Verify configuration files exist**:
   ```cmd
   dir "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf\application-customer.yaml"
   dir "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf\logback-service.xml"
   ```

### Configuration File Issues

**Service starts but fails immediately:**
- Check that `application-customer.yaml` exists at: `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf\application-customer.yaml`
- Verify YAML syntax in `application-customer.yaml` (YAML is strict about indentation)
- Check initialization log: `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\logs\cdr-service-init.log` for configuration errors
- Verify the service account has read/write permissions on `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\`
- **Common mistake:** Verify config paths in watchdog's `appsettings.json` use `C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/` NOT `C:/Program Files/`

**Logs not being created:**
- Verify the log directory exists: `C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\logs\`
- Check that the service account has write permissions:
  ```cmd
  icacls "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient"
  ```
- If `logback-service.xml` was manually created, verify it doesn't contain the `@@LOG_DIR@@` placeholder:
  ```cmd
  type "C:\ProgramData\Swisscom (Schweiz) AG\curaLINEClient\conf\logback-service.xml" | findstr "@@LOG_DIR@@"
  ```
  If found, either delete the file and let the app recreate it, or manually replace `@@LOG_DIR@@` with `C:/ProgramData/Swisscom (Schweiz) AG/curaLINEClient/logs`
- Check the watchdog service is running: `sc query CDRClientWatchdog`

### CDR Service Crashes/Restarts

- Check Watchdog logs in Event Viewer
- Review CDR service logs (if configured)
- Verify Java arguments in `watchdog/appsettings.json`

### Updates Not Applying

1. Check UpdateService logs in Event Viewer
2. Verify GitHub repository URL is correct
3. Test network connectivity to GitHub:
   ```powershell
   Test-NetConnection -ComputerName github.com -Port 443
   ```
4. Verify release artifacts exist on GitHub releases page

### Manual Update Check

Force an immediate update check:
```cmd
sc stop curaLINEClientUpdateService
sc start curaLINEClientUpdateService
```

## Uninstallation

To remove CDR Client:

1. **Stop and remove Update Service**:
   ```cmd
   cd "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\updateservice"
   uninstall-service.bat
   ```

2. **Stop and remove Watchdog** (also stops CDR service):
   ```cmd
   cd "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient\bin\watchdog"
   uninstall-service.bat
   ```

3. **Delete installation directory**:
   ```cmd
   rmdir /s "C:\Program Files\Swisscom (Schweiz) AG\curaLINEClient"
   ```

## Service Management Commands

### Start Services
```cmd
sc start CDRClientWatchdog
sc start curaLINEClientUpdateService
```

### Stop Services
```cmd
sc stop curaLINEClientUpdateService
sc stop CDRClientWatchdog
```

### Check Status
```cmd
sc query CDRClientWatchdog
sc query curaLINEClientUpdateService
```

### View Service Configuration
```cmd
sc qc CDRClientWatchdog
sc qc curaLINEClientUpdateService
```

## Monitoring Best Practices

### Regular Checks

1. **Daily**: Monitor Event Viewer for errors/warnings
2. **Weekly**: Verify services are running
3. **Monthly**: Confirm updates are being applied

### PowerShell Monitoring

```powershell
# Check recent watchdog events
Get-EventLog -LogName Application -Source CDRClientWatchdog -Newest 20

# Check recent update service events
Get-EventLog -LogName Application -Source curaLINEClientUpdateService -Newest 20
```

## Support

For issues:
1. Check Event Viewer logs for detailed error messages
2. Review configuration files for typos
3. Verify file permissions and paths
4. Consult the README files in each component directory

---

**Copyright © Swisscom (Schweiz) AG**

**Note**: This manual installation method is designed for Windows Server environments where the default MSIX installer cannot be used. For **Windows Server 2022 and newer**, the MSIX package with automatic updates via Conveyor is the recommended installation method.

