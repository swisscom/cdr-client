# CDR Client
The Swisscom Health Confidential Data Routing (CDR) Client

## API
There is no endpoint (beside actuator/health) that are offered here.

The CDR Client is triggered by a scheduler and synchronizes by the given delay time the files from the CDR API.

### Functionality
For each defined connector the CDR Client calls the defined endpoints of the CDR API.

For each connector one file after the other is pulled. Each file is written into a temporary folder defined as 'local-folder'.
The file is named after the received 'cdr-document-uuid' header that is a unique identifier created by the CDR API.
After saving the file to the temporary folder, a delete request for the given 'cdr-document-uuid' is sent to the CDR API.
After successfully deleting the file in the CDR API, the file is moved to the connector defined 'target-folder'.

The temporary folders need to be monitored by another tool to make sure that no files are forgotten (should only happen if the move
to the destination folder is failing).

For each connector one file after the other is pushed from the defined 'source-folder'. After the file is successfully uploaded it will be deleted.
If the upload failed with a response code of 4xx the file will be appended with '.error' and an additional file with the same name as the sent file, but with
the extension '.log' will be created and the received response body will be saved to this file.
If the upload failed with a response code of 5xx the file will be retried a defined amount of times, 
see retry-delay in the [application-client.yaml](./src/main/resources/config/application-client.yaml) file. After reaching the max retry count the file will 
be appended with '.error' and an additional file with the same name as the sent file, but with the extension '.log' will be created and the received response 
body will be saved to this file.

## Local development
To test some usecases there is a [docker-compose.yaml](./docker-compose/docker-compose.yaml) with wiremock that simulates the CDR API. Run with ```docker-compose down && docker-compose up --build```.

If you want to work with a deployed CDR API you need to change the [application-dev.yaml](./src/main/resources/config/application-dev.yaml)

Set the following spring profile to active: dev

Following environment variables need to be set:
* cdrClient.localFolder=~/Documents/cdr/inflight
* cdrClient.targetFolder=~/Documents/cdr/target
* cdrClient.sourceFolder=~/Documents/cdr/source

## Application Plugin
To create scripts to run the application locally one needs to run following gradle cmd: ```gradlew installDist```

This creates a folder ```build/install/cdr-client``` with scripts for windows and unix servers in the ```bin``` folder.

To run the application locally one can call ```./build/install/cdr-client/bin/cdr-client```. It is required to have a ```application-customer.yaml``` and link it by adding following command line: ```JVM_OPTS="-Dspring.config.additional-location=./application-customer.yaml"```.
With a minimum configuration that looks like this:
```
client:
  local-folder: /tmp/cdr
  endpoint:
    host: cdr.health.swisscom.com
    base-path: api/documents
  customer:
    - connector-id: 8000000000000
      content-type: application/forumdatenaustausch+xml;charset=UTF-8;version=4.5
      target-folder: /tmp/download/8000000000000
      source-folder: /tmp/source/8000000000000
      mode: test
```
