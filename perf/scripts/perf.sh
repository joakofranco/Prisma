#!/usr/bin/env bash
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PERF="$RAIZ/perf"
IMAGEN="${PERF_IMAGEN:-prisma-perf:local}"
MEDICION="CP-RND-01 CP-RND-02 CP-RND-03 CP-RND-04 CP-RND-05 CP-TRV-06 CP-TRV-07"
DISRUPTIVOS="CP-RND-06 CP-OBS-08"

export MSYS_NO_PATHCONV=1
export DOCKER_CLI_HINTS=false

morir() { echo "ERROR: $*" >&2; exit 1; }

ruta_host() {
  if (cd "$1" && pwd -W >/dev/null 2>&1); then (cd "$1" && pwd -W); else (cd "$1" && pwd); fi
}

prop() {
  local clave="$1" archivo valor=""
  shift
  for archivo in "$@"; do
    if [ -f "$archivo" ] && grep -qE "^${clave}=" "$archivo"; then
      valor="$(grep -E "^${clave}=" "$archivo" | tail -1 | cut -d= -f2- | tr -d '\r')"
    fi
  done
  printf '%s' "$valor"
}

asegurar_imagen() {
  if ! docker image inspect "$IMAGEN" >/dev/null 2>&1; then
    echo "Construyendo la imagen $IMAGEN (solo la primera vez)..."
    docker build -q -t "$IMAGEN" "$PERF" >/dev/null
  fi
}

detectar_red() {
  if [ -n "${PERF_NETWORK:-}" ]; then printf '%s' "$PERF_NETWORK"; return; fi
  local red
  for red in prisma-swarm-net prisma-net; do
    if docker network inspect "$red" >/dev/null 2>&1; then
      if [ "$red" = "prisma-swarm-net" ] && ! docker service inspect prisma_backend-core >/dev/null 2>&1; then
        continue
      fi
      printf '%s' "$red"; return
    fi
  done
  printf ''
}

secreto_cliente() {
  if [ -n "${PERF_CLIENT_SECRET:-}" ]; then printf '%s' "$PERF_CLIENT_SECRET"; return; fi
  prop KEYCLOAK_ADMIN_CLIENT_SECRET "$RAIZ/.env"
}

es_local() {
  case "$1" in
    localhost|127.0.0.1|::1|nginx|backend-core|*.localhost|*.test) return 0 ;;
    *) return 1 ;;
  esac
}

partes_url() {
  local url="$1" resto
  URL_PROTO="${url%%://*}"
  resto="${url#*://}"
  resto="${resto%%/*}"
  URL_HOST="${resto%%:*}"
  if [ "$resto" != "$URL_HOST" ]; then URL_PORT="${resto##*:}"
  elif [ "$URL_PROTO" = "https" ]; then URL_PORT=443
  else URL_PORT=80; fi
}

contenedor() {
  local red="$1"
  shift
  local args=(--rm -v "$(ruta_host "$PERF"):/perf" -w /perf)
  [ -n "$red" ] && args+=(--network "$red")
  docker run "${args[@]}" "$@"
}

