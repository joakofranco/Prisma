#!/usr/bin/env bash
# =====================================================================
# generate-internal-ca.sh — genera (o renueva) la CA interna de PRISMA y el
# certificado TLS de Keycloak (BUG-12 / PRI-369: cifrado en tránsito en los
# tramos internos nginx -> Keycloak y backend-core -> Keycloak).
# ---------------------------------------------------------------------
# Salida (por defecto en infra/keycloak/certs/, ignorada por git):
#   ca.crt / ca.key        CA interna (ca.crt es lo que montan nginx y backend-core
#                          para VERIFICAR a Keycloak; ca.key no sale de esta carpeta)
#   keycloak.crt / .key    certificado de servidor de Keycloak (firmado por la CA),
#                          SAN: keycloak, localhost, 127.0.0.1
#
# Distinto de scripts/generate-nginx-cert.sh: aquel genera el certificado PÚBLICO
# (navegador -> nginx :443); éste cubre el tramo INTERNO dentro de prisma-net.
#
# Uso:
#   scripts/generate-internal-ca.sh                 # defaults (5 años)
#   scripts/generate-internal-ca.sh --force         # regenera sin preguntar
#   scripts/generate-internal-ca.sh --san keycloak,localhost,127.0.0.1,kc.prisma.local
#   scripts/generate-internal-ca.sh --days 825 --out-dir /ruta/custom
#
# Variables equivalentes: CERT_DAYS, CERT_SAN, CERT_OUT_DIR
# Después: docker compose up -d --force-recreate keycloak nginx frontend backend-core
# =====================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DAYS="${CERT_DAYS:-1825}"
SAN="${CERT_SAN:-keycloak,localhost,127.0.0.1}"
OUT_DIR="${CERT_OUT_DIR:-$SCRIPT_DIR/../infra/keycloak/certs}"
FORCE=0

usage() {
  sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
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

CA_CRT="$OUT_DIR/ca.crt"
CA_KEY="$OUT_DIR/ca.key"
KC_CRT="$OUT_DIR/keycloak.crt"
KC_KEY="$OUT_DIR/keycloak.key"

if [ -f "$KC_CRT" ] && [ "$FORCE" -ne 1 ]; then
  echo "Ya existe un certificado en $KC_CRT:"
  openssl x509 -in "$KC_CRT" -noout -subject -dates
  echo
  echo "Volver a generarlo REEMPLAZA la CA y el certificado (hay que recrear keycloak, nginx, frontend y backend-core)."
  read -r -p "¿Continuar? [y/N] " confirm
  case "$confirm" in
    y|Y|yes|si|sí) ;;
    *) echo "Cancelado."; exit 0 ;;
  esac
fi

mkdir -p "$OUT_DIR"

# subjectAltName: DNS:/IP: por entrada, detectando IPs automáticamente.
SAN_LIST=""
IFS=',' read -ra ENTRIES <<< "$SAN"
for entry in "${ENTRIES[@]}"; do
  entry="$(echo "$entry" | xargs)"
  if [[ "$entry" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    SAN_LIST="${SAN_LIST:+$SAN_LIST,}IP:$entry"
  else
    SAN_LIST="${SAN_LIST:+$SAN_LIST,}DNS:$entry"
  fi
done

EXT_FILE="$(mktemp)"
CSR_FILE="$(mktemp)"
trap 'rm -f "$EXT_FILE" "$CSR_FILE"' EXIT

# MSYS2_ARG_CONV_EXCL="/C=": ver el comentario en generate-nginx-cert.sh (Git Bash reescribe "/C=...").
export MSYS2_ARG_CONV_EXCL="/C="

# 1) CA interna (autofirmada, sólo puede firmar certificados).
openssl req -x509 -newkey rsa:4096 -nodes -days "$DAYS" \
  -keyout "$CA_KEY" -out "$CA_CRT" \
  -subj "/C=UY/ST=Montevideo/L=Montevideo/O=PRISMA/OU=Dev/CN=PRISMA Internal CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"

# 2) Certificado de servidor de Keycloak, firmado por la CA.
openssl req -newkey rsa:2048 -nodes \
  -keyout "$KC_KEY" -out "$CSR_FILE" \
  -subj "/C=UY/ST=Montevideo/L=Montevideo/O=PRISMA/OU=Dev/CN=keycloak"

cat > "$EXT_FILE" <<EOF
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$SAN_LIST
EOF

openssl x509 -req -in "$CSR_FILE" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
  -out "$KC_CRT" -days "$DAYS" -extfile "$EXT_FILE"

# El contenedor de Keycloak corre con un uid distinto de root: la clave debe ser legible
# desde el bind mount (misma situación que en dev con infra/nginx/certs, que es :ro).
chmod 644 "$KC_KEY" "$CA_CRT" "$KC_CRT" 2>/dev/null || true
chmod 600 "$CA_KEY" 2>/dev/null || true
rm -f "$OUT_DIR/ca.srl"

echo
echo "✅ CA interna y certificado de Keycloak generados en $OUT_DIR"
openssl x509 -in "$KC_CRT" -noout -subject -issuer -dates -ext subjectAltName
openssl verify -CAfile "$CA_CRT" "$KC_CRT"
echo
echo "Recreá los servicios que lo usan:"
echo "  docker compose up -d --force-recreate keycloak nginx frontend backend-core"
