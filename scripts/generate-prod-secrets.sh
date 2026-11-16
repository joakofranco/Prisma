#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE="${ENV_FILE:-.env}"
SOLO_SI_PRODUCCION=0
[ "${1:-}" = "--if-production" ] && SOLO_SI_PRODUCCION=1

VALOR_INICIAL='Admin1234!'
VALOR_INICIAL_LARGO='Admin1234!!!'

ENTRADAS=(
  "POSTGRES_PASSWORD|PostgreSQL (base principal)|POSTGRES_USER"
  "KEYCLOAK_DB_PASSWORD|PostgreSQL de Keycloak|KEYCLOAK_DB_USER"
  "KEYCLOAK_ADMIN_PASSWORD|Consola de administración de Keycloak|KEYCLOAK_ADMIN"
  "REDIS_PASSWORD|Redis|-"
  "RUSTFS_SECRET_KEY|RustFS (almacenamiento de evidencias)|RUSTFS_ACCESS_KEY"
  "GF_SECURITY_ADMIN_PASSWORD|Grafana|GF_SECURITY_ADMIN_USER"
  "ELASTIC_PASSWORD|Elasticsearch y Kibana|=elastic"
  "KIBANA_SYSTEM_PASSWORD|Usuario interno de Kibana|=kibana_system"
  "SONAR_DB_PASSWORD|PostgreSQL de SonarQube|SONAR_DB_USER"
  "PGADMIN_DEFAULT_PASSWORD|pgAdmin|PGADMIN_DEFAULT_EMAIL"
  "JWT_SECRET|Clave HMAC (sello de certificación)|-"
  "AI_INTERNAL_API_KEY|Clave interna entre backend-core y backend-ai|-"
)
CON_ESTADO=" POSTGRES_PASSWORD KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_PASSWORD SONAR_DB_PASSWORD GF_SECURITY_ADMIN_PASSWORD PGADMIN_DEFAULT_PASSWORD RUSTFS_SECRET_KEY "

leer() {
  local linea
  linea="$(grep -E "^$1=" "$ENV_FILE" | tail -1 || true)"
  linea="${linea#*=}"
  linea="${linea%$'\r'}"
  linea="${linea%%[[:space:]]#*}"
  linea="${linea%"${linea##*[![:space:]]}"}"
  printf '%s' "$linea"
}

es_inicial() {
  local v="$1" minus
  minus="$(printf '%s' "$v" | tr '[:upper:]' '[:lower:]')"
  [ -z "$v" ] || [ "$v" = "$VALOR_INICIAL" ] || [ "$v" = "$VALOR_INICIAL_LARGO" ] || [ "$v" = "admin" ] || [ "${minus#change_me}" != "$minus" ]
}

aleatorio() {
  local largo="${1:-32}" valor=""
  while [ "${#valor}" -lt "$largo" ]; do
    if command -v openssl >/dev/null 2>&1; then
      valor="$valor$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9')"
    else
      valor="$valor$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
    fi
  done
  printf '%s' "${valor:0:$largo}"
}

escribir() {
  local clave="$1" valor="$2" tmp="$ENV_FILE.tmp.$$" hecho=0 linea
  : > "$tmp"
  while IFS= read -r linea || [ -n "$linea" ]; do
    if [ "$hecho" = 0 ] && [ "${linea%%=*}" = "$clave" ] && [ "$linea" != "${linea#*=}" ]; then
      printf '%s=%s\n' "$clave" "$valor" >> "$tmp"
      hecho=1
    else
      printf '%s\n' "$linea" >> "$tmp"
    fi
  done < "$ENV_FILE"
  [ "$hecho" = 1 ] || printf '%s=%s\n' "$clave" "$valor" >> "$tmp"
  mv "$tmp" "$ENV_FILE"
}

if [ ! -f "$ENV_FILE" ]; then
  if [ "$SOLO_SI_PRODUCCION" = 1 ]; then exit 0; fi
  [ -f .env.example ] || { echo "No existe $ENV_FILE ni .env.example" >&2; exit 1; }
  cp .env.example "$ENV_FILE"
  echo "Se creó $ENV_FILE a partir de .env.example."
fi

if [ "$SOLO_SI_PRODUCCION" = 1 ] && [ "$(leer ENV)" != "production" ]; then
  exit 0
fi

respaldo=""
pendientes=()
for entrada in "${ENTRADAS[@]}"; do
  clave="${entrada%%|*}"
  if es_inicial "$(leer "$clave")"; then pendientes+=("$clave"); fi
done
portainer_archivo=".secrets/portainer_admin_password"
generar_portainer=0
if [ ! -s "$portainer_archivo" ] || [ "$(tr -d '\r\n' < "$portainer_archivo")" = "$VALOR_INICIAL_LARGO" ]; then
  generar_portainer=1
fi

if [ "${#pendientes[@]}" = 0 ] && [ "$generar_portainer" = 0 ]; then
  echo "Producción: $ENV_FILE ya tiene contraseñas propias; no se generó ninguna."
  exit 0
fi

prefijo="$(leer VOLUME_PREFIX)"
prefijo="${prefijo:-prisma}"
if [ "${PROD_SECRETS_FORCE:-0}" != 1 ] && command -v docker >/dev/null 2>&1 \
  && docker volume inspect "${prefijo}-postgres-data" >/dev/null 2>&1; then
  bloqueadas=()
  libres=()
  for clave in ${pendientes[@]+"${pendientes[@]}"}; do
    case "$CON_ESTADO" in
      *" $clave "*) bloqueadas+=("$clave") ;;
      *) libres+=("$clave") ;;
    esac
  done
  if [ "${#bloqueadas[@]}" -gt 0 ]; then
    echo ""
    echo "ATENCIÓN: este ambiente ya tiene datos (volumen ${prefijo}-postgres-data) y estas"
    echo "variables siguen con el valor inicial: ${bloqueadas[*]}"
    echo "No se cambian solas: quedaron fijadas en los datos al primer arranque y cambiarlas solo en"
    echo "el .env deja a los servicios sin poder autenticarse. Rotalas a mano (ver docs/operacion/RUNBOOK.md) o,"
    echo "si es una instalación nueva con volúmenes de prueba, repetí con PROD_SECRETS_FORCE=1."
    echo ""
  fi
  pendientes=(${libres[@]+"${libres[@]}"})
