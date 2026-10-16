FROM caddy:2.11.4

RUN apk update && apk add curl && rm -rf /var/cache/apk/*

COPY ./Caddyfile /etc/caddy/Caddyfile
