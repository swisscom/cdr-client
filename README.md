# CDR Client
The Swisscom Health Confidential Data Routing (CDR) Client

## API
There is no endpoint (beside actuator/health) that are offered here.

The CDR Client is triggered by a scheduler and synchronizes by the given delay time the files from the CDR API.

### Functionality
For each defined connector the CDR Client calls the defined endpoint of the CDR API.

#### Document Download

For each connector one file after the other is pulled. Each file is written into a temporary folder defined as 'local-folder'.
The file is named after the received 'cdr-document-uuid' header that is a unique identifier created by the CDR API.
After saving the file to the temporary folder, a delete request for the given 'cdr-document-uuid' is sent to the CDR API.
After successfully deleting the file in the CDR API, the file is moved to the connector defined 'target-folder'.

The temporary folders need to be monitored by another tool to make sure that no files are forgotten (should only happen if the move
to the destination folder is failing).

#### Document Upload

Document upload uses a combination of directory polling and event driven uploads. The polling process inspects the 
contents of every source folder at the configured interval and uploads all `.xml` files it finds to the CDR API. The 
event driven process listens for filesystem events from the same directories and uploads all `.xml` files as they 
are created. The two approaches are combined so

* at start of the client all files that might have arrived while the client was not running are uploaded
* folders on (remote) filesystems that do not support filesystem events can be used as source folders

If the filesystem that hosts a source folder supports filesystem events, then the polling process normally won't find 
any files to process and immediately goes back to sleep. If the polling process wakes up right at the moment a new file 
arrives, it might happen that both processes pick up the same file for processing. However, only one of the two will 
continue to process the file, depending on which one is first to register the file for processing.

After the file is successfully uploaded it will be deleted.
If the upload failed with a response code of 4xx the file will be appended with '.error' and an additional file with the same name as the sent file, but with
the extension '.log' will be created and the received response body will be saved to this file.
If the upload failed with a response code of 5xx the file will be retried indefinitely, assuming the root cause is 
an infrastructure issue that will ultimately be resolved (and uploading another file would fail too, for the same 
reason). See retry-delay in the [application-client.yaml](./src/main/resources/config/application-client.yaml) file.

## Local development
To test some usecases there is a [docker-compose.yaml](./docker-compose/docker-compose.yaml) with wiremock that simulates the CDR API. Run with ```docker-compose down && docker-compose up --build```.

If you want to work with a deployed CDR API you need to change the [application-dev.yaml](./src/main/resources/config/application-dev.yaml)

Set the following spring profile to active: dev

Following environment variables need to be set:
* cdrClient.localFolder=~/Documents/cdr/inflight
* cdrClient.targetFolder=~/Documents/cdr/target
* cdrClient.sourceFolder=~/Documents/cdr/source
* CDR_B2C_TENANT_ID=some-tenant-id
* CDR_CLIENT_ID=my-own-client-id
* CDR_CLIENT_SECRET=my-own-client-secret

## Application Plugin
To create scripts to run the application locally one needs to run following gradle cmd: ```gradlew installDist```

This creates a folder ```build/install/cdr-client``` with scripts for windows and unix servers in the ```bin``` folder.

To run the application locally one can call ```./build/install/cdr-client/bin/cdr-client```. It is required to have a ```application-customer.yaml``` and link it by adding following command line: ```JVM_OPTS="-Dspring.config.additional-location=./application-customer.yaml"```.
With a minimum configuration that looks like this:
```
client:
  endpoint:
    host: cdr.health.swisscom.com
  customer:
    - connector-id: 8000000000000
      content-type: application/forumdatenaustausch+xml;charset=UTF-8
      target-folder: /tmp/download/8000000000000
      source-folder: /tmp/source/8000000000000
      mode: test
```

## Running the Jar
If the provided jar should be run directly, the following command can be used:
```java -jar cdr-client.jar```
The jar can be found in build/libs.

Following environment variables need to be present (and correctly configured) so that the application can start successfully:
```
SPRING_CONFIG_ADDITIONAL_LOCATION={{ cdr_client_dir }}/config/application-customer.yaml"
LOGGING_FILE_NAME={{ cdr_client_dir }}/logs/cdr-client.log"
```
The LOGGING_FILE_NAME is just so that the log file is not auto created where the jar is run from.

See [Application Plugin](#application-plugin) regarding the content of the application-customer.yaml
