#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DAYS="${CERT_DAYS:-1825}"
OUT_DIR="${CERT_OUT_DIR:-$SCRIPT_DIR/../infra/tls}"
ALIAS="${PDF_SIGNING_KEY_ALIAS:-prisma-report-seal}"
PASSWORD="${PDF_SIGNING_KEYSTORE_PASSWORD:-}"
FORCE=0

usage() {
  cat <<'USAGE'
Uso: scripts/generate-report-signing-cert.sh [opciones]
  --force            reemplaza el keystore existente sin preguntar
  --days <n>         validez en dias (por defecto 1825)
  --out-dir <ruta>   carpeta de la CA y del keystore (por defecto infra/tls)
Variables: CERT_DAYS, CERT_OUT_DIR, PDF_SIGNING_KEY_ALIAS, PDF_SIGNING_KEYSTORE_PASSWORD
Emite el certificado intermedio «PRISMA - Sellado de reportes» con la CA interna y lo guarda en
signing/report-signing.p12 junto con su cadena. Si no se da contraseña, genera una y la imprime.
USAGE
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --days) DAYS="$2"; shift 2 ;;
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
SIGN_DIR="$OUT_DIR/signing"
P12="$SIGN_DIR/report-signing.p12"

if [ ! -f "$CA_CRT" ] || [ ! -f "$CA_KEY" ]; then
  echo "ERROR: no existe la CA interna en $OUT_DIR; corré make certs primero." >&2
  exit 1
fi

mkdir -p "$SIGN_DIR"

if [ -f "$P12" ] && [ "$FORCE" -ne 1 ]; then
  read -r -p "Ya existe $P12. ¿Reemplazarlo? Los PDF firmados antes dejarán de verificarse. [y/N] " confirm
  case "$confirm" in
    y|Y|yes|si|sí) ;;
    *) echo "Cancelado."; exit 0 ;;
  esac
fi

GENERATED=0
if [ -z "$PASSWORD" ]; then
  PASSWORD="$(openssl rand -hex 16)"
  GENERATED=1
fi

export MSYS2_ARG_CONV_EXCL="/C="

KEY_FILE="$(mktemp)"
CSR_FILE="$(mktemp)"
EXT_FILE="$(mktemp)"
CRT_FILE="$(mktemp)"
trap 'rm -f "$KEY_FILE" "$CSR_FILE" "$EXT_FILE" "$CRT_FILE"' EXIT

openssl req -newkey rsa:3072 -nodes -keyout "$KEY_FILE" -out "$CSR_FILE" \
  -subj "/C=UY/ST=Montevideo/L=Montevideo/O=PRISMA/OU=Sellado PRISMA/CN=PRISMA - Sellado de reportes"
cat > "$EXT_FILE" <<'EXT'
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,digitalSignature,nonRepudiation,keyCertSign
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid
EXT
openssl x509 -req -in "$CSR_FILE" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
  -out "$CRT_FILE" -days "$DAYS" -extfile "$EXT_FILE" 2>/dev/null
openssl verify -CAfile "$CA_CRT" "$CRT_FILE" >/dev/null

openssl pkcs12 -export -inkey "$KEY_FILE" -in "$CRT_FILE" -certfile "$CA_CRT" \
  -name "$ALIAS" -out "$P12" -passout "pass:$PASSWORD"
chmod 644 "$P12" 2>/dev/null || true
rm -f "$OUT_DIR/ca.srl"

echo "✅ Keystore de sellado generado en $P12"
openssl x509 -in "$CRT_FILE" -noout -subject -dates
if [ "$GENERATED" -eq 1 ]; then
  echo
  echo "Contraseña generada (guardala en el .env, no se vuelve a mostrar):"
  echo "  PDF_SIGNING_KEYSTORE_PASSWORD=$PASSWORD"
fi
echo
echo "Habilitá el sellado en el .env con PDF_SIGNING_ENABLED=true y recreá backend-core:"
echo "  docker compose up -d --force-recreate backend-core"
