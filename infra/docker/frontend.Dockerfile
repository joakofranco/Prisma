# =====================================================================
# frontend.Dockerfile — Vue 3 + Vite servido por Nginx
# Multi-stage: builder → dev → runtime
# =====================================================================

# ---------- Stage 1: BUILDER ----------
FROM node:24-alpine AS builder
WORKDIR /app

# Cache de dependencias
COPY package.json package-lock.json* pnpm-lock.yaml* yarn.lock* ./
RUN if [ -f pnpm-lock.yaml ]; then \
      corepack enable && pnpm install --frozen-lockfile; \
    elif [ -f yarn.lock ]; then \
      yarn install --frozen-lockfile; \
    else \
      npm ci; \
    fi

# Copiar todo el código y compilar
COPY . .

# Argumentos de build para inyectar en el bundle
ARG VITE_API_BASE_URL
ARG VITE_AI_API_BASE_URL
ARG VITE_KEYCLOAK_URL
ARG VITE_KEYCLOAK_REALM
ARG VITE_KEYCLOAK_CLIENT_ID

ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_AI_API_BASE_URL=${VITE_AI_API_BASE_URL}
ENV VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL}
ENV VITE_KEYCLOAK_REALM=${VITE_KEYCLOAK_REALM}
ENV VITE_KEYCLOAK_CLIENT_ID=${VITE_KEYCLOAK_CLIENT_ID}

RUN npm run build

# ---------- Stage 2: DEV (Vite HMR) ----------
FROM node:24-alpine AS dev
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]

# ---------- Stage 3: RUNTIME (Nginx sirviendo assets estáticos) ----------
FROM nginx:1.30-alpine AS runtime

LABEL org.opencontainers.image.title="prisma-frontend"
LABEL org.opencontainers.image.description="Interfaz web de PRISMA (Vue 3)"

# La imagen base nginx:1.30-alpine trae openssl (libcrypto3/libssl3) con CVEs conocidos ya
# parcheados en Alpine 3.24 (ver Trivy); actualizamos los paquetes del sistema al building en vez
# de esperar a que la imagen base suba de tag.
RUN apk update && apk upgrade --no-cache

# Copiar configuración custom de nginx
COPY --from=builder /app/nginx.conf /etc/nginx/conf.d/default.conf

# Copiar el build compilado
COPY --from=builder /app/dist /usr/share/nginx/html

# Usuario no-root
RUN chown -R nginx:nginx /usr/share/nginx/html && \
    chown -R nginx:nginx /var/cache/nginx && \
    chown -R nginx:nginx /var/log/nginx && \
    chown -R nginx:nginx /etc/nginx/conf.d && \
    touch /var/run/nginx.pid && \
    chown -R nginx:nginx /var/run/nginx.pid

USER nginx
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget --spider -q http://localhost:80 || exit 1

CMD ["nginx", "-g", "daemon off;"]
