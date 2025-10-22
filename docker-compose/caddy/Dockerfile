FROM caddy:2.10.0

RUN apk update && apk upgrade && apk add curl

COPY ./Caddyfile /etc/caddy/Caddyfile
