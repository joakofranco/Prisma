#!/usr/bin/env bash
# =====================================================================
# generate-internal-ca.sh — genera (o renueva) la CA interna de PRISMA y un
# certificado TLS por servicio para el cifrado en tránsito entre contenedores
# (PRI-369 / BUG-12).
# ---------------------------------------------------------------------
# Salida (por defecto en infra/tls/, ignorada por git):
#   ca.crt / ca.key      CA interna (ca.crt es lo que montan los clientes para VERIFICAR
#                        a cada servicio; ca.key no sale de esta carpeta)
#   <servicio>.crt/.key  certificado de servidor firmado por la CA, SAN: <servicio>,
#                        localhost, 127.0.0.1
#
# Servicios (SERVICES): keycloak keycloak-db postgres backend-core backend-ai frontend
#                       rustfs elasticsearch
#
# Distinto de scripts/generate-nginx-cert.sh: aquel genera el certificado PÚBLICO
# (navegador -> nginx :443); éste cubre los tramos INTERNOS dentro de prisma-net.
#
# Uso:
#   scripts/generate-internal-ca.sh                 # CA + certificados (5 años)
#   scripts/generate-internal-ca.sh --force         # regenera todo sin preguntar
#   scripts/generate-internal-ca.sh --only postgres # reemite solo un certificado (reusa la CA)
#   scripts/generate-internal-ca.sh --days 825 --out-dir /ruta/custom
#
# Variables equivalentes: CERT_DAYS, CERT_OUT_DIR, CERT_SERVICES
# Después: docker compose up -d --force-recreate <servicios que lo usan>
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DAYS="${CERT_DAYS:-1825}"
OUT_DIR="${CERT_OUT_DIR:-$SCRIPT_DIR/../infra/tls}"
SERVICES="${CERT_SERVICES:-keycloak keycloak-db postgres backend-core backend-ai frontend rustfs elasticsearch}"
FORCE=0
ONLY=""

usage() {
  sed -n '2,27p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage ;;
    *) echo "Opción desconocida: $1" >&2; usage ;;
  esac
done

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl no está instalado o no está en el PATH." >&2
  exit 1
fi

CA_CRT="$OUT_DIR/ca.crt"
CA_KEY="$OUT_DIR/ca.key"

mkdir -p "$OUT_DIR"

# MSYS2_ARG_CONV_EXCL="/C=": ver el comentario en generate-nginx-cert.sh (Git Bash reescribe "/C=...").
export MSYS2_ARG_CONV_EXCL="/C="

# --only reusa la CA existente; sin --only se (re)genera todo.
if [ -n "$ONLY" ]; then
  if [ ! -f "$CA_CRT" ] || [ ! -f "$CA_KEY" ]; then
    echo "ERROR: no existe la CA en $OUT_DIR; corré el script sin --only primero." >&2
    exit 1
  fi
  SERVICES="$ONLY"
else
  if [ -f "$CA_CRT" ] && [ "$FORCE" -ne 1 ]; then
    echo "Ya existe una CA en $CA_CRT:"
    openssl x509 -in "$CA_CRT" -noout -subject -dates
    echo
    echo "Volver a generarla REEMPLAZA la CA y TODOS los certificados (hay que recrear los servicios)."
    read -r -p "¿Continuar? [y/N] " confirm
    case "$confirm" in
      y|Y|yes|si|sí) ;;
      *) echo "Cancelado."; exit 0 ;;
    esac
  fi
  openssl req -x509 -newkey rsa:4096 -nodes -days "$DAYS" \
    -keyout "$CA_KEY" -out "$CA_CRT" \
    -subj "/C=UY/ST=Montevideo/L=Montevideo/O=PRISMA/OU=Dev/CN=PRISMA Internal CA" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"
fi

EXT_FILE="$(mktemp)"
CSR_FILE="$(mktemp)"
trap 'rm -f "$EXT_FILE" "$CSR_FILE"' EXIT

# issue <servicio>: certificado de servidor firmado por la CA, SAN = servicio, localhost, 127.0.0.1.
issue() {
  local svc="$1" crt="$OUT_DIR/$1.crt" key="$OUT_DIR/$1.key"
  openssl req -newkey rsa:2048 -nodes -keyout "$key" -out "$CSR_FILE" \
    -subj "/C=UY/ST=Montevideo/L=Montevideo/O=PRISMA/OU=Dev/CN=$svc"
  cat > "$EXT_FILE" <<EOF
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:$svc,DNS:localhost,IP:127.0.0.1
EOF
  openssl x509 -req -in "$CSR_FILE" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
    -out "$crt" -days "$DAYS" -extfile "$EXT_FILE" 2>/dev/null
  # Los contenedores corren con uids distintos de root: el bind mount debe ser legible. Los servicios
  # que exigen permisos estrictos sobre la clave (Postgres) la copian a un lugar propio al arrancar.
  chmod 644 "$key" "$crt" 2>/dev/null || true
  openssl verify -CAfile "$CA_CRT" "$crt" >/dev/null
  echo "  ✔ $svc"
}

echo "Emitiendo certificados:"
for svc in $SERVICES; do
  issue "$svc"
done

chmod 644 "$CA_CRT" 2>/dev/null || true
chmod 600 "$CA_KEY" 2>/dev/null || true
rm -f "$OUT_DIR/ca.srl"

echo
echo "✅ CA interna y certificados generados en $OUT_DIR"
openssl x509 -in "$CA_CRT" -noout -subject -dates
echo
echo "Recreá los servicios que los usan, por ejemplo:"
echo "  docker compose up -d --force-recreate keycloak nginx frontend backend-core"
