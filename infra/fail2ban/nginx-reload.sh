#!/bin/sh
SOCK=/var/run/docker.sock
SERVICE="${NGINX_SWARM_SERVICE:-prisma_nginx}"
IDS=$(curl -s --unix-socket "$SOCK" -G "http://localhost/containers/json" \
  --data-urlencode "filters={\"label\":[\"com.docker.swarm.service.name=${SERVICE}\"]}" \
  | grep -o '"Id":"[0-9a-f]*"' | cut -d'"' -f4)
if [ -z "$IDS" ]; then
  IDS=prisma-nginx
fi
for id in $IDS; do
  curl -s -o /dev/null --unix-socket "$SOCK" -X POST "http://localhost/containers/${id}/kill?signal=HUP"
done
