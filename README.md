# CDR Client

The Swisscom Health curaLINE Data Routing (CDR) Client.

The project is split into two parts:
1. the client service, which is the actual client that connects to the CDR API and
2. the client UI, which can be used to configure the client service and monitor its status

On a Linux system, the service part runs as a systemd unit. On a Windows system it runs as a Windows service 
managed by a dedicated watchdog service.

On macOS, the client UI application starts the client service and shuts it down again when itself is shut down.

## Installation / Run the client

### Installer

Download the installer from the [download page](https://cdr.health.swisscom.ch/share/downloads/download.html) and follow the instructions.
Using the download page will give you the latest version of the client.

The installed client will automatically check for updates and install them if available. 
If the UI is running during an update it needs to be restarted manually to reflect the changes.
* **Windows**: Checks for updates every hour. The UI needs to be manually restarted to reflect the changes.
* **Linux** (Debian): A custom apt repository is added to your system, and the software is installed from it. Updates can be managed through the standard package manager (apt update/upgrade commands).
* **MacOS**: Auto updates in the background or during startup. The UI (and therefore the client service) need to be manually restarted to reflect the changes.

### Manual Installation - Jar only, no auto update
If you want to run the client service without the UI and without auto updates, you can download the jar file directly.

Pre-Requirements:
* Java 17 (or higher) installed

Go to the [releases](https://github.com/swisscom/cdr-client/releases) github page and click on the maven assets for the newest release:
![releases assets overview](./installation/releases-overview.png)
Download the jar file:
![release jar download](./installation/single-release-overview.png)
Place a file named application-customer.yaml in the same directory as the jar file. The application-customer.yaml file
should contain the configuration for the client. An example can be found [here](#application-customer-yaml-example).

Open a terminal and navigate to the directory where the jar file is located. Run the following command to start the
client (check the jar name and replace it in the command or rename the jar itself):
> The -D parameters need to be placed before the "-jar cdr-client-service.jar".<p>
> The quotes are necessary for Windows, but not for Unix systems

```shell
java "-Dspring.config.additional-location=./application-customer.yaml" -jar cdr-client-service.jar
```

Check that no error messages are present in the terminal (or have a look at the "cdr-client-service.log" file that is created in
the same directory as you've placed the jar file) and that the client is running.

Configure an OS appropriate service to run the client as a background service.

## API

A scheduler triggers document downloads from the CDR API. Document uploads are triggered by file system events (if
available) and a scheduler.

### Functionality

For each defined connector, the CDR Client calls the CDR API endpoints for document download and document upload.

Optionally, the client can be configured to automatically renew its client credentials every 365 days.

#### Client Credential Renewal

The client can be configured to renew its credentials every 365 days. The default behavior is to not renew the
credential.

Credential renewal only works if the client credential is configured in a properties or YAML file. If the client detects
multiple sources (system property, environment variable, etc.) for the credential or the source is not of either of the
two file types, then credential renewal won't be attempted. Likewise, if the user that runs the client process does not
have write permissions on the file.

During a successful client credential renewal, all pre-existing client credentials are deleted. So, if you source the
client credential from a secure location (Hashicorp Vault, Azure Keyvault, etc., like you should) as part of your
deployment process, then, you should not enable credential renewal. A re-deployment would restore a previous credential
that is no longer valid, and you would be locked out of the client account until you manually re-create a credential on
the CDR website.

#### Document Download

For each connector, one file after the other is pulled. Each file is written into a temporary directory defined as
'local-folder'. The file is named after the received 'cdr-document-uuid' header that is a unique identifier created by
the CDR API. After saving the file to the temporary folder, a delete request for the given 'cdr-document-uuid' is sent
to the CDR API. After successfully deleting the file in the CDR API, the file is moved to the connector defined
'target-folder'.

The temporary directories need to be monitored to make sure that no files get stranded there (should only happen if the
move to the destination directory is failing).

#### Document Upload

Document upload uses a combination of directory polling and event driven uploads. The polling process inspects the
contents of every source directory at the configured interval and uploads all `.xml` files to the CDR API that it finds
there. The event driven process listens for filesystem events from the same directories and uploads all `.xml` files as
they are created. The two approaches are combined so

* at the start of the client all files are uploaded that might have arrived while the client was not running
* directories on (remote) filesystems that do not support filesystem events can be used as source directories

If the filesystem that hosts a source directory supports filesystem events, then the polling process normally won't find
any files to process and immediately goes back to sleep. If the polling process wakes up right at the moment a new file
arrives, it might happen that both processes pick up the same file for processing. However, only one of the two will
continue to process the file, depending on which one is first to register the file for processing.

After the file is successfully uploaded, it is either deleted or archived, depending on the connector configuration. If
the upload failed with a response code of 4xx, the file will be appended with '.error' and an additional file with the
same name as the file sent, but with the extension '.log', will be created and the received response body will be saved
to this file. If the upload failed with a response code of 5xx, the file will be retried indefinitely, assuming the root
cause is an infrastructure issue that will ultimately be resolved (and uploading another file would fail too, for the
same reason). See retry-delay in the [application-client.yaml](./cdr-client-service/src/main/resources/config/application-client.yaml)
file.

## Local development

### Client Service Configuration

To test some use cases, there is a [docker-compose.yaml](./docker-compose/docker-compose.yaml) with wiremock that
simulates the CDR API. Run with 
```
docker compose down && docker compose up --build
```

If you want to work with a deployed CDR API you need to change
the [application-dev.yaml](./cdr-client-service/src/main/resources/config/application-dev.yaml)

Set the following spring profile to active: dev

The following environment variables can be set (to override their `dev` profile defaults):

| Variable                 | Default Value                |
|--------------------------|------------------------------|
| CDR_CLIENT_LOCAL_FOLDER  | $HOME/Documents/cdr/inflight |
| CDR_CLIENT_TARGET_FOLDER | $HOME/Documents/cdr/target   |
| CDR_CLIENT_SOURCE_FOLDER | $HOME/Documents/cdr/source   |
| CDR_B2C_TENANT_ID        | test-tenant-client-id        |
| CDR_CLIENT_ID            | fake-id                      |
| CDR_CLIENT_SECRET        | Placeholder_dummy            |

The application JRE has to be started with the following system properties:

* currently none

### Hydraulic Conveyor

You can use [Hydraulic Conveyor](https://conveyor.hydraulic.dev) to build installable artifacts

Run following to build the project and create and install the package on your DEBIAN system:
```
./gradlew cleanAll buildAll -x test && conveyor -f conveyor-dev.conf make site && sudo dpkg -i output/debian/swisscom-schweiz-ag-cdr-client_1.0.0_amd64.deb
```

### Running the Fat-JAR

If the built SpringBoot fat-jar should be run directly, the following command can be used:
`java -jar cdr-client-service.jar`
The jar can be found in cdr-client-service/build/libs.

The following environment variables need to be present (and correctly configured) so that the application can start
successfully:

```
SPRING_CONFIG_ADDITIONAL_LOCATION=/path/to/application-customer.yaml"
LOGGING_FILE_NAME=/path/to/logs/cdr-client.log"
```

See [application-customer.yaml](#application-customer-yaml-example) below for an example configuration file.

If you do not provide a value for `LOGGING_FILE_NAME` the log file gets auto created in your current working directory.

## application-customer YAML example

```
client:
  file-synchronization-enabled: true
  schedule-delay: PT30M
  local-folder: /tmp/download/in-flight # temporary directory for files that are currently downloaded from CDR API
  file-busy-test-strategy: never_busy # valid values are `never_busy` and `file_size_changed`
  idp-credentials:
    tenant-id: swisscom-health-tenant-id # provided by Swisscom Health
    client-id: my-client-id # Self-service on CDR website
    client-secret: my-secret # Self-service on CDR website
    scope: https://identity.health.swisscom.ch/CdrApi/.default
    renew-credential: true
    max-credential-age: 365d
    last-credential-renewal-time: 2025-06-05T14:01:42Z
  cdr-api:
    scheme: https
    port: 443
    host: cdr.health.swisscom.ch
    base-path: api/documents
  credential-api:
    scheme: ${client.cdr-api.scheme}
    port: ${client.cdr-api.port}
    host: ${client.cdr-api.host}
    base-path: api/client-credentials
  customer:
    - connector-id: 8000000000000 # provided by Swisscom Health
      content-type: application/forumdatenaustausch+xml;charset=UTF-8
      target-folder: /tmp/download/test/8000000000000
      source-folder: /tmp/upload/test/8000000000000
      source-archive-enabled: false
      mode: test
    - connector-id: 8000000000000 # provided by Swisscom Health
      content-type: application/forumdatenaustausch+xml;charset=UTF-8
      target-folder: /tmp/download/8000000000000
      source-folder: /tmp/upload/
      source-archive-enabled: false
      mode: production
```

If the host is set to stage instead of production, then the scope needs to be set to `https://tst.identity.health.swisscom.ch/CdrApi/.default`.
