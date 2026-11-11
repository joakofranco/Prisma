#!/usr/bin/env bash
set -e
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CA="$ROOT_DIR/infra/tls/ca.crt"
services=(
  "backend-core|http://localhost:8081/actuator/health|https://localhost:8081/actuator/health"
  "backend-ai|http://localhost:8000/health|https://localhost:8000/health"
  "frontend|http://localhost:8080|https://localhost"
  "keycloak|http://localhost:8180/auth/realms/prisma|https://localhost/auth/realms/prisma"
  "rustfs|http://localhost:9001/health|https://localhost:9001/health"
)
reachable() {
  local url="$1"
  curl -sf --max-time 5 "$url" >/dev/null 2>&1 && return 0
  case "$url" in
    https://*)
      [ -f "$CA" ] && curl -sf --max-time 5 --cacert "$CA" "$url" >/dev/null 2>&1 && return 0
      curl -skf --max-time 5 "$url" >/dev/null 2>&1 && return 0
      ;;
  esac
  return 1
}
echo "🩺 Comprobando servicios..."
for entry in "${services[@]}"; do
  IFS='|' read -r name plain tls <<< "$entry"
  if reachable "$plain" || reachable "$tls"; then
    echo "  ✅ $name — OK"
  else
    echo "  ❌ $name — FAIL ($plain | $tls)"
  fi
done
