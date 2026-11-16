FROM aquasec/trivy:0.58.1 AS trivy

FROM alpine:3.20

SHELL ["/bin/ash", "-o", "pipefail", "-c"]

RUN apk add --no-cache bash curl python3 docker-cli tzdata

COPY --from=trivy /usr/local/bin/trivy /usr/local/bin/trivy
COPY scripts/vuln-scan.sh /opt/prisma/scripts/vuln-scan.sh
COPY scripts/vuln_metrics.py /opt/prisma/scripts/vuln_metrics.py
COPY infra/vuln-scanner/entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /opt/prisma/scripts/vuln-scan.sh /opt/prisma/scripts/vuln_metrics.py /entrypoint.sh \
 && chmod +x /opt/prisma/scripts/vuln-scan.sh /entrypoint.sh

ENV VULN_NATIVO=1 \
    VULN_RAIZ_CODIGO=/src \
    VULN_SALIDA=/data/vuln-reports

ENTRYPOINT ["/entrypoint.sh"]
