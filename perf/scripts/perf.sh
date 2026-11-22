#!/usr/bin/env bash
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PERF="$RAIZ/perf"
IMAGEN="${PERF_IMAGEN:-prisma-perf:local}"
UMBRALES_GUARDADOS="$PERF/data/umbrales.properties"
CASOS_P95="CP-RND-01 CP-RND-02 CP-RND-03 CP-RND-04 CP-RND-05 CP-RND-06 CP-TRV-06 CP-TRV-07"
MEDICION="CP-RND-01 CP-RND-02 CP-RND-03 CP-RND-04 CP-RND-05 CP-TRV-06 CP-TRV-07 CP-PERF-UPL-01"
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
    DEST_OPTS=("-Japi_protocolo=${PERF_API_PROTOCOLO:-https}" "-Japi_host=${PERF_API_HOST:-backend-core}" "-Japi_port=${PERF_API_PORT:-8080}" "${auth[@]}")
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

UMBRALES_CORRIDA="P95_MS:p95_ms P99_MS:p99_ms MEDIA_MS:media_ms ERROR_PCT:error_pct THROUGHPUT_MIN:throughput_min MIN_429:min_429"

p95_actual() {
  local caso="$1" guardado
  guardado="$(prop "$caso.p95_ms" "$UMBRALES_GUARDADOS")"
  if [ -n "$guardado" ]; then printf '%s' "$guardado"
  else prop umbral.p95_ms "$PERF/casos/_defaults.properties" "$PERF/casos/$caso.properties"; fi
}

pedir_umbrales() {
  local interactivo=0 caso titulo actual nuevo base especiales
  if [ -t 0 ] && [ "${PERF_SIN_PREGUNTAS:-0}" != 1 ]; then interactivo=1; fi
  mkdir -p "$PERF/data"
  echo
  if [ "$interactivo" = 1 ]; then
    echo "p95 aceptable (en ms) para cada caso. Enter conserva el valor entre corchetes."
    echo "Es el tiempo bajo el cual debe quedar el 95 % de las peticiones para que el caso cumpla el RNF."
  else
    echo "p95 aceptable (en ms) por caso, sin preguntar (no hay terminal interactiva o PERF_SIN_PREGUNTAS=1):"
  fi
  local salida="$PERF/data/umbrales.properties.tmp"
  : > "$salida"
  for caso in $CASOS_P95; do
    titulo="$(prop titulo "$PERF/casos/$caso.properties")"
    actual="$(p95_actual "$caso")"
    base="$(prop umbral.p95_ms "$PERF/casos/_defaults.properties" "$PERF/casos/$caso.properties")"
    especiales="$(sed -n -E 's/^umbral\.p95_ms\.([^=]+)=([0-9.]+).*/\1 \2 ms/p' "$PERF/casos/$caso.properties" | tr -d '\r' | paste -sd, - | sed 's/,/, /g')"
    nuevo="$actual"
    if [ "$interactivo" = 1 ]; then
      [ -n "$especiales" ] && echo "  ($caso tiene umbrales propios que se mantienen si no cambiás el valor: $especiales)"
      while true; do
        read -r -p "  $caso - $titulo [$actual]: " nuevo || nuevo=""
        nuevo="${nuevo:-$actual}"
        if [[ "$nuevo" =~ ^[0-9]+([.][0-9]+)?$ ]]; then break; fi
        echo "    Debe ser un número en milisegundos."
      done
    else
      echo "  $caso: $actual ms"
    fi
    if [ "$nuevo" != "$base" ]; then echo "$caso.p95_ms=$nuevo" >> "$salida"; fi
  done
  mv "$salida" "$UMBRALES_GUARDADOS"
  echo "Guardado en perf/data/umbrales.properties (solo lo que difiere de cada caso). Se usa en make perf y make perf-all; UMBRAL_P95_MS=... los pisa en una ejecución."
}

