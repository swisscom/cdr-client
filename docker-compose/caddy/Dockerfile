FROM caddy:2.8.4

RUN apk update && apk upgrade && apk add curl

COPY ./Caddyfile /etc/caddy/Caddyfile
COPY ./certs/ /root/certs/
