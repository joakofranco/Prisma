#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env}"
DOMINIO="${DOMINIO:-}"
PROD="${PROD:-0}"
BUILD="${BUILD:-0}"
PERFIL="${PERFIL:-full}"
ESPERA_S="${ESPERA_S:-300}"
SOLO_ENV=0

uso() {
  cat <<'EOF'
Uso: DOMINIO=prisma.tudominio.com scripts/deploy-cloudflare.sh [--solo-env]

Despliega PRISMA publicado con Cloudflare Tunnel (docker-compose.cloudflare.yml):
  1. Deja el .env coherente con el dominio: PUBLIC_BASE_URL, CORS_ALLOWED_ORIGINS,
     KEYCLOAK_TRUSTED_ISSUERS y KEYCLOAK_ISSUER_URL. Es idempotente y guarda antes una
     copia en .env.bak-<fecha>.
  2. Levanta el stack con docker-compose.yml (+ docker-compose.prod.yml si PROD=1) +
     docker-compose.cloudflare.yml.
  3. Espera a que backend-core quede sano y comprueba que el issuer que anuncia Keycloak
     por el dominio coincide con uno de confianza.

Variables: DOMINIO (obligatoria), ENV_FILE=.env, PROD=0|1, BUILD=0|1, PERFIL=full,
ESPERA_S=300. En el .env o en el entorno: INTERNAL_BIND_IP (obligatoria), TUNNEL_ORIGIN_PORT.
--solo-env solo actualiza el .env y no levanta nada.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --solo-env) SOLO_ENV=1 ;;
    -h|--help) uso; exit 0 ;;
    *) echo "Parámetro desconocido: $arg" >&2; uso >&2; exit 2 ;;
  esac
done

fallar() { echo "ERROR: $*" >&2; exit 1; }

[ -n "$DOMINIO" ] || { uso >&2; fallar "falta DOMINIO (por ejemplo DOMINIO=prisma.tudominio.com)"; }
DOMINIO="${DOMINIO#https://}"
DOMINIO="${DOMINIO%/}"
[[ "$DOMINIO" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$ ]] \
  || fallar "DOMINIO no es un nombre de host válido: $DOMINIO"
[ -f "$ENV_FILE" ] || fallar "no existe $ENV_FILE (copiar .env.example y completarlo)"

ORIGEN="https://$DOMINIO"
ISSUER="$ORIGEN/auth/realms/prisma"

valor_env() {
  local v
  v="$(grep -m1 "^$1=" "$ENV_FILE" | cut -d= -f2- | tr -d '\r' || true)"
  printf '%s' "${v:-}"
}

fijar() {
  local clave="$1" valor="$2"
  if grep -q "^$clave=" "$ENV_FILE"; then
    sed -i "s|^$clave=.*|$clave=$valor|" "$ENV_FILE"
  else
    printf '\n%s=%s\n' "$clave" "$valor" >> "$ENV_FILE"
  fi
}

agregar_a_lista() {
  local clave="$1" item="$2" actual
  actual="$(valor_env "$clave")"
  if [ -z "$actual" ]; then
    actual="$(grep -m1 "^$clave=" .env.example | cut -d= -f2- | tr -d '\r' || true)"
  fi
  case ",$actual," in
    *",$item,"*) fijar "$clave" "$actual" ;;
    *) fijar "$clave" "${actual:+$actual,}$item" ;;
  esac
}

COPIA="$ENV_FILE.bak-$(date +%Y%m%d-%H%M%S)"
cp -p "$ENV_FILE" "$COPIA"
echo "Copia del $ENV_FILE en $COPIA"

BIND_IP="${INTERNAL_BIND_IP:-$(valor_env INTERNAL_BIND_IP)}"
PUERTO_TUNEL="${TUNNEL_ORIGIN_PORT:-$(valor_env TUNNEL_ORIGIN_PORT)}"
PUERTO_TUNEL="${PUERTO_TUNEL:-8443}"

