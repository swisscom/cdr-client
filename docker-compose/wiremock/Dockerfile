FROM wiremock/wiremock:3.13.0-1-alpine

RUN apk update && apk upgrade && apk add curl

COPY ./mappings/* /home/wiremock/mappings/
