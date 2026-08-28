# =====================================================================
# backend-ai.Dockerfile — FastAPI + LangChain + Ollama + ChromaDB
# Multi-stage: builder → dev → runtime
# =====================================================================

# ---------- Stage 1: BUILDER ----------
FROM python:3.13-slim AS builder
WORKDIR /build

# Instalar dependencias del sistema mínimas
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
 && rm -rf /var/lib/apt/lists/*

# Copiar sólo requirements primero para cache
COPY requirements.txt requirements-dev.txt ./
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir --prefix=/install -r requirements.txt

# ---------- Stage 2: DEV ----------
FROM python:3.13-slim AS dev
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=builder /install /usr/local
COPY requirements-dev.txt ./
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY app ./app
COPY tests ./tests
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]

# ---------- Stage 3: RUNTIME ----------
FROM python:3.13-slim AS runtime

LABEL org.opencontainers.image.title="prisma-backend-ai"
LABEL org.opencontainers.image.description="Servicio de IA/RAG de PRISMA (FastAPI + Ollama)"
LABEL org.opencontainers.image.source="https://github.com/luisaraujo-utec/proyecto_prisma"

# Usuario NO-root
RUN groupadd -r prisma && useradd -r -g prisma -d /app prisma

# apt-get upgrade (no solo install): parchea los paquetes ya presentes en la imagen base
# python:3.13-slim (openssl, etc.) con CVEs conocidos, no solo los que instalamos nosotros.
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends \
    tini \
    curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /install /usr/local

# La imagen base python:3.13-slim trae su propio pip con un msgpack vendorizado desactualizado
# (pip/_vendor/msgpack) que Trivy marca vulnerable (GHSA-6v7p-g79w-8964) aunque no sea un paquete
# nuestro; pip no se usa en runtime (uvicorn corre directo), asi que se lo saca del todo en vez de
# dejarlo desactualizado sin motivo.
RUN python -m pip uninstall -y pip setuptools wheel

COPY app ./app

# Base de conocimiento del RAG (docs/mcu-5.0 del repo). En local, docker-compose.yml la
# bind-mountea en vivo sobre este mismo path (así se edita sin rebuild); esta copia es la que
# usan las imágenes publicadas a GHCR (K8s, docker-compose.prod.yml) donde no hay bind mount.
# El directorio "knowledge-base" lo puebla `make sync-k8s-assets` antes del build (ver Makefile);
# si nunca corrió, queda vacío (placeholder .gitkeep versionado) y el build no falla, sólo el
# RAG no tiene contexto -- ver infra/kubernetes/README.md.
COPY knowledge-base ./knowledge-base
RUN mkdir -p /data/chroma /data/knowledge-base \
 && cp -r ./knowledge-base/. /data/knowledge-base/ \
 && rm -rf ./knowledge-base \
 && chown -R prisma:prisma /app /data

USER prisma
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=5 \
  CMD curl -f http://localhost:8000/health || exit 1

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