destino() {
  local modo="$1"
  DEST_RED="$(detectar_red)"
  DEST_OPTS=()
  DEST_DESC=""
  if [ -n "${BASE_URL:-}" ]; then
    partes_url "$BASE_URL"
    DEST_REMOTO=1
    es_local "$URL_HOST" && DEST_REMOTO=0
    DEST_OPTS=("-Japi_protocolo=$URL_PROTO" "-Japi_host=$URL_HOST" "-Japi_port=$URL_PORT"
               "-Jauth_protocolo=$URL_PROTO" "-Jauth_host=$URL_HOST" "-Jauth_port=$URL_PORT"
               "-Jauth_host_header=${AUTH_HOST_HEADER:-$URL_HOST}")
    DEST_DESC="$BASE_URL (por el borde)"
    if [ "$DEST_REMOTO" = 1 ]; then DEST_RED=""; fi
    return
  fi
  DEST_REMOTO=0
  [ -n "$DEST_RED" ] || morir "No encuentro la red Docker del stack (prisma-net o prisma-swarm-net). Levantá el stack o definí PERF_NETWORK o BASE_URL."
  local auth=("-Jauth_protocolo=https" "-Jauth_host=${PERF_AUTH_HOST:-nginx}" "-Jauth_port=${PERF_AUTH_PORT:-443}"
              "-Jauth_host_header=${AUTH_HOST_HEADER:-localhost}")
  if [ "$modo" = "borde" ]; then
    DEST_OPTS=("-Japi_protocolo=https" "-Japi_host=nginx" "-Japi_port=443" "${auth[@]}")
    DEST_DESC="nginx:443 en la red $DEST_RED (por el borde, con límites)"
  else
    DEST_OPTS=("-Japi_protocolo=https" "-Japi_host=${PERF_API_HOST:-backend-core}" "-Japi_port=${PERF_API_PORT:-8080}" "${auth[@]}")
    DEST_DESC="backend-core:8080 en la red $DEST_RED (directo a la aplicación)"
  fi
}

validar_permisos() {
  local caso="$1" disruptivo="$2"
  if [ "$DEST_REMOTO" = 1 ] && [ "${PERF_PERMITIR_REMOTO:-0}" != 1 ]; then
    morir "El destino $BASE_URL no es local. Para correr contra un entorno remoto definí PERF_PERMITIR_REMOTO=1."
  fi
  if [ "$disruptivo" = "true" ] && [ "${PERF_PERMITIR_DISRUPTIVO:-0}" != 1 ]; then
    morir "$caso es disruptivo (estrés o inundación): puede degradar el servicio y provocar baneos de fail2ban. Definí PERF_PERMITIR_DISRUPTIVO=1 para correrlo."
  fi
}

escribir_efectivo() {
  local destino="$1" caso="$2" usuarios="$3"
  shift 3
  {
    cat "$PERF/casos/_defaults.properties"
    echo
    cat "$PERF/casos/$caso.properties"
    echo
    echo "caso=$caso"
    echo "objetivo=$DEST_DESC"
    echo "commit=$(cd "$RAIZ" && git rev-parse --short HEAD 2>/dev/null || echo desconocido)"
    echo "fecha=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    [ -n "$usuarios" ] && echo "usuarios=$usuarios"
    [ -n "${RAMP:-}" ] && echo "rampa_s=$RAMP"
    [ -n "${DURATION:-}" ] && echo "duracion_s=$DURATION"
    [ -n "${ITERACIONES:-}" ] && echo "iteraciones=$ITERACIONES"
    [ -n "${PAUSA_MS:-}" ] && echo "pausa_ms=$PAUSA_MS"
    [ -n "${ENDPOINTS:-}" ] && echo "endpoints=$ENDPOINTS"
    local extra
    for extra in "$@"; do echo "$extra"; done
  } | tr -d '\r' > "$destino/efectivo.properties.tmp"
  awk -F= '/^[^#]/ && NF>1 { k=$1; sub(/^[^=]*=/, ""); v[k]=$0; if (!(k in o)) { o[k]=++n; kk[n]=k } } END { for (i=1;i<=n;i++) print kk[i] "=" v[kk[i]] }' \
    "$destino/efectivo.properties.tmp" > "$destino/efectivo.properties"
  rm -f "$destino/efectivo.properties.tmp"
}

