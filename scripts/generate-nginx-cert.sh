#!/usr/bin/env bash
# =====================================================================
# generate-nginx-cert.sh — genera (o renueva) el certificado autofirmado
# del reverse-proxy nginx (infra/nginx/certs/prisma.crt + prisma.key).
# ---------------------------------------------------------------------
# Por qué un script y no `mkcert` (ver docs/Dev-Config.md): mkcert emite
# certificados confiables para el navegador (los firma con una CA local
# ya instalada en el sistema), pero limita la validez a ~825 días (regla
# del CA/Browser Forum que mkcert respeta aunque sea una CA local). Este
# script genera un certificado autofirmado "de verdad" (sin CA detrás),
# lo que permite elegir la validez libremente (5 años por defecto) a
# cambio de que el navegador muestre la advertencia de "no confiable" la
# primera vez (se acepta una sola vez por navegador/máquina).
#
# nginx (infra/nginx/conf.d/prisma.conf) SIEMPRE lee los mismos dos
# nombres de archivo fijos (prisma.crt/prisma.key) -- este script es la
# ÚNICA pieza que hay que tocar para renovar o cambiar de certificado;
# la configuración de nginx no necesita ningún cambio. Ver el
# procedimiento completo en docs/Certificados-TLS.md.
#
# Uso:
#   scripts/generate-nginx-cert.sh                     # defaults (5 años, prisma.local)
#   scripts/generate-nginx-cert.sh --days 825           # validez distinta
#   scripts/generate-nginx-cert.sh --cn otrohost.local --san otrohost.local,localhost,127.0.0.1
#   scripts/generate-nginx-cert.sh --out-dir /ruta/custom
#
# Variables de entorno equivalentes (para uso no interactivo / CI):
#   CERT_DAYS, CERT_CN, CERT_SAN, CERT_OUT_DIR
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DAYS="${CERT_DAYS:-1825}"                       # 5 años
CN="${CERT_CN:-prisma.local}"
SAN="${CERT_SAN:-prisma.local,localhost,127.0.0.1}"
OUT_DIR="${CERT_OUT_DIR:-$SCRIPT_DIR/../infra/nginx/certs}"
FORCE=0

usage() {
  sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --cn) CN="$2"; shift 2 ;;
    --san) SAN="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage ;;
    *) echo "Opción desconocida: $1" >&2; usage ;;
  esac
done

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl no está instalado o no está en el PATH." >&2
  exit 1
fi

CRT="$OUT_DIR/prisma.crt"
KEY="$OUT_DIR/prisma.key"

if [ -f "$CRT" ] && [ "$FORCE" -ne 1 ]; then
  echo "Ya existe un certificado en $CRT:"
  openssl x509 -in "$CRT" -noout -subject -dates
  echo
  echo "Volver a generarlo lo REEMPLAZA (hay que reiniciar el contenedor nginx después)."
  read -r -p "¿Continuar? [y/N] " confirm
  case "$confirm" in
    y|Y|yes|si|sí) ;;
    *) echo "Cancelado."; exit 0 ;;
  esac
fi

mkdir -p "$OUT_DIR"

# subjectAltName acepta DNS:/IP: por entrada -- se arma a partir de la lista separada por comas,
# detectando automáticamente si cada entrada es una IP o un nombre de host.
SAN_LIST=""
IFS=',' read -ra ENTRIES <<< "$SAN"
for entry in "${ENTRIES[@]}"; do
  entry="$(echo "$entry" | xargs)" # trim
  if [[ "$entry" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    SAN_LIST="${SAN_LIST:+$SAN_LIST,}IP:$entry"
  else
    SAN_LIST="${SAN_LIST:+$SAN_LIST,}DNS:$entry"
  fi
done

# MSYS2_ARG_CONV_EXCL="/C=": en Git Bash/MSYS2, un argumento que empieza con "/" se puede
# confundir con una ruta de archivo y reescribirse como "C:/UY/ST/..." -- esta variable excluye
# de esa conversión específicamente al valor de -subj (que arranca con "/C="), sin afectar la
# conversión normal (y necesaria) de -keyout/-out. No-op en Linux/Mac (variable no reconocida).
MSYS2_ARG_CONV_EXCL="/C=" openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" \
  -keyout "$KEY" -out "$CRT" \
  -subj "/C=UY/ST=Montevideo/L=Montevideo/O=PRISMA/OU=Dev/CN=$CN" \
  -addext "subjectAltName=$SAN_LIST"

chmod 600 "$KEY" 2>/dev/null || true

echo
echo "✅ Certificado generado en $OUT_DIR"
openssl x509 -in "$CRT" -noout -subject -dates -ext subjectAltName
echo
echo "Reiniciá el contenedor de nginx para que lo tome:"
echo "  docker compose restart nginx"
echo "  # o, con Make:  make restart-nginx"
