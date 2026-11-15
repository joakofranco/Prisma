# RNF-13: imagen mínima para el respaldo/restauración diarios -- cliente de Postgres (pg_dump,
# psql, ya vienen en la imagen base postgres:17-alpine, la misma versión que corre el servicio
# "postgres") + AWS CLI (habla el mismo protocolo S3 que expone RustFS) para espejar el bucket de
# evidencias. Ver backup.sh / restore.sh, y el servicio backup-runner en docker-compose.yml.
#
# No se usa la imagen oficial "amazon/aws-cli" (más simple) ni "minio/mc" (cliente nativo de
# MinIO/RustFS) porque ninguna de las dos se puede copiar/combinar con esta base limpiamente:
# amazon/aws-cli trae un Python compilado para glibc (Amazon Linux), incompatible con el musl de
# Alpine; minio/mc pasó a requerir login en Docker Hub y Quay para pullearse. Instalar awscli vía
# pip en un venv evita depender de ninguna de las dos.
FROM postgres:17-alpine

RUN apk add --no-cache python3 py3-pip curl \
    && python3 -m venv /opt/awscli-venv \
    && /opt/awscli-venv/bin/pip install --no-cache-dir awscli \
    && apk del py3-pip

ENV PATH="/opt/awscli-venv/bin:${PATH}"

COPY backup.sh restore.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore.sh

# Se queda vivo sin ejecutar el entrypoint de Postgres (no es ese el rol de este contenedor):
# Ofelia ejecuta backup.sh/restore.sh ADENTRO de este contenedor ya corriendo (docker exec), vía
# "ofelia.job-exec.*" en las labels del servicio backup-runner.
ENTRYPOINT ["sleep", "infinity"]