fi

if [ "${#pendientes[@]}" -gt 0 ]; then
  respaldo="$ENV_FILE.bak.$(date -u +%Y%m%d-%H%M%S)"
  cp "$ENV_FILE" "$respaldo"
  chmod 600 "$respaldo" 2>/dev/null || true
fi

generadas=()
for clave in ${pendientes[@]+"${pendientes[@]}"}; do
  largo=32
  [ "$clave" = "JWT_SECRET" ] && largo=64
  valor="$(aleatorio "$largo")"
  escribir "$clave" "$valor"
  generadas+=("$clave|$valor")
done
chmod 600 "$ENV_FILE" 2>/dev/null || true

clave_portainer=""
if [ "$generar_portainer" = 1 ]; then
  mkdir -p .secrets
  clave_portainer="$(aleatorio 24)"
  ( umask 077; printf '%s' "$clave_portainer" > "$portainer_archivo" )
fi

mostrar=1
[ "${GITHUB_ACTIONS:-}" = "true" ] && mostrar=0
[ "${PROD_SECRETS_QUIET:-0}" = 1 ] && mostrar=0

descripcion() {
  local entrada
  for entrada in "${ENTRADAS[@]}"; do
    if [ "${entrada%%|*}" = "$1" ]; then
      local resto="${entrada#*|}" que usuario
      que="${resto%%|*}"
      usuario="${resto#*|}"
      case "$usuario" in
        -) usuario="-" ;;
        =*) usuario="${usuario#=}" ;;
        *) usuario="$(leer "$usuario")" ;;
      esac
      printf '%s|%s' "$que" "${usuario:--}"
      return
    fi
  done
}

echo ""
echo "=================================================================================="
echo " PRISMA — contraseñas de producción generadas"
echo "=================================================================================="
if [ "$mostrar" = 1 ]; then
  printf ' %-28s %-40s %-22s %s\n' "VARIABLE" "SERVICIO" "USUARIO" "CONTRASEÑA"
  for item in ${generadas[@]+"${generadas[@]}"}; do
    clave="${item%%|*}"
    valor="${item#*|}"
    d="$(descripcion "$clave")"
    printf ' %-28s %-40s %-22s %s\n' "$clave" "${d%%|*}" "${d#*|}" "$valor"
  done
  if [ -n "$clave_portainer" ]; then
    printf ' %-28s %-40s %-22s %s\n' "(archivo .secrets)" "Portainer" "admin" "$clave_portainer"
  fi
else
  echo " Se generaron ${#generadas[@]} valores. No se muestran en esta salida: están en $ENV_FILE del servidor."
  [ -n "$clave_portainer" ] && echo " La contraseña de Portainer está en $portainer_archivo."
fi
echo "----------------------------------------------------------------------------------"
echo " Guardalas ahora en un gestor de contraseñas: no se vuelven a mostrar."
echo " Quedaron escritas en $ENV_FILE (permisos 600)${respaldo:+; copia del anterior en $respaldo}."
if es_inicial "$(leer KEYCLOAK_ADMIN_CLIENT_SECRET)"; then
  echo ""
  echo " PENDIENTE (manual): KEYCLOAK_ADMIN_CLIENT_SECRET sigue con el valor de ejemplo. No se genera"
  echo " solo porque tiene que coincidir con el secreto del cliente prisma-backend dentro de Keycloak."
  echo " Con Keycloak arriba: Clients → prisma-backend → Credentials → Regenerate, y copiar ese valor"
  echo " al .env antes de recrear backend-core."
fi
echo ""
echo " El usuario inicial de la aplicación es admin@prisma.local con contraseña $VALOR_INICIAL;"
echo " Keycloak obliga a cambiarla en el primer inicio de sesión."
echo "=================================================================================="
echo ""
