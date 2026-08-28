# =====================================================================
# backend-core.Dockerfile — Spring Boot 3 (Java 25)
# Multi-stage: builder → dev → runtime
# =====================================================================

# ---------- Stage 1: BUILDER ----------
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace

# Copiar sólo pom.xml primero para aprovechar cache de dependencias
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Ahora copiar el código fuente y compilar
COPY src ./src
RUN mvn -B clean package -DskipTests -T 1C
RUN mkdir -p target/dependency && \
    (cd target/dependency && jar -xf ../*.jar)

# ---------- Stage 2: DEV (hot-reload con Spring DevTools) ----------
FROM maven:3.9-eclipse-temurin-25 AS dev
WORKDIR /workspace
COPY --from=builder /root/.m2 /root/.m2
COPY pom.xml .
COPY src ./src
EXPOSE 8080 5005
CMD ["mvn", "spring-boot:run"]

# ---------- Stage 3: RUNTIME (imagen mínima y hardeneada) ----------
FROM eclipse-temurin:25-jre-alpine AS runtime

# Metadatos OCI
LABEL org.opencontainers.image.title="prisma-backend-core"
LABEL org.opencontainers.image.description="API principal de PRISMA (Spring Boot 3)"
LABEL org.opencontainers.image.source="https://github.com/luisaraujo-utec/proyecto_prisma"
LABEL org.opencontainers.image.licenses="Academic"

# Usuario NO-root por seguridad
RUN addgroup -S prisma && adduser -S prisma -G prisma -h /app

# Instalar tini y curl (para healthcheck); apk upgrade parchea el resto de paquetes de la
# imagen base (openssl, etc.) con CVEs conocidos, no solo los que instalamos nosotros.
RUN apk update && apk upgrade --no-cache && apk add --no-cache tini curl

WORKDIR /app
ARG DEPENDENCY=/workspace/target/dependency
COPY --from=builder ${DEPENDENCY}/BOOT-INF/lib     /app/lib
COPY --from=builder ${DEPENDENCY}/META-INF         /app/META-INF
COPY --from=builder ${DEPENDENCY}/BOOT-INF/classes /app

# Directorio para evidencias
RUN mkdir -p /var/prisma/evidences && chown -R prisma:prisma /var/prisma /app

USER prisma
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx1024m -XX:+UseZGC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["java", "-cp", "/app:/app/lib/*", "uy.edu.prisma.PrismaApplication"]
