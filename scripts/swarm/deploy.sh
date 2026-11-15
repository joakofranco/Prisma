#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

ENV_FILE="${ENV_FILE:-.env}"
export STACK="${STACK:-prisma}"

if [ ! -f "$ENV_FILE" ]; then
  echo "No existe $ENV_FILE" >&2
  exit 1
fi

CALLER_ENV="$(export -p)"
set -a
. "./$ENV_FILE"
set +a
set +e
eval "$CALLER_ENV" 2>/dev/null
set -e

: "${GHCR_ORG:?Falta GHCR_ORG}"
export TAG="${TAG:-$(git rev-parse --short HEAD)}"

if [ "$(docker info --format '{{.Swarm.LocalNodeState}}')" != "active" ]; then
  docker swarm init >/dev/null
fi

NODE_ID="$(docker info --format '{{.Swarm.NodeID}}')"
docker node update --label-add app=true --label-add data=true "$NODE_ID" >/dev/null

docker stack deploy --with-registry-auth --resolve-image="${RESOLVE_IMAGE:-changed}" --prune \
  -c docker-stack.yml "$STACK"