validar_umbrales() {
  local par var val
  for par in $UMBRALES_CORRIDA; do
    var="UMBRAL_${par%%:*}"
    val="${!var:-}"
    if [ -n "$val" ] && ! [[ "$val" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
      morir "$var debe ser un número (recibí '$val')."
    fi
  done
}

escribir_efectivo() {
  local destino="$1" caso="$2" usuarios="$3"
  shift 3
  validar_umbrales
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
    [ -n "${PROMETHEUS_URL:-}" ] && echo "prometheus_url=$PROMETHEUS_URL"
    [ -n "${ALERTMANAGER_URL:-}" ] && echo "alertmanager_url=$ALERTMANAGER_URL"
    [ -n "${MAILPIT_URL:-}" ] && echo "mailpit_url=$MAILPIT_URL"
    local par var val forzados="" guardado
    guardado="$(prop "$caso.p95_ms" "$UMBRALES_GUARDADOS")"
    if [ -n "$guardado" ]; then
      echo "umbral.p95_ms=$guardado"
      echo "forzar_umbral.p95_ms=1"
      forzados=" p95_ms"
    fi
    for par in $UMBRALES_CORRIDA; do
      var="UMBRAL_${par%%:*}"
      val="${!var:-}"
      if [ -n "$val" ]; then
        echo "umbral.${par##*:}=$val"
        echo "forzar_umbral.${par##*:}=1"
        case " $forzados " in *" ${par##*:} "*) ;; *) forzados="$forzados ${par##*:}" ;; esac
      fi
    done
    [ -n "$forzados" ] && echo "umbrales_de_la_corrida=${forzados# }"
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
    "-Jmuestra_pdf=/perf/$salida_rel/muestra.pdf" \
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
  local caso="$1" fases n rc=0
  local archivo="$PERF/casos/$caso.properties"
  [ -f "$archivo" ] || morir "No existe el caso $caso (perf/casos/$caso.properties). Disponibles: $(cd "$PERF/casos" && ls CP-*.properties | sed 's/.properties//' | tr '\n' ' ')"
  fases="$(prop fases_usuarios "$PERF/casos/_defaults.properties" "$archivo")"
  ULTIMAS_SALIDAS=()
  if [ -n "$fases" ] && [ -z "${USERS:-}" ] && [ -z "${ESCALONES:-}" ]; then
    for n in ${fases//,/ }; do
      correr_una "$caso" "$n" || rc=1
    done
  else
    correr_una "$caso" "" || rc=$?
  fi
  return $rc
}

verificar_observabilidad() {
  local caso="$1" archivo="$2" defs="$PERF/casos/_defaults.properties" obligatorio par nombre url ok=1
  obligatorio="$(prop dos_alerta_obligatoria "$defs" "$archivo")"
  local prom am correo
  prom="${PROMETHEUS_URL:-$(prop prometheus_url "$defs" "$archivo")}"
  am="${ALERTMANAGER_URL:-$(prop alertmanager_url "$defs" "$archivo")}"
  correo="${MAILPIT_URL:-$(prop mailpit_url "$defs" "$archivo")}"
  for par in "Prometheus|$prom|/-/ready" "Alertmanager|$am|/-/ready" "Mailpit|$correo|/api/v1/info"; do
    nombre="${par%%|*}"
    url="${par#*|}"
    ruta_url="${url#*|}"
    url="${url%%|*}"
    [ -n "$url" ] || continue
    if contenedor "$DEST_RED" "$IMAGEN" wget -q -T 6 -O /dev/null "$url$ruta_url" >/dev/null 2>&1; then
      echo "   $nombre responde en $url"
    else
      echo "   $nombre NO responde en $url" >&2
      ok=0
    fi
  done
  if [ "$ok" = 0 ]; then
    echo "   Por la VPN suelen ser: PROMETHEUS_URL=http://10.8.0.1:9090 ALERTMANAGER_URL=http://10.8.0.1:9093 MAILPIT_URL=http://10.8.0.1:8025" >&2
    if [ "$obligatorio" = "true" ]; then
      echo "ERROR: $caso necesita llegar a Prometheus, Alertmanager y Mailpit antes de lanzar la ráfaga. Corregí las URL y repetí. No se lanzó la carga." >&2
      return 1
    fi
    echo "   Sin Prometheus no hay métricas del servidor en el informe." >&2
  fi
  return 0
}

correr_una() {
  local caso="$1" fase="$2"
  local usuarios_corrida="${2:-${USERS:-}}"
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
  verificar_observabilidad "$caso" "$archivo" || return 1

  local sello salida_rel
  sello="$(date -u +%Y%m%d-%H%M%S)"
  salida_rel="results/$sello-$caso"
  local fase_extra=()
  if [ -n "$fase" ]; then
    salida_rel="$salida_rel-${fase}u"
    fase_extra=("titulo=$(prop titulo "$archivo") (fase de $fase usuario(s))")
  fi
  mkdir -p "$PERF/$salida_rel"
  echo "== $caso${fase:+ ($fase usuario(s))} | plan $plan | destino: $DEST_DESC"

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
    local rec_s rec_dir
    rec_s="${RECUPERACION_S:-$(prop recuperacion_s "$defs" "$archivo")}"
    if [ -n "$rec_s" ] && [ "$rec_s" != 0 ]; then
      rec_dir="$salida_rel/recuperacion"
      mkdir -p "$PERF/$rec_dir"
      escribir_efectivo "$PERF/$rec_dir" "$caso" "$(prop recuperacion_usuarios "$defs" "$archivo")" "modo=$modo" \
        "duracion_s=$rec_s" "rampa_s=1" "iteraciones=-1" "pausa_ms=$(prop recuperacion_pausa_ms "$defs" "$archivo")" "escalones="
      echo "-- recuperación: $rec_s s con $(prop recuperacion_usuarios "$defs" "$archivo") usuario(s), sin reiniciar nada"
      lanzar_jmeter "$rec_dir" "$plan"
      resumir --dir "/perf/$rec_dir" || true
    fi
    escribir_efectivo "$PERF/$salida_rel" "$caso" "" "modo=$modo" "escalones=$escalones"
    resumir --dir "/perf/$salida_rel" --estres || rc=$?
  else
    escribir_efectivo "$PERF/$salida_rel" "$caso" "$usuarios_corrida" "modo=$modo" ${fase_extra[@]+"${fase_extra[@]}"}
    lanzar_jmeter "$salida_rel" "$plan"
    resumir --dir "/perf/$salida_rel" || rc=$?
  fi
  generar_pdf --dir "/perf/$salida_rel"
  echo "   Reporte PDF: perf/$salida_rel/informe.pdf"
  echo "   Detalle: perf/$salida_rel/resumen.md y perf/$salida_rel/html/index.html"
  ULTIMAS_SALIDAS+=("$salida_rel")
  return $rc
}

cmd_run() {
  [ -n "${1:-}" ] || morir "Indicá el caso: make perf CASO=CP-RND-01"
  correr_caso "$1"
}

cmd_all() {
  local casos="$MEDICION" caso salida fallo=0 dirs=() incumplidos=""
  [ "${INCLUIR_DISRUPTIVOS:-0}" = 1 ] && casos="$casos $DISRUPTIVOS"
  for caso in $casos; do
    case " ${EXCLUIR:-} " in *" $caso "*) echo "== $caso: excluido (EXCLUIR)"; continue ;; esac
    case ",${EXCLUIR:-}," in *",$caso,"*) echo "== $caso: excluido (EXCLUIR)"; continue ;; esac
    if correr_caso "$caso"; then :; else fallo=1; incumplidos="$incumplidos $caso"; fi
    for salida in ${ULTIMAS_SALIDAS[@]+"${ULTIMAS_SALIDAS[@]}"}; do dirs+=("/perf/$salida"); done
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
    api="${PERF_API_PROTOCOLO:-https}://${PERF_API_HOST:-backend-core}:${PERF_API_PORT:-8080}"
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
  setup) cmd_datos setup_data.py; pedir_umbrales ;;
  umbrales) pedir_umbrales ;;
  run) cmd_run "${2:-}" ;;
  all) cmd_all ;;
  clean) cmd_clean ;;
  *) echo "Uso: perf.sh build | setup | umbrales | run <CASO> | all | clean"; exit 2 ;;
esac