lanzar_jmeter() {
  local salida_rel="$1" plan="$2"
  local secretos="$PERF/data/.secretos.properties"
  mkdir -p "$PERF/data"
  ( umask 077; printf 'auth_client_secret=%s\n' "$(secreto_cliente)" > "$secretos" )
  [ -f "$PERF/data/datos.properties" ] || morir "Falta perf/data/datos.properties. Corré primero: make perf-setup"
  local extra=()
  if [ -n "${PERF_OPTS:-}" ]; then read -r -a extra <<< "$PERF_OPTS"; fi
  set +e
  contenedor "$DEST_RED" "$IMAGEN" jmeter -n \
    -t "/perf/jmeter/$plan.jmx" \
    -q /perf/data/datos.properties \
    -q "/perf/$salida_rel/efectivo.properties" \
    -q /perf/data/.secretos.properties \
    ${DEST_OPTS[@]+"${DEST_OPTS[@]}"} ${extra[@]+"${extra[@]}"} \
    -Jjmeter.save.saveservice.output_format=csv \
    -l "/perf/$salida_rel/resultados.jtl" \
    -j "/perf/$salida_rel/jmeter.log" \
    -e -o "/perf/$salida_rel/html" > "$PERF/$salida_rel/consola.log" 2>&1
  local rc=$?
  set -e
  rm -f "$secretos"
  if [ $rc -ne 0 ]; then
    tail -15 "$PERF/$salida_rel/consola.log" >&2
    morir "JMeter terminó con error (ver $salida_rel/consola.log y jmeter.log)"
  fi
}

resumir() {
  contenedor "$DEST_RED" "$IMAGEN" python3 /perf/scripts/summary.py "$@"
}

generar_pdf() {
  if ! contenedor "$DEST_RED" "$IMAGEN" python3 /perf/scripts/informe_pdf.py "$@" 2>"$PERF/results/.pdf-error.log"; then
    echo "AVISO: no se pudo generar el PDF (detalle en perf/results/.pdf-error.log)" >&2
    return 0
  fi
  rm -f "$PERF/results/.pdf-error.log"
}

