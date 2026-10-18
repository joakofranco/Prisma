#!/usr/bin/env bash
# =====================================================================
# MFA (TOTP) obligatoria en Keycloak para ciertos roles del realm "prisma"
# ---------------------------------------------------------------------
# Por defecto: PRISMA_ADMIN y ORG_RESPONSIBLE (variable MFA_ROLES).
#
# Qué hace (idempotente, se puede correr las veces que haga falta):
#   1. Crea el flujo de login "browser-mfa-por-rol" (copia de "browser") con un
#      sub-flujo condicional por rol: si el usuario tiene el rol y todavía NO
#      tiene OTP, se le exige registrar TOTP (alta con QR) en ese login. Quien ya
#      tiene OTP sigue pasando por el "Conditional 2FA" original de Keycloak.
#   2. Lo vincula como "browser flow" del realm.
#   3. Crea el flujo "direct-grant-sin-otp" y lo asigna SOLO al cliente
#      "prisma-backend": backend-core valida la contraseña actual con direct grant
#      (KeycloakAdminClient.verifyPassword) y no puede enviar el código OTP.
#   4. otpPolicyLookAheadWindow=1 (tolerancia de reloj de +-30 s).
#
# Uso:
#   scripts/keycloak/enable-mfa.sh            # activa (o repara) la MFA
#   scripts/keycloak/enable-mfa.sh --status   # muestra el estado actual
#   scripts/keycloak/enable-mfa.sh --disable  # vuelve al flujo "browser" original
#
# Requisitos: contenedor de Keycloak levantado (docker compose --profile security
# up -d keycloak) y KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD en .env o en el entorno.
# Variables opcionales: REALM (prisma), KEYCLOAK_CONTAINER (prisma-keycloak),
# MFA_ROLES ("PRISMA_ADMIN ORG_RESPONSIBLE").
#
# Nota: --import-realm solo importa realm-prisma.json la PRIMERA vez que se crea el
# realm; por eso este script se corre en cada ambiente después del primer arranque.
# =====================================================================
set -euo pipefail

# Git Bash en Windows convierte rutas tipo /opt/... en argumentos de docker exec.
export MSYS_NO_PATHCONV=1

REALM="${REALM:-prisma}"
KC_CONTAINER="${KEYCLOAK_CONTAINER:-prisma-keycloak}"
MFA_ROLES="${MFA_ROLES:-PRISMA_ADMIN ORG_RESPONSIBLE}"
BROWSER_FLOW="browser"
MFA_BROWSER_FLOW="browser-mfa-por-rol"
MFA_FORMS_FLOW="${MFA_BROWSER_FLOW} forms"
CONDITIONAL_2FA_FLOW="${MFA_BROWSER_FLOW} Browser - Conditional 2FA"
DIRECT_GRANT_FLOW="direct grant"
NO_OTP_DIRECT_GRANT_FLOW="direct-grant-sin-otp"
BACKEND_CLIENT="prisma-backend"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then
  # Solo se leen las dos variables que hacen falta (.env no siempre es "sourceable").
  : "${KEYCLOAK_ADMIN:=$(grep -E '^KEYCLOAK_ADMIN=' "$ROOT_DIR/.env" | head -1 | cut -d= -f2- | tr -d '"\r')}"
  : "${KEYCLOAK_ADMIN_PASSWORD:=$(grep -E '^KEYCLOAK_ADMIN_PASSWORD=' "$ROOT_DIR/.env" | head -1 | cut -d= -f2- | tr -d '"\r')}"
fi
: "${KEYCLOAK_ADMIN:?Falta KEYCLOAK_ADMIN (en .env o en el entorno)}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Falta KEYCLOAK_ADMIN_PASSWORD (en .env o en el entorno)}"

