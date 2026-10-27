#!/bin/bash
# RNF-13: restaura Postgres y el bucket de evidencias de RustFS desde el backup MÁS RECIENTE en
# /backups (ver backup.sh). "make restore" ejecuta esta misma corrida a mano.
set -euo pipefail

OUT_DIR="/backups"

LATEST_PG=$(ls -t "$OUT_DIR"/postgres_*.sql.gz 2>/dev/null | head -1 || true)
if [ -z "${LATEST_PG:-}" ]; then
  echo "[restore] No hay ningún backup de Postgres en $OUT_DIR" >&2
  exit 1
fi
echo "[restore] Restaurando Postgres desde $LATEST_PG..."
gunzip -c "$LATEST_PG" | psql -h postgres -U "$POSTGRES_USER" "$POSTGRES_DB"
echo "[restore] Postgres restaurado."

LATEST_EV=$(ls -dt "$OUT_DIR"/evidences_*/ 2>/dev/null | head -1 || true)
if [ -z "${LATEST_EV:-}" ]; then
  echo "[restore] No hay ningún backup de evidencias en $OUT_DIR -- se omite esa parte."
else
  echo "[restore] Restaurando el bucket de evidencias desde $LATEST_EV..."
  aws --endpoint-url "$S3_ENDPOINT" s3 sync --no-progress "$LATEST_EV" "s3://${S3_BUCKET}"
  echo "[restore] Evidencias restauradas."
fi

echo "[restore] Listo."
