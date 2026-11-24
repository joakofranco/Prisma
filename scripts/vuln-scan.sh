#!/usr/bin/env bash
# Escaneo de vulnerabilidades de PRISMA con Trivy: imágenes de la stack y dependencias del código.
# Los resultados se guardan en vuln-reports/ y se publican en el Pushgateway para que Prometheus y
# el tablero «PRISMA · Vulnerabilidades» de Grafana los muestren. Ver docs/operacion/Vulnerabilidades.md.
#
# Se puede correr a mano (make vuln-scan, usa contenedores de Trivy y Python) o desde el servicio
# vuln-scanner del compose, que lo ejecuta una vez por día con VULN_NATIVO=1 (Trivy y Python ya
# están dentro de su imagen).
set -euo pipefail

RAIZ="${VULN_RAIZ_CODIGO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRIVY_IMAGEN="${TRIVY_IMAGEN:-aquasec/trivy:0.58.1}"
PYTHON_IMAGEN="${VULN_PYTHON_IMAGEN:-python:3.14-alpine}"
CURL_IMAGEN="${VULN_CURL_IMAGEN:-curlimages/curl:8.11.0}"
PUSHGATEWAY_URL="${PUSHGATEWAY_URL:-http://localhost:9091}"
SALIDA="${VULN_SALIDA:-$RAIZ/vuln-reports}"
SOLO="${VULN_SOLO:-todo}"
NATIVO="${VULN_NATIVO:-0}"

export MSYS_NO_PATHCONV=1

morir() { echo "ERROR: $*" >&2; exit 1; }

ruta_host() {
  if (cd "$1" && pwd -W >/dev/null 2>&1); then (cd "$1" && pwd -W); else (cd "$1" && pwd); fi
}

case "$SOLO" in
  todo|imagenes|dependencias) ;;
  *) morir "VULN_SOLO debe ser todo, imagenes o dependencias (recibí '$SOLO')." ;;
esac
docker info >/dev/null 2>&1 || morir "Docker no responde."

rm -rf "$SALIDA"
mkdir -p "$SALIDA"
: > "$SALIDA/manifiesto.tsv"
if [ "$NATIVO" != 1 ]; then
  RAIZ_HOST="$(ruta_host "$RAIZ")"
  SALIDA_HOST="$(ruta_host "$SALIDA")"
fi

trivy_comun=(--format json --scanners vuln --severity UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL --quiet)
[ "${VULN_SOLO_CON_PARCHE:-0}" = 1 ] && trivy_comun+=(--ignore-unfixed)
entorno=()
[ -n "${TRIVY_DB_REPOSITORY:-}" ] && entorno+=(-e "TRIVY_DB_REPOSITORY=$TRIVY_DB_REPOSITORY")
[ -n "${TRIVY_JAVA_DB_REPOSITORY:-}" ] && entorno+=(-e "TRIVY_JAVA_DB_REPOSITORY=$TRIVY_JAVA_DB_REPOSITORY")

nombre_archivo() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'; }

registrar() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$SALIDA/manifiesto.tsv"
}

trivy_imagen() {
  if [ "$NATIVO" = 1 ]; then
    trivy image "${trivy_comun[@]}" "$1"
  else
    docker run --rm "${entorno[@]+"${entorno[@]}"}" -v /var/run/docker.sock:/var/run/docker.sock \
      -v prisma-trivy-cache:/root/.cache/ "$TRIVY_IMAGEN" image "${trivy_comun[@]}" "$1"
  fi
}

trivy_carpeta() {
  if [ "$NATIVO" = 1 ]; then
    trivy fs "${trivy_comun[@]}" --skip-dirs node_modules,target,dist --skip-files pom.xml "$RAIZ/$1"
  else
    docker run --rm "${entorno[@]+"${entorno[@]}"}" -v "$RAIZ_HOST:/src:ro" -v prisma-trivy-cache:/root/.cache/ \
      "$TRIVY_IMAGEN" fs "${trivy_comun[@]}" --skip-dirs node_modules,target,dist --skip-files pom.xml "/src/$1"
  fi
}

escanear_imagen() {
  local imagen="$1" archivo
  archivo="imagen__$(nombre_archivo "$imagen").json"
  echo "-- imagen $imagen"
  if trivy_imagen "$imagen" > "$SALIDA/$archivo" 2> "$SALIDA/$archivo.err"; then
    registrar imagen "$imagen" "$archivo" ok
  else
    echo "   no se pudo escanear (ver vuln-reports/$archivo.err)" >&2
    registrar imagen "$imagen" "$archivo" error
  fi
}