kcadm() { docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
log()   { echo "  $*"; }
enc()   { printf '%s' "$1" | sed 's/ /%20/g'; }   # alias con espacios -> path de la API

login() {
  if ! docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER"; then
    echo "❌ El contenedor $KC_CONTAINER no está corriendo." >&2; exit 1
  fi
  kcadm config credentials --server http://localhost:8080/auth --realm master \
    --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
}

# ---- helpers sobre flujos --------------------------------------------------
flow_id() {  # alias -> id ("" si no existe)
  kcadm get authentication/flows -r "$REALM" --fields id,alias --format csv --noquotes \
    | awk -F, -v a="$1" '{ id=$1; sub(/^[^,]*,/, ""); if ($0 == a) print id }' | head -1
}

# Imprime "id,requirement" de la ejecución con ese displayName dentro del flujo dado.
exec_info() {  # flowAlias displayName
  kcadm get "authentication/flows/$(enc "$1")/executions" -r "$REALM" \
    --fields id,displayName,requirement --format csv --noquotes \
    | awk -F, -v n="$2" '{ id=$1; req=$NF; name=$2; for (i=3;i<NF;i++) name=name "," $i; if (name == n) print id "," req }' | head -1
}

set_requirement() {  # flowAlias executionId REQUIREMENT
  kcadm update "authentication/flows/$(enc "$1")/executions" -r "$REALM" \
    -b "{\"id\":\"$2\",\"requirement\":\"$3\"}"
}

# Añade una ejecución (autenticador/condición) a un flujo; devuelve su id.
add_execution() {  # flowAlias providerId
  kcadm create "authentication/flows/$(enc "$1")/executions/execution" -r "$REALM" \
    -s "provider=$2" -i
}

set_config() {  # executionId alias json-config
  kcadm create "authentication/executions/$1/config" -r "$REALM" \
    -b "{\"alias\":\"$2\",\"config\":$3}" >/dev/null
}

# Crea un sub-flujo CONDITIONAL dentro de un flujo y devuelve su alias.
add_conditional_subflow() {  # parentFlowAlias subflowAlias priority
  kcadm create "authentication/flows/$(enc "$1")/executions/flow" -r "$REALM" \
    -s "alias=$2" -s type=basic-flow -s provider=registration-page-form \
    -s "description=MFA obligatorio por rol" >/dev/null
  local info; info="$(exec_info "$1" "$2")"
  set_priority "$1" "${info%%,*}" CONDITIONAL "$3"
}

# Los sub-flujos de MFA por rol tienen que ejecutarse DESPUÉS del usuario/clave (Keycloak aún
# no sabe quién es) y del "Conditional 2FA" original (su condición mira si ese sub-flujo se
# ejecutó). En "forms" esos dos tienen prioridad 10 y 20; los nuestros arrancan en 30.
set_priority() {  # flowAlias executionId REQUIREMENT priority
  kcadm update "authentication/flows/$(enc "$1")/executions" -r "$REALM" \
    -b "{\"id\":\"$2\",\"requirement\":\"$3\",\"priority\":$4}"
}

# ---- activar ---------------------------------------------------------------
enable_mfa() {
  echo "▶ Activando MFA obligatoria para: $MFA_ROLES (realm $REALM)"

  # 1) Flujo de login por rol
  if [ -z "$(flow_id "$MFA_BROWSER_FLOW")" ]; then
    kcadm create "authentication/flows/$BROWSER_FLOW/copy" -r "$REALM" -s "newName=$MFA_BROWSER_FLOW" >/dev/null
    log "flujo '$MFA_BROWSER_FLOW' creado (copia de '$BROWSER_FLOW')"
  else
    log "flujo '$MFA_BROWSER_FLOW' ya existe"
  fi

  local previous_roles="" role sub info prio=30
  for role in $MFA_ROLES; do
    sub="MFA obligatorio $role"
    info="$(exec_info "$MFA_FORMS_FLOW" "$sub")"
    if [ -n "$info" ]; then
      log "sub-flujo '$sub' ya existe"
      set_priority "$MFA_FORMS_FLOW" "${info%%,*}" CONDITIONAL "$prio"
    else
      add_conditional_subflow "$MFA_FORMS_FLOW" "$sub" "$prio"

      # Condición 1: el usuario tiene el rol
      local c; c="$(add_execution "$sub" conditional-user-role)"
      set_requirement "$sub" "$c" REQUIRED
      set_config "$c" "mfa-rol-$role" "{\"condUserRole\":\"$role\",\"negate\":\"false\"}"

      # Condiciones extra: no tiene un rol anterior de la lista (así el OTP se pide una sola vez)
      local prev
      for prev in $previous_roles; do
        c="$(add_execution "$sub" conditional-user-role)"
        set_requirement "$sub" "$c" REQUIRED
        set_config "$c" "mfa-rol-$role-sin-$prev" "{\"condUserRole\":\"$prev\",\"negate\":\"true\"}"
      done

      # Condición 2: el "Conditional 2FA" original NO se ejecutó (usuario sin OTP registrado)
      c="$(add_execution "$sub" conditional-sub-flow-executed)"
      set_requirement "$sub" "$c" REQUIRED
      set_config "$c" "mfa-rol-$role-sin-otp" \
        "{\"flow_to_check\":\"$CONDITIONAL_2FA_FLOW\",\"check_result\":\"not-executed\"}"

      # Acción: OTP obligatorio (sin OTP registrado dispara el alta de TOTP)
      local otp; otp="$(add_execution "$sub" auth-otp-form)"
      set_requirement "$sub" "$otp" REQUIRED
      log "sub-flujo '$sub' creado"
    fi
    previous_roles="$previous_roles $role"
    prio=$((prio + 10))
  done

  # 2) Vincular como browser flow + tolerancia de reloj del OTP
  kcadm update "realms/$REALM" -s "browserFlow=$MFA_BROWSER_FLOW" -s otpPolicyLookAheadWindow=1
  log "browser flow del realm = '$MFA_BROWSER_FLOW'; otpPolicyLookAheadWindow=1"

  # 3) direct grant sin OTP, solo para prisma-backend
  if [ -z "$(flow_id "$NO_OTP_DIRECT_GRANT_FLOW")" ]; then
    kcadm create "authentication/flows/$(enc "$DIRECT_GRANT_FLOW")/copy" -r "$REALM" \
      -s "newName=$NO_OTP_DIRECT_GRANT_FLOW" >/dev/null
    log "flujo '$NO_OTP_DIRECT_GRANT_FLOW' creado (copia de '$DIRECT_GRANT_FLOW')"
  fi
  local otp_sub; otp_sub="$(exec_info "$NO_OTP_DIRECT_GRANT_FLOW" "$NO_OTP_DIRECT_GRANT_FLOW Direct Grant - Conditional OTP")"
  if [ -n "$otp_sub" ] && [ "${otp_sub##*,}" != "DISABLED" ]; then
    set_requirement "$NO_OTP_DIRECT_GRANT_FLOW" "${otp_sub%%,*}" DISABLED
  fi
  local client_id; client_id="$(kcadm get clients -r "$REALM" -q "clientId=$BACKEND_CLIENT" --fields id --format csv --noquotes | head -1)"
  kcadm update "clients/$client_id" -r "$REALM" \
    -s "authenticationFlowBindingOverrides={\"direct_grant\":\"$(flow_id "$NO_OTP_DIRECT_GRANT_FLOW")\"}"
  log "cliente '$BACKEND_CLIENT': direct grant sin OTP (solo ese cliente)"

  echo "✅ MFA activa. Los usuarios con rol $MFA_ROLES deberán registrar TOTP en su próximo login."
}

# ---- desactivar ------------------------------------------------------------
disable_mfa() {
  echo "▶ Desactivando MFA obligatoria por rol (realm $REALM)"
  local client_id; client_id="$(kcadm get clients -r "$REALM" -q "clientId=$BACKEND_CLIENT" --fields id --format csv --noquotes | head -1)"
  kcadm update "clients/$client_id" -r "$REALM" -s 'authenticationFlowBindingOverrides={"direct_grant":null}'
  kcadm update "realms/$REALM" -s "browserFlow=$BROWSER_FLOW"
  local f id
  for f in "$MFA_BROWSER_FLOW" "$NO_OTP_DIRECT_GRANT_FLOW"; do
    id="$(flow_id "$f")"
    if [ -n "$id" ]; then kcadm delete "authentication/flows/$id" -r "$REALM"; log "flujo '$f' eliminado"; fi
  done
  echo "✅ Vuelve a regir el flujo '$BROWSER_FLOW' (MFA solo voluntaria). Las credenciales OTP ya registradas se conservan."
}

# ---- estado ----------------------------------------------------------------
status() {
  echo "▶ Estado de MFA (realm $REALM)"
  log "browser flow   : $(kcadm get "realms/$REALM" --fields browserFlow --format csv --noquotes)"
  log "OTP policy     : $(kcadm get "realms/$REALM" --fields otpPolicyType,otpPolicyAlgorithm,otpPolicyDigits,otpPolicyPeriod,otpPolicyLookAheadWindow --format csv --noquotes)"
  local client_id; client_id="$(kcadm get clients -r "$REALM" -q "clientId=$BACKEND_CLIENT" --fields id --format csv --noquotes | head -1)"
  local ov; ov="$(kcadm get "clients/$client_id" -r "$REALM" | grep -o '"direct_grant" : "[^"]*"' | cut -d'"' -f4 || true)"
  if [ -n "$ov" ] && [ "$ov" = "$(flow_id "$NO_OTP_DIRECT_GRANT_FLOW")" ]; then
    log "direct grant de $BACKEND_CLIENT : $NO_OTP_DIRECT_GRANT_FLOW (sin OTP, solo ese cliente)"
  else
    log "direct grant de $BACKEND_CLIENT : flujo del realm (con OTP condicional)"
  fi
  local role u id has_otp
  for role in $MFA_ROLES; do
    echo "  Rol $role:"
    kcadm get "roles/$role/users" -r "$REALM" --fields id,username --format csv --noquotes | while IFS=, read -r id u; do
      has_otp="$(kcadm get "users/$id/credentials" -r "$REALM" --fields type --format csv --noquotes | grep -cx otp || true)"
      echo "    - $u : $([ "$has_otp" -ge 1 ] && echo 'OTP registrado' || echo 'sin OTP (se le pedirá registrar TOTP al entrar)')"
    done
  done
}

login
case "${1:-}" in
  ""|--enable)  enable_mfa ;;
  --status)     status ;;
  --disable)    disable_mfa ;;
  *) echo "Uso: $0 [--enable|--status|--disable]" >&2; exit 2 ;;
esac