fijar PUBLIC_BASE_URL "$ORIGEN"
fijar KEYCLOAK_ISSUER_URL "$ORIGEN/auth"
agregar_a_lista CORS_ALLOWED_ORIGINS "$ORIGEN"
agregar_a_lista KEYCLOAK_TRUSTED_ISSUERS "$ISSUER"
if [ -n "$BIND_IP" ] && [ "$BIND_IP" != "127.0.0.1" ]; then
  agregar_a_lista CORS_ALLOWED_ORIGINS "https://$BIND_IP"
  agregar_a_lista KEYCLOAK_TRUSTED_ISSUERS "https://$BIND_IP/auth/realms/prisma"
fi
echo "$ENV_FILE actualizado para $ORIGEN"

[ "$SOLO_ENV" = 1 ] && exit 0

[ -n "$BIND_IP" ] || fallar "falta INTERNAL_BIND_IP en $ENV_FILE o en el entorno: es la IP (de la VPN) donde queda publicado NGINX para los administradores. Con 127.0.0.1 solo se entra desde el propio servidor"

ARCHIVOS=(-f docker-compose.yml)
[ "$PROD" = 1 ] && ARCHIVOS+=(-f docker-compose.prod.yml)
ARCHIVOS+=(-f docker-compose.cloudflare.yml)

if command -v systemctl >/dev/null 2>&1; then
  systemctl is-active --quiet cloudflared \
    || echo "AVISO: el servicio cloudflared no figura activo en este equipo" >&2
fi

echo "NGINX quedará en 127.0.0.1:$PUERTO_TUNEL (túnel) y en $BIND_IP:443 (administración)"
echo "El servicio del túnel en Cloudflare debe ser https://localhost:$PUERTO_TUNEL con No TLS Verify"

OPCIONES=(--profile "$PERFIL" up -d --remove-orphans)
[ "$BUILD" = 1 ] && OPCIONES+=(--build)
docker compose --env-file "$ENV_FILE" "${ARCHIVOS[@]}" "${OPCIONES[@]}"

echo "Esperando a que backend-core quede sano (máximo ${ESPERA_S}s)..."
FIN=$(( $(date +%s) + ESPERA_S ))
ESTADO=""
while [ "$(date +%s)" -lt "$FIN" ]; do
  ESTADO="$(docker inspect prisma-backend-core --format '{{.State.Health.Status}}' 2>/dev/null || true)"
  [ "$ESTADO" = healthy ] && break
  sleep 5
done
[ "$ESTADO" = healthy ] || fallar "backend-core no quedó sano (estado: ${ESTADO:-desconocido})"

ISSUER_ANUNCIADO="$(curl -sk -m 15 -H "Host: $DOMINIO" \
  "https://127.0.0.1:$PUERTO_TUNEL/auth/realms/prisma/.well-known/openid-configuration" \
  | sed -n 's/.*"issuer":"\([^"]*\)".*/\1/p' || true)"
[ -n "$ISSUER_ANUNCIADO" ] || fallar "no se pudo leer el issuer de Keycloak por 127.0.0.1:$PUERTO_TUNEL con Host $DOMINIO (¿NGINX levantó?)"
case ",$(valor_env KEYCLOAK_TRUSTED_ISSUERS)," in
  *",$ISSUER_ANUNCIADO,"*) ;;
  *) fallar "Keycloak anuncia el issuer $ISSUER_ANUNCIADO y no está en KEYCLOAK_TRUSTED_ISSUERS: la API respondería 401" ;;
esac

CODIGO="$(curl -sk -o /dev/null -m 15 -w '%{http_code}' -H "Host: $DOMINIO" "https://127.0.0.1:$PUERTO_TUNEL/" || true)"
[ "$CODIGO" = 200 ] || fallar "NGINX respondió HTTP $CODIGO en 127.0.0.1:$PUERTO_TUNEL (se esperaba 200)"

echo "Listo: $ORIGEN, issuer $ISSUER_ANUNCIADO de confianza, origen local en HTTP 200"
