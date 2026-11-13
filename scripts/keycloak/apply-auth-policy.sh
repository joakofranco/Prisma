#!/usr/bin/env bash
# =====================================================================
# Política de autenticación del realm "prisma": recuperación de contraseña y bloqueo de cuenta
# ---------------------------------------------------------------------
# RF-AUTH-05 (PRI-230) — Vigencia del enlace de "¿Olvidaste tu contraseña?": 30 minutos.
#   Por defecto Keycloak lo deja en 5 minutos. Se fijan los dos ajustes que pueden aplicar:
#   el general de acciones iniciadas por el usuario (actionTokenGeneratedByUserLifespan) y el
#   específico de la recuperación (atributo actionTokenGeneratedByUserLifespan.reset-credentials).
#
# RF-AUTH-08 (PRI-233) — Bloqueo de cuenta: 10 intentos fallidos consecutivos en 15 minutos y
#   bloqueo de 15 minutos.
#     failureFactor       = 10   intentos fallidos que bloquean
#     maxDeltaTimeSeconds = 900  ventana en que se cuentan (15 min)
#     waitIncrementSeconds = maxFailureWaitSeconds = 900
#       En Keycloak la espera crece en múltiplos de waitIncrementSeconds hasta el máximo: con los
#       dos iguales el bloqueo es siempre de 900 s (15 min), sin espera progresiva.
#   NO se tocan minimumQuickLoginWaitSeconds / quickLoginCheckMilliSeconds (acceso rápido): dos
#   fallos con menos de 1 s de diferencia bloquean 60 s antes de llegar a los 10 intentos. Se dejó
#   así a propósito; --status los muestra para tenerlos presentes.
#
# Ojo con el límite de Nginx (RNF-07): infra/nginx/conf.d/prisma-locations.inc deja pasar 5 POST de
# login por minuto por IP (burst=2). Los intentos que Nginx rechaza no llegan a Keycloak y no
# cuentan para el bloqueo: para llegar a 10 hay que espaciarlos (~12 s entre intentos).
#
# Uso (idempotente, se puede correr las veces que haga falta):
#   scripts/keycloak/apply-auth-policy.sh            # aplica la política y muestra el estado
#   scripts/keycloak/apply-auth-policy.sh --status   # solo muestra los valores vigentes
#   scripts/keycloak/apply-auth-policy.sh --check    # como --status, pero sale con 1 si difiere
#
# Requisitos: contenedor de Keycloak levantado y KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD en .env
# o en el entorno. Variables opcionales: REALM (prisma), KEYCLOAK_CONTAINER (prisma-keycloak),
# RESET_LINK_LIFESPAN_SECONDS (1800), LOCKOUT_FAILURES (10), LOCKOUT_WINDOW_SECONDS (900),
# LOCKOUT_DURATION_SECONDS (900).
#
# Nota: --import-realm solo importa realm-prisma.json la PRIMERA vez que se crea el realm. Esos
# mismos valores están en el JSON para los entornos nuevos; en los que ya existen hay que correr
# este script.
# =====================================================================
set -euo pipefail

# Git Bash en Windows convierte rutas tipo /opt/... en argumentos de docker exec.
export MSYS_NO_PATHCONV=1

REALM="${REALM:-prisma}"
KC_CONTAINER="${KEYCLOAK_CONTAINER:-prisma-keycloak}"
RESET_LINK_LIFESPAN="${RESET_LINK_LIFESPAN_SECONDS:-1800}"
LOCKOUT_FAILURES="${LOCKOUT_FAILURES:-10}"
LOCKOUT_WINDOW="${LOCKOUT_WINDOW_SECONDS:-900}"
LOCKOUT_DURATION="${LOCKOUT_DURATION_SECONDS:-900}"
RESET_ATTR="actionTokenGeneratedByUserLifespan.reset-credentials"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then
  # Solo se leen las dos variables que hacen falta (.env no siempre es "sourceable").
  : "${KEYCLOAK_ADMIN:=$(grep -E '^KEYCLOAK_ADMIN=' "$ROOT_DIR/.env" | head -1 | cut -d= -f2- | tr -d '"\r')}"
  : "${KEYCLOAK_ADMIN_PASSWORD:=$(grep -E '^KEYCLOAK_ADMIN_PASSWORD=' "$ROOT_DIR/.env" | head -1 | cut -d= -f2- | tr -d '"\r')}"
fi
: "${KEYCLOAK_ADMIN:?Falta KEYCLOAK_ADMIN (en .env o en el entorno)}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Falta KEYCLOAK_ADMIN_PASSWORD (en .env o en el entorno)}"

kcadm() { docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }

login() {
  if ! docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER"; then
    echo "❌ El contenedor $KC_CONTAINER no está corriendo." >&2; exit 1
  fi
  if kcadm config credentials --server http://localhost:8080/auth --realm master \
    --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1; then
    return 0
  fi
  docker exec "$KC_CONTAINER" sh -c 'keytool -importcert -noprompt -alias prisma-internal-ca -file /opt/keycloak/conf/tls/ca.crt -keystore /tmp/kcadm-truststore.p12 -storetype PKCS12 -storepass changeit >/dev/null 2>&1 || true'
  kcadm config credentials --server https://localhost:8443/auth --truststore /tmp/kcadm-truststore.p12 --trustpass changeit \
    --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
}

# Valor de un campo del JSON de `kcadm get`: busca la clave exacta y devuelve su valor sin comillas.
json_value() {  # json clave
  printf '%s\n' "$1" | grep -F "\"$2\"" | head -1 | sed -E 's/^[^:]*:[[:space:]]*//; s/[",[:space:]]//g'
}

DRIFT=0
row() {  # etiqueta clave-actual valor-esperado
  local mark="✅"
  if [ "$2" != "$3" ]; then mark="❌"; DRIFT=1; fi
  printf '  %s %-52s vigente=%-6s esperado=%s\n' "$mark" "$1" "${2:-(sin valor)}" "$3"
}

show_status() {
  local realm
  # Se lee el realm completo: con --fields, kcadm muestra los objetos anidados (attributes) vacíos
  # aunque tengan valores.
  realm="$(kcadm get "realms/$REALM")"
  echo "Realm '$REALM':"
  echo "Recuperación de contraseña (RF-AUTH-05):"
  row "Vigencia del enlace, general (s)" "$(json_value "$realm" actionTokenGeneratedByUserLifespan)" "$RESET_LINK_LIFESPAN"
  row "Vigencia del enlace, recuperación (s)" "$(json_value "$realm" "$RESET_ATTR")" "$RESET_LINK_LIFESPAN"
  echo "Bloqueo de cuenta (RF-AUTH-08):"
  row "Protección contra fuerza bruta" "$(json_value "$realm" bruteForceProtected)" "true"
  row "Bloqueo permanente" "$(json_value "$realm" permanentLockout)" "false"
  row "Intentos fallidos que bloquean" "$(json_value "$realm" failureFactor)" "$LOCKOUT_FAILURES"
  row "Ventana en que se cuentan (s)" "$(json_value "$realm" maxDeltaTimeSeconds)" "$LOCKOUT_WINDOW"
  row "Incremento de espera (s)" "$(json_value "$realm" waitIncrementSeconds)" "$LOCKOUT_DURATION"
  row "Espera máxima (s)" "$(json_value "$realm" maxFailureWaitSeconds)" "$LOCKOUT_DURATION"
  # Tema de login con el texto del error que explica el bloqueo (infra/keycloak/themes/prisma/login).
  row "Tema de login (texto del error de bloqueo)" "$(json_value "$realm" loginTheme)" "prisma"
  echo "Acceso rápido (no se modifica):"
  printf '  ℹ️  minimumQuickLoginWaitSeconds=%s quickLoginCheckMilliSeconds=%s\n' \
    "$(json_value "$realm" minimumQuickLoginWaitSeconds)" "$(json_value "$realm" quickLoginCheckMilliSeconds)"
}

apply_policy() {
  # kcadm update con -s hace merge con el realm actual: no pisa el resto de los atributos.
  kcadm update "realms/$REALM" \
    -s "actionTokenGeneratedByUserLifespan=$RESET_LINK_LIFESPAN" \
    -s "attributes.\"$RESET_ATTR\"=$RESET_LINK_LIFESPAN" \
    -s bruteForceProtected=true \
    -s permanentLockout=false \
    -s "failureFactor=$LOCKOUT_FAILURES" \
    -s "maxDeltaTimeSeconds=$LOCKOUT_WINDOW" \
    -s "waitIncrementSeconds=$LOCKOUT_DURATION" \
    -s "maxFailureWaitSeconds=$LOCKOUT_DURATION" \
    -s loginTheme=prisma
  echo "✅ Política aplicada al realm '$REALM'."
}

MODE="${1:-apply}"
case "$MODE" in
  apply|--apply)    login; apply_policy; show_status ;;
  --status)         login; show_status ;;
  --check)          login; show_status; if [ "$DRIFT" -ne 0 ]; then echo "❌ La política vigente difiere de la esperada."; exit 1; fi ;;
  -h|--help)        sed -n '2,/^# =====/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' ;;
  *) echo "Opción desconocida: $MODE (usar --status, --check o sin argumentos)" >&2; exit 2 ;;
esac