escanear_dependencias() {
  local carpeta="$1" archivo
  archivo="dependencias__$(nombre_archivo "$carpeta").json"
  echo "-- dependencias $carpeta"
  if trivy_carpeta "$carpeta" > "$SALIDA/$archivo" 2> "$SALIDA/$archivo.err"; then
    registrar dependencias "$carpeta" "$archivo" ok
  else
    echo "   no se pudo escanear (ver vuln-reports/$archivo.err)" >&2
    registrar dependencias "$carpeta" "$archivo" error
  fi
}

lista_de_imagenes() {
  if [ -n "${VULN_IMAGENES:-}" ]; then
    printf '%s\n' "${VULN_IMAGENES//,/ }" | tr ' ' '\n'
  elif [ "$NATIVO" = 1 ]; then
    docker ps --filter "label=com.docker.compose.project=${VULN_PROYECTO:-prisma}" --format '{{.Image}}' | sort -u
  else
    (cd "$RAIZ" && docker compose config --images 2>/dev/null | sort -u || true)
  fi
}

if [ "$SOLO" != dependencias ]; then
  hallada=0
  for imagen in $(lista_de_imagenes); do
    if docker image inspect "$imagen" >/dev/null 2>&1; then
      hallada=1
      escanear_imagen "$imagen"
    else
      echo "-- imagen $imagen: no está descargada ni construida, se omite"
    fi
  done
  [ "$hallada" = 1 ] || echo "AVISO: no hay ninguna imagen de la stack en este equipo (make build o make pull las crea)." >&2
fi

if [ "$SOLO" != imagenes ]; then
  for carpeta in apps/frontend apps/backend-ai; do
    [ -d "$RAIZ/$carpeta" ] && escanear_dependencias "$carpeta"
  done
fi

[ -s "$SALIDA/manifiesto.tsv" ] || morir "No había nada para escanear."

echo
if [ "$NATIVO" = 1 ]; then
  salida_py="$(python3 "$SCRIPTS/vuln_metrics.py" "$SALIDA")"
else
  salida_py="$(docker run --rm -v "$SALIDA_HOST:/datos" -v "$RAIZ_HOST/scripts/vuln_metrics.py:/vuln_metrics.py:ro" \
    "$PYTHON_IMAGEN" python3 /vuln_metrics.py /datos)"
fi
printf '%s\n' "$salida_py" | grep -v '^CRITICAS_CON_PARCHE='
criticas="$(printf '%s\n' "$salida_py" | sed -n 's/^CRITICAS_CON_PARCHE=//p')"

publicar() {
  if [ "$NATIVO" = 1 ]; then
    curl -sf --max-time 20 -X PUT --data-binary "@$SALIDA/metricas.prom" "$PUSHGATEWAY_URL/metrics/job/trivy"
  else
    docker run --rm --network host -v "$SALIDA_HOST/metricas.prom:/metricas.prom:ro" "$CURL_IMAGEN" \
      -sf --max-time 20 -X PUT --data-binary @/metricas.prom "$PUSHGATEWAY_URL/metrics/job/trivy" 2>/dev/null \
      || curl -sf --max-time 20 -X PUT --data-binary "@$SALIDA/metricas.prom" "$PUSHGATEWAY_URL/metrics/job/trivy" 2>/dev/null
  fi
}

if [ "${VULN_SIN_PUSH:-0}" = 1 ]; then
  echo "VULN_SIN_PUSH=1: no se publicó en el Pushgateway."
elif publicar; then
  echo "Resultados publicados en el Pushgateway ($PUSHGATEWAY_URL): tablero «PRISMA · Vulnerabilidades» de Grafana."
else
  echo "AVISO: no se pudo publicar en $PUSHGATEWAY_URL. ¿Está levantado el perfil observability (make up)? Los resultados quedaron en vuln-reports/." >&2
fi

echo "Informe local: vuln-reports/resumen.md (JSON de Trivy y metricas.prom en la misma carpeta)."
if [ "${VULN_FALLAR:-0}" = 1 ] && [ "${criticas:-0}" -gt 0 ]; then
  echo "RESULTADO: hay $criticas vulnerabilidad(es) crítica(s) con parche disponible." >&2
  exit 1
fi
