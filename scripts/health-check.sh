#!/usr/bin/env bash
set -e
echo "🩺 Comprobando servicios..."
services=(
  "http://localhost:8081/actuator/health|backend-core"
  "http://localhost:8000/health|backend-ai"
  "http://localhost:8080|frontend"
  "http://localhost:8180/health/ready|keycloak"
  "http://localhost:9001/minio/health/live|minio"
)
for entry in "${services[@]}"; do
  url="${entry%%|*}"; name="${entry##*|}"
  if curl -sf --max-time 5 "$url" > /dev/null 2>&1; then
    echo "  ✅ $name — OK"
  else
    echo "  ❌ $name — FAIL ($url)"
  fi
done