correr_caso() {
  local caso="$1"
  local archivo="$PERF/casos/$caso.properties"
  [ -f "$archivo" ] || morir "No existe el caso $caso (perf/casos/$caso.properties). Disponibles: $(cd "$PERF/casos" && ls CP-*.properties | sed 's/.properties//' | tr '\n' ' ')"
  local defs="$PERF/casos/_defaults.properties"
  local plan modo disruptivo escalones
  plan="$(prop plan "$defs" "$archivo")"
  modo="${MODO:-$(prop modo "$defs" "$archivo")}"
  disruptivo="$(prop disruptivo "$defs" "$archivo")"
  escalones="${ESCALONES:-$(prop escalones "$defs" "$archivo")}"
  [ -f "$PERF/jmeter/$plan.jmx" ] || morir "El caso $caso usa el plan '$plan', que no existe en perf/jmeter"

  destino "$modo"
  validar_permisos "$caso" "$disruptivo"
  asegurar_imagen

  local sello salida_rel
  sello="$(date -u +%Y%m%d-%H%M%S)"
  salida_rel="results/$sello-$caso"
  mkdir -p "$PERF/$salida_rel"
  echo "== $caso | plan $plan | destino: $DEST_DESC"

  local rc=0
  if [ -n "$escalones" ]; then
    local n paso
    for n in ${escalones//,/ }; do
      paso="$salida_rel/paso-$(printf '%04d' "$n")"
      mkdir -p "$PERF/$paso"
      escribir_efectivo "$PERF/$paso" "$caso" "$n" "modo=$modo"
      echo "-- escalón de $n usuarios"
      lanzar_jmeter "$paso" "$plan"
      if ! resumir --dir "/perf/$paso"; then
        echo "-- umbrales superados con $n usuarios: se detiene la escalada"
        break
      fi
    done
    escribir_efectivo "$PERF/$salida_rel" "$caso" "" "modo=$modo" "escalones=$escalones"
    resumir --dir "/perf/$salida_rel" --estres || rc=$?
  else
    escribir_efectivo "$PERF/$salida_rel" "$caso" "${USERS:-}" "modo=$modo"
    lanzar_jmeter "$salida_rel" "$plan"
    resumir --dir "/perf/$salida_rel" || rc=$?
  fi
  generar_pdf --dir "/perf/$salida_rel"
  echo "   Reporte PDF: perf/$salida_rel/informe.pdf"
  echo "   Detalle: perf/$salida_rel/resumen.md y perf/$salida_rel/html/index.html"
  ULTIMA_SALIDA="$salida_rel"
  return $rc
}

cmd_run() {
  [ -n "${1:-}" ] || morir "Indicá el caso: make perf CASO=CP-RND-01"
  correr_caso "$1"
}

cmd_all() {
  local casos="$MEDICION" caso fallo=0 dirs=() incumplidos=""
  [ "${INCLUIR_DISRUPTIVOS:-0}" = 1 ] && casos="$casos $DISRUPTIVOS"
  for caso in $casos; do
    if correr_caso "$caso"; then :; else fallo=1; incumplidos="$incumplidos $caso"; fi
    [ -n "${ULTIMA_SALIDA:-}" ] && dirs+=("/perf/$ULTIMA_SALIDA")
    ULTIMA_SALIDA=""
  done
  local informe="results/$(date -u +%Y%m%d-%H%M%S)-informe-consolidado.md"
  resumir --consolidar ${dirs[@]+"${dirs[@]}"} --salida "/perf/$informe" >/dev/null || true
  generar_pdf --consolidar ${dirs[@]+"${dirs[@]}"} --salida "/perf/${informe%.md}.pdf"
  echo "Informe consolidado: perf/$informe"
  echo "Informe PDF consolidado (todos los resultados y gráficas): perf/${informe%.md}.pdf"
  if [ $fallo -ne 0 ]; then
    echo "RESULTADO: no cumplen sus umbrales:$incumplidos. Los informes se generaron igual; make termina con error para señalarlo."
  else
    echo "RESULTADO: todos los casos cumplen sus umbrales."
  fi
  return $fallo
}

cmd_datos() {
  local script="$1"
  shift
  destino app
  if [ "$DEST_REMOTO" = 1 ] && [ "${PERF_PERMITIR_REMOTO:-0}" != 1 ]; then
    morir "El destino $BASE_URL no es local. Definí PERF_PERMITIR_REMOTO=1 para crear o borrar datos PERF- en un entorno remoto."
  fi
  asegurar_imagen
  [ -f "$PERF/data/admin.env" ] || morir "Falta perf/data/admin.env con PERF_ADMIN_USER y PERF_ADMIN_PASSWORD (ver perf/README.md)."
  local api auth header
  if [ -n "${BASE_URL:-}" ]; then
    api="$BASE_URL"; auth="$BASE_URL"; header="${AUTH_HOST_HEADER:-$URL_HOST}"
  else
    api="https://${PERF_API_HOST:-backend-core}:${PERF_API_PORT:-8080}"
    auth="https://${PERF_AUTH_HOST:-nginx}:${PERF_AUTH_PORT:-443}"
    header="${AUTH_HOST_HEADER:-localhost}"
  fi
  local args=(--rm -v "$(ruta_host "$PERF"):/perf" -w /perf --env-file "$(ruta_host "$PERF/data")/admin.env"
              -e "PERF_API_URL=$api" -e "PERF_AUTH_URL=$auth" -e "PERF_AUTH_HOST_HEADER=$header"
              -e "PERF_CLIENT_SECRET=$(secreto_cliente)" -e "PERF_USUARIOS=${PERF_USUARIOS:-20}")
  [ -n "$DEST_RED" ] && args+=(--network "$DEST_RED")
  docker run "${args[@]}" "$IMAGEN" python3 "/perf/scripts/$script" "$@"
}

cmd_clean() {
  cmd_datos cleanup_data.py
  if [ "${BORRAR_RESULTADOS:-0}" = 1 ]; then
    rm -rf "$PERF/results"/*
    echo "Resultados borrados (perf/results)."
  else
    echo "Los resultados en perf/results se conservan. Para borrarlos: BORRAR_RESULTADOS=1"
  fi
}

case "${1:-}" in
  build) docker build -t "$IMAGEN" "$PERF" ;;
  setup) cmd_datos setup_data.py ;;
  run) cmd_run "${2:-}" ;;
  all) cmd_all ;;
  clean) cmd_clean ;;
  *) echo "Uso: perf.sh build | setup | run <CASO> | all | clean"; exit 2 ;;
esac
