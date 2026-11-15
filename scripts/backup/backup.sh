#!/bin/bash
# RNF-13: respaldo diario de Postgres (dump completo) y del bucket de evidencias en RustFS
# (espejado completo, no incremental -- mismo criterio simple que el dump de Postgres: una
# fotografía completa por corrida, no una diferencia contra la anterior). Lo dispara el scheduler
# de Ofelia (ver docker-compose.yml, servicio backup-scheduler) corriendo este script dentro del
# contenedor backup-runner; "make backup" ejecuta la misma corrida a mano.
set -euo pipefail

OUT_DIR="/backups"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
STAMP=$(date +%Y%m%d_%H%M%S)

mkdir -p "$OUT_DIR"

# Si algo falla, además de que Ofelia/"make backup" vean el exit code != 0, avisamos por webhook
# (Slack/Discord/Teams-compatible) cuando BACKUP_ALERT_WEBHOOK_URL está configurado -- no hay
# ningún otro canal de alertas operativas en el proyecto todavía.
notify_failure() {
  local reason="$1"
  echo "[backup] FALLÓ: ${reason}" >&2
  if [ -n "${BACKUP_ALERT_WEBHOOK_URL:-}" ]; then
    curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
      -d "{\"text\":\"[PRISMA] Falló el respaldo diario (${STAMP}): ${reason}\"}" \
      "$BACKUP_ALERT_WEBHOOK_URL" >/dev/null 2>&1 \
      || echo "[backup] ADVERTENCIA: no se pudo notificar el fallo al webhook" >&2
  fi
}
trap 'notify_failure "comando fallido en la línea $LINENO"; exit 1' ERR

echo "[backup] Volcando Postgres (${POSTGRES_DB})..."
PG_DUMP_FILE="$OUT_DIR/postgres_${STAMP}.sql.gz"
pg_dump -h postgres -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "$PG_DUMP_FILE"

echo "[backup] Verificando integridad del dump..."
if ! gzip -t "$PG_DUMP_FILE"; then
  rm -f "$PG_DUMP_FILE"
  notify_failure "el dump de Postgres quedó corrupto (gzip -t falló)"
  exit 1
fi
if [ ! -s "$PG_DUMP_FILE" ]; then
  rm -f "$PG_DUMP_FILE"
  notify_failure "el dump de Postgres quedó vacío"
  exit 1
fi
echo "[backup] OK: $PG_DUMP_FILE (íntegro)"

echo "[backup] Espejando el bucket de evidencias (${S3_BUCKET}) desde RustFS..."
# RustFS expone un API S3-compatible (el mismo que usa backend-core via AWS SDK, ver
# ObjectStorageConfig) -- aws s3 sync con --endpoint-url habla contra él igual que contra S3 real.
if aws --endpoint-url "$S3_ENDPOINT" s3 sync --no-progress \
    "s3://${S3_BUCKET}" "$OUT_DIR/evidences_${STAMP}"; then
  echo "[backup] OK: $OUT_DIR/evidences_${STAMP}/"
else
  # No se aborta el backup de Postgres por esto -- el bucket puede no existir todavía (instalación
  # nueva sin evidencia cargada) o RustFS puede estar caído puntualmente. Sí lo reportamos.
  notify_failure "no se pudo espejar el bucket de evidencias (¿vacío o RustFS caído?)"
fi

echo "[backup] Aplicando retención de ${RETENTION_DAYS} días..."
find "$OUT_DIR" -maxdepth 1 -name "postgres_*.sql.gz" -mtime "+${RETENTION_DAYS}" -delete
find "$OUT_DIR" -maxdepth 1 -name "evidences_*" -type d -mtime "+${RETENTION_DAYS}" -exec rm -rf {} +

echo "[backup] Listo."
