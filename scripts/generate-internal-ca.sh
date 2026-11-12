#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DAYS="${CERT_DAYS:-1825}"
OUT_DIR="${CERT_OUT_DIR:-$SCRIPT_DIR/../infra/tls}"
SERVICES="${CERT_SERVICES:-keycloak keycloak-db postgres backend-core backend-ai frontend rustfs elasticsearch}"
FORCE=0
ONLY=""
EXTRA_SAN="${CERT_EXTRA_SAN:-}"

usage() {
  cat <<'USAGE'
Uso: scripts/generate-internal-ca.sh [opciones]
  (sin opciones)     genera la CA y un certificado por servicio en infra/tls
  --force            regenera todo sin preguntar
  --only <servicio>  reemite solo ese certificado reusando la CA
  --days <n>         validez en dias (por defecto 1825)
  --out-dir <ruta>   carpeta de salida
Variables: CERT_DAYS, CERT_OUT_DIR, CERT_SERVICES, CERT_EXTRA_SAN (ej. DNS:otro.host,IP:10.0.0.5)
Cada certificado incluye como SAN: <servicio>, localhost, prisma.local y 127.0.0.1
USAGE
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

export MSYS2_ARG_CONV_EXCL="/C="

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

issue() {
  local svc="$1" crt="$OUT_DIR/$1.crt" key="$OUT_DIR/$1.key"
  openssl req -newkey rsa:2048 -nodes -keyout "$key" -out "$CSR_FILE" \
    -subj "/C=UY/ST=Montevideo/L=Montevideo/O=PRISMA/OU=Dev/CN=$svc"
  cat > "$EXT_FILE" <<EOF
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:$svc,DNS:localhost,DNS:prisma.local,IP:127.0.0.1${EXTRA_SAN:+,$EXTRA_SAN}
EOF
  openssl x509 -req -in "$CSR_FILE" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
    -out "$crt" -days "$DAYS" -extfile "$EXT_FILE" 2>/dev/null
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
