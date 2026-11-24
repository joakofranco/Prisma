# =====================================================================
# PRISMA — Makefile
# ---------------------------------------------------------------------
# Atajos comunes para desarrollo. Funciona en Linux/Mac y en Windows
# (usando WSL, Git Bash o make instalado).
# =====================================================================

# Windows + `make` nativo (ej. GnuWin32, no MSYS2/WSL) corriendo desde PowerShell/cmd: por
# defecto ese `make` NO usa ningún shell para una línea sin metacaracteres (sin &&, |, ; ni
# $()) -- llama directo a CreateProcess() con el PATH heredado del proceso, y esa PowerShell NO
# tiene el `usr/bin` de Git (cp/rm/mkdir/openssl) en el PATH -- de ahí el error "El sistema no
# puede encontrar el archivo especificado" en targets como sync-knowledge-base. Fix: forzar el
# shell de las recetas a bash.exe de Git Bash y, como bash -c (no login) NO sourcea /etc/profile
# (que es justamente lo que arma el PATH con /usr/bin), BASH_ENV hace que lo sourcee igual (ver
# la doc de bash: "when invoked non-interactively", lee BASH_ENV). $(wildcard ...) en esta
# versión vieja de GNU Make no soporta espacios en la ruta -- de ahí el nombre corto 8.3
# (PROGRA~1) sólo para la comprobación de existencia; SHELL sí acepta la ruta con espacios.
# Sin efecto en Linux/Mac/WSL ($(OS) no es Windows_NT ahí) ni si Git no está en la ruta default.
ifeq ($(OS),Windows_NT)
ifneq ($(wildcard C:/PROGRA~1/Git/usr/bin/bash.exe),)
SHELL := C:/Program Files/Git/usr/bin/bash.exe
export BASH_ENV := /etc/profile
endif
endif

# Colores
ESC := $(shell printf '\033')
CYAN := $(ESC)[36m
GREEN := $(ESC)[32m
YELLOW := $(ESC)[33m
RESET := $(ESC)[0m

# Detección del comando docker compose
DC := docker compose

.DEFAULT_GOAL := help
.PHONY: help up up-app up-core down restart logs logs-core logs-ai logs-front logs-elastic \
        ps build rebuild pull health clean nuke \
        mvp-up mvp-down mvp-logs mvp-ps \
        test test-frontend test-core test-ai test-e2e lint format \
        migrate backup restore pull-llm sonar security-scan sync-knowledge-base \
        mfa-enable mfa-status mfa-disable mfa-reset-requires-otp auth-policy auth-policy-status \
        certs certs-force internal-ca restart-nginx \
        shell-core shell-ai shell-db shell-front \
        prod-secrets prod-up deploy-cloudflare swarm-deploy swarm-ps swarm-down \
        perf-build perf-setup perf-umbrales perf perf-all perf-clean \
        vuln-scan \
        mcu50-build mcu50-validate bugasura-build

# Archivos de compose para el stack MVP (mínimo, sin LLM ni servicios extra)
MVP := -f docker-compose.yml -f docker-compose.mvp.yml

# ---------------------------------------------------------------------
#! Los puertos, usuarios y contraseñas de los servicios se toman de .env (ver .env.example).
help: ## Mostrar esta ayuda: cada comando con lo que hace y, debajo, sus parámetros opcionales
	@echo ""
	@echo "$(CYAN)PRISMA - Comandos disponibles$(RESET)"
	@echo "Uso: make <comando> [PARAMETRO=valor ...]"
	@awk 'BEGIN {FS = ":.*## "} /^# ---------- .* ----------$$/ {t = $$0; gsub(/^# -+ | -+$$/, "", t); printf "\n$(CYAN)%s$(RESET)\n", t; next} /^[a-zA-Z0-9_-]+:.*## / {printf "  $(GREEN)%-24s$(RESET) %s\n", $$1, $$2; next} /^#> / {sub(/^#> /, ""); printf "  %-24s   $(YELLOW)%s$(RESET)\n", "", $$0; next} /^#! / {pie = substr($$0, 4)} END {printf "\n%s\n\n", pie}' $(MAKEFILE_LIST)

# ---------- CICLO DE VIDA DE LA STACK ----------
up: ## Levantar toda la stack (perfil full): aplicación, Keycloak, almacenamiento, monitoreo y alertas, Elasticsearch, SonarQube y gestión. Construye las imágenes locales
	@echo "$(CYAN)Levantando toda la stack...$(RESET)"
	@mkdir -p .secrets && { [ -s .secrets/portainer_admin_password ] || printf '%s' 'Admin1234!!!' > .secrets/portainer_admin_password; }
	$(DC) --profile full up -d --build --remove-orphans

up-app: ## Levantar solo la aplicación (perfil app): frontend, backend-core, backend-ai, PostgreSQL, Redis, Ollama y ClamAV
	$(DC) --profile app up -d --build

up-core: ## Levantar la aplicación con Keycloak, RustFS, nginx y fail2ban (perfiles app y security), sin monitoreo ni SonarQube
	$(DC) --profile app --profile security up -d --build

down: ## Detener y quitar los contenedores de toda la stack. Conserva los volúmenes (datos)
	@echo "$(YELLOW)Deteniendo la stack...$(RESET)"
	$(DC) --profile full down --remove-orphans

restart: ## Reiniciar toda la stack: equivale a make down y luego make up
	$(MAKE) down
	$(MAKE) up

logs: ## Seguir en vivo los logs de todos los servicios (últimas 100 líneas; Ctrl+C para salir)
	$(DC) --profile full logs -f --tail=100

logs-core: ## Seguir en vivo los logs de backend-core
	$(DC) logs -f backend-core

logs-ai: ## Seguir en vivo los logs de backend-ai
	$(DC) logs -f backend-ai

logs-front: ## Seguir en vivo los logs del frontend
	$(DC) logs -f frontend

logs-elastic: ## Seguir en vivo los logs de Elasticsearch, Kibana y Filebeat (perfil elastic)
	$(DC) logs -f elasticsearch kibana filebeat

ps: ## Listar los contenedores de la stack con su estado y salud
	$(DC) --profile full ps

build: ## Construir en paralelo las imágenes locales (frontend, backend-core, backend-ai y auxiliares), usando la caché
	$(DC) --profile full build --parallel

rebuild: ## Construir las imágenes locales desde cero, sin caché (más lento; usar si la caché quedó inconsistente)
	$(DC) --profile full build --no-cache --parallel

pull: ## Descargar las imágenes de terceros que usa la stack (PostgreSQL, Keycloak, Grafana, etc.)
	$(DC) --profile full pull

health: ## Comprobar que backend-core, backend-ai, frontend, Keycloak y RustFS responden (prueba HTTP y, si falla, HTTPS)
	@echo "$(CYAN)Comprobando la salud de los servicios...$(RESET)"
	@bash scripts/health-check.sh

# ---------- STACK MÍNIMO (MVP) ----------
mvp-up: ## MVP: levantar el stack mínimo (frontend, backend-core, backend-ai ocioso, Keycloak, PostgreSQL, Redis y nginx), sin LLM, RustFS ni monitoreo
	@echo "$(CYAN)Levantando el stack MVP (sin Ollama/LLM, sin observabilidad)...$(RESET)"
	$(DC) $(MVP) --profile mvp up -d --build --remove-orphans

mvp-down: ## MVP: detener el stack mínimo. Conserva los volúmenes
	$(DC) $(MVP) --profile mvp down --remove-orphans

mvp-logs: ## MVP: seguir en vivo los logs del stack mínimo
	$(DC) $(MVP) --profile mvp logs -f --tail=100

mvp-ps: ## MVP: listar los contenedores del stack mínimo
	$(DC) $(MVP) --profile mvp ps

# ---------- LIMPIEZA ----------
clean: ## Igual que make down: detiene y quita contenedores y redes. Conserva los volúmenes
	$(DC) --profile full down --remove-orphans

nuke: ## DESTRUCTIVO: borra los contenedores y TODOS los volúmenes de PRISMA (bases, evidencias, Keycloak) y además cualquier volumen de Docker sin uso en la máquina. Espera 5 s para poder cancelar con Ctrl+C
	@echo "$(YELLOW)CUIDADO: se borran TODOS los datos locales. Ctrl+C para cancelar.$(RESET)"
	@sleep 5
	$(DC) --profile full down -v --remove-orphans
	docker volume prune -f

# ---------- TESTS ----------
test: test-frontend test-core test-ai ## Ejecutar las tres suites: frontend, backend-core y backend-ai (no incluye E2E)

test-frontend: ## Tests unitarios del frontend (npm ci + Vitest con cobertura). Necesita Node.js 24
	@echo "$(CYAN)Tests del frontend...$(RESET)"
	cd apps/frontend && npm ci && npm run test:unit -- --coverage

test-core: ## Tests unitarios y de integración de backend-core (mvn verify, con el gate de cobertura). Necesita JDK 25, Maven y Docker corriendo para Testcontainers
	@echo "$(CYAN)Tests de backend-core...$(RESET)"
	cd apps/backend-core && mvn -B verify

test-ai: ## Tests de backend-ai (pytest con cobertura). Necesita el entorno Python con requirements-dev.txt instalado
	@echo "$(CYAN)Tests de backend-ai...$(RESET)"
	cd apps/backend-ai && pytest --cov=app --cov-report=term-missing

test-e2e: ## Pruebas E2E y de accesibilidad con Playwright. Necesita Keycloak y backend-core levantados
#> E2E_BASE_URL=https://localhost  probar contra un frontend ya levantado (por defecto Playwright arma uno en el puerto 5173)
#> E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=...  usuario administrador a usar (por defecto, el usuario inicial)
	cd apps/frontend && npm run test:e2e

# ---------- PERFORMANCE (JMeter) ----------
perf-build: ## Construir la imagen Docker con JMeter que usan las pruebas de performance (se hace sola la primera vez)
	@bash perf/scripts/perf.sh build

perf-setup: ## Crear los datos de prueba PERF- (organización, usuarios y evaluaciones) y pedir el p95 aceptable de cada caso. Idempotente; necesita perf/data/admin.env
#> PERF_USUARIOS=20  cantidad de usuarios de prueba a crear
#> BASE_URL=... PERF_PERMITIR_REMOTO=1  crear los datos en otro entorno en lugar del stack local
#> PERF_SIN_PREGUNTAS=1  no pedir los p95: conserva los guardados o los de cada caso
#> PERF_EVAL_APROBADAS=5  evaluaciones aprobadas que se crean para los reportes PDF (una por usuario virtual)
	@bash perf/scripts/perf.sh setup

perf-umbrales: ## Cambiar el p95 aceptable (ms) de cada caso CP sin recrear los datos. Se guarda en perf/data/umbrales.properties
#> PERF_SIN_PREGUNTAS=1  solo mostrar los valores vigentes
	@bash perf/scripts/perf.sh umbrales

perf: ## Correr un caso de performance y generar su informe (PDF, HTML y resumen) en perf/results. Termina con error si el caso no cumple sus umbrales
#> CASO=CP-RND-02  caso a correr (obligatorio): uno de los archivos de perf/casos
#> USERS=50  DURATION=600  RAMP=60  ITERACIONES=10  PAUSA_MS=500  cambian la carga definida en el caso
#> ENDPOINTS=dashboard,evaluaciones  peticiones a medir    MODO=app|borde  directo a backend-core o por nginx
#> ESCALONES=10,20,40  usuarios de cada escalón en la prueba de estrés
#> BASE_URL=https://host PERF_PERMITIR_REMOTO=1  correr contra otro entorno (por defecto, el stack local)
#> PERF_PERMITIR_DISRUPTIVO=1  necesario para CP-RND-06 (estrés) y CP-OBS-08 (inundación)
#> UMBRAL_P95_MS=2000  UMBRAL_P99_MS=4000  UMBRAL_MEDIA_MS=800  tiempos máximos aceptados (RNF); reemplazan los del caso, también los de cada petición
#> UMBRAL_ERROR_PCT=1  porcentaje máximo de errores    UMBRAL_THROUGHPUT_MIN=20  peticiones por segundo mínimas
#> UMBRAL_MIN_429=100  mínimo de respuestas 429 esperadas (solo CP-OBS-08)
#> PROMETHEUS_URL=http://10.8.0.1:9090  ALERTMANAGER_URL=http://10.8.0.1:9093  MAILPIT_URL=http://10.8.0.1:8025  servicios de observabilidad a consultar (por defecto, los nombres de la red Docker); se comprueban antes de empezar
#> CP-RND-03, 04 y 05 corren primero con 1 usuario y después con la carga completa (dos informes); USERS=n corre una sola fase
#> CP-RND-06 termina con una corrida de recuperación de 5 minutos con 1 usuario, comparada con el último CP-RND-01 (RECUPERACION_S=0 la omite)
	@bash perf/scripts/perf.sh run $(CASO)

perf-all: ## Correr todos los casos de medición y generar el informe consolidado en PDF. Termina con error si alguno no cumple
#> INCLUIR_DISRUPTIVOS=1 PERF_PERMITIR_DISRUPTIVO=1  sumar el estrés y la inundación (CP-OBS-08 corre al final)
#> EXCLUIR=CP-RND-04,CP-TRV-07  casos que no se corren
#> Acepta además los parámetros de carga, de destino y de umbrales (UMBRAL_*) de make perf; los umbrales se aplican a todos los casos
	@bash perf/scripts/perf.sh all

perf-clean: ## Limpiar los datos PERF-: borra lo que se puede y deshabilita los usuarios con historial. Conserva los resultados
#> BORRAR_RESULTADOS=1  borrar también todo perf/results
	@bash perf/scripts/perf.sh clean

# ---------- LINT & FORMAT ----------
lint: ## Correr los linters: ESLint en el frontend (con --fix: corrige lo que puede), Spotless y Checkstyle en backend-core, Ruff y Black en backend-ai
	@echo "$(CYAN)Corriendo los linters...$(RESET)"
	cd apps/frontend     && npm run lint
	cd apps/backend-core && mvn -B spotless:check checkstyle:check
	cd apps/backend-ai   && ruff check app tests && black --check app tests

format: ## Formatear todo el código: Prettier (frontend), Spotless (backend-core), Ruff y Black (backend-ai). Modifica archivos
	@echo "$(CYAN)Formateando...$(RESET)"
	cd apps/frontend     && npm run format
	cd apps/backend-core && mvn -B spotless:apply
	cd apps/backend-ai   && ruff check --fix app tests && black app tests

# ---------- BASE DE DATOS ----------
migrate: ## Aplicar las migraciones Flyway pendientes reiniciando backend-core (Flyway las ejecuta al arrancar; no hace falta en el uso normal)
	$(DC) restart backend-core

# RNF-13: respaldo diario (Postgres + bucket de evidencias de RustFS) y restauración. "make
# backup"/"make restore" corren el MISMO script (scripts/backup/backup.sh y restore.sh, dentro del
# contenedor backup-runner) que Ofelia dispara automáticamente una vez al día -- ver el servicio
# backup-runner/backup-scheduler en docker-compose.yml (perfil management o full). Antes esto solo
# respaldaba Postgres, a mano, sin ningún mecanismo diario ni cobertura del bucket de evidencias.
backup: ## Respaldo manual inmediato de PostgreSQL y de las evidencias en ./backups (el mismo que corre solo una vez al día). Necesita el perfil management o full
#> En .env: BACKUP_RETENTION_DAYS=30 (días que se conservan) y BACKUP_ALERT_WEBHOOK_URL=... (aviso si el respaldo falla)
	@mkdir -p backups
	$(DC) exec -T backup-runner /usr/local/bin/backup.sh

restore: ## Restaurar PostgreSQL y las evidencias desde el respaldo MÁS RECIENTE de ./backups. Sobrescribe los datos actuales
	$(DC) exec -T backup-runner /usr/local/bin/restore.sh

# ---------- IA / OLLAMA ----------
pull-llm: ## Descargar en Ollama los modelos nemotron-3-nano:30b (LLM, ~24 GB) y bge-m3 (embedding, ~1,2 GB). Solo la primera vez; necesita el contenedor ollama levantado
	@echo "$(CYAN)Descargando los modelos de Ollama...$(RESET)"
	$(DC) exec ollama ollama pull nemotron-3-nano:30b
	$(DC) exec ollama ollama pull bge-m3
	@echo "$(GREEN)Modelos descargados$(RESET)"

# ---------- CALIDAD & SEGURIDAD ----------
sonar: ## Analizar el código con SonarQube contra el contenedor local (perfil quality o full)
#> SONAR_TOKEN=<token>  token generado en SonarQube (My Account > Security). Obligatorio
	@echo "$(CYAN)Ejecutando SonarQube Scanner...$(RESET)"
	docker run --rm --network prisma-net \
	  -e SONAR_HOST_URL=http://prisma-sonarqube:9000 \
	  -e SONAR_TOKEN=$${SONAR_TOKEN} \
	  -v $(PWD):/usr/src \
	  sonarsource/sonar-scanner-cli

vuln-scan: ## Escanear ahora con Trivy las imágenes de la stack y las dependencias del código y publicar el resultado en el tablero «PRISMA · Vulnerabilidades» de Grafana (con make up, el servicio vuln-scanner también lo hace solo todos los días)
#> VULN_SOLO=imagenes|dependencias  escanear solo uno de los dos (por defecto, ambos)
#> VULN_IMAGENES=redis:7-alpine,nginx:1.30-alpine  escanear solo esas imágenes (por defecto, las de docker compose que haya en el equipo)
#> VULN_SOLO_CON_PARCHE=1  contar solo las vulnerabilidades que ya tienen versión corregida
#> VULN_FALLAR=1  terminar con error si hay críticas con parche (para usarlo en una automatización)
#> VULN_SIN_PUSH=1  no publicar en Grafana: deja solo el informe local en vuln-reports/
#> PUSHGATEWAY_URL=http://localhost:9091  Pushgateway al que publicar (perfil observability)
#> En .env: VULN_HORA=03:00  hora del escaneo diario automático    VULN_ESCANEAR_AL_INICIAR=true  escanear también al levantar el servicio
	@bash scripts/vuln-scan.sh

security-scan: ## Escanear el repositorio con Trivy (vulnerabilidades CRITICAL y HIGH en dependencias y archivos)
	@echo "$(CYAN)Ejecutando Trivy...$(RESET)"
	docker run --rm -v $(PWD):/src aquasec/trivy:latest fs /src --severity CRITICAL,HIGH

# ---------- RAG: BASE DE CONOCIMIENTO ----------
sync-knowledge-base: ## Copiar docs/mcu-5.0 a apps/backend-ai/knowledge-base para empaquetarla en la imagen de backend-ai (base de conocimiento del RAG)
	@echo "$(CYAN)Sincronizando la base de conocimiento del RAG para la imagen de backend-ai...$(RESET)"
	rm -rf apps/backend-ai/knowledge-base;
	mkdir -p apps/backend-ai/knowledge-base;
	cp -r docs/mcu-5.0/. apps/backend-ai/knowledge-base/;

# ---------- KEYCLOAK: MFA (TOTP) POR ROL ----------
mfa-enable: ## Activar en Keycloak la MFA (TOTP) obligatoria por rol. Idempotente; hay que correrlo en cada ambiente
#> MFA_ROLES="PRISMA_ADMIN AUDITOR"  roles obligados (por defecto PRISMA_ADMIN y ORG_RESPONSIBLE)
#> REALM=prisma  KEYCLOAK_CONTAINER=prisma-keycloak  realm y contenedor de Keycloak, si no son los habituales
	./scripts/keycloak/enable-mfa.sh

mfa-status: ## Mostrar el flujo de login activo en Keycloak y qué usuarios con rol obligado ya registraron su OTP
#> Acepta los mismos parámetros que mfa-enable
	./scripts/keycloak/enable-mfa.sh --status

mfa-disable: ## Volver al flujo de login original de Keycloak: la MFA pasa a ser voluntaria. Los OTP ya registrados se conservan
	./scripts/keycloak/enable-mfa.sh --disable

mfa-reset-requires-otp: ## Hacer que «¿Olvidaste tu contraseña?» pida el OTP actual y no lo regenere. Idempotente
	./scripts/keycloak/enable-mfa.sh --require-otp-on-reset

# ---------- KEYCLOAK: POLÍTICA DE AUTENTICACIÓN (recuperación y bloqueo) ----------
auth-policy: ## Aplicar en Keycloak la política de autenticación: enlace de recuperación de 30 min, bloqueo de 15 min tras 10 intentos fallidos, tema de login y SMTP si está en .env. Idempotente
#> RESET_LINK_LIFESPAN_SECONDS=1800  vigencia del enlace de recuperación
#> LOCKOUT_FAILURES=10  LOCKOUT_WINDOW_SECONDS=900  LOCKOUT_DURATION_SECONDS=900  intentos, ventana y duración del bloqueo
#> En .env: SMTP_HOST, SMTP_PORT, SMTP_FROM, SMTP_USER, SMTP_PASSWORD, SMTP_STARTTLS, SMTP_SSL (sin SMTP_HOST no toca el correo)
	./scripts/keycloak/apply-auth-policy.sh

auth-policy-status: ## Mostrar la vigencia del enlace de recuperación y los parámetros de bloqueo vigentes en Keycloak, comparados con los esperados
	./scripts/keycloak/apply-auth-policy.sh --status

# ---------- TLS / CERTIFICADOS ----------
certs: ## Generar el certificado autofirmado de nginx (pide confirmación si ya existe) y, si falta, la CA interna con los certificados de cada servicio
#> CERT_DAYS=1825  validez en días    CERT_CN=prisma.local  nombre principal
#> CERT_SAN=prisma.local,localhost,127.0.0.1  nombres e IP que cubre el certificado de nginx
	./scripts/generate-nginx-cert.sh
	@[ -f infra/tls/ca.crt ] || ./scripts/generate-internal-ca.sh --force

certs-force: ## Regenerar el certificado de nginx y la CA interna sin preguntar. Sobrescribe los existentes; después hay que recrear los contenedores
#> Acepta los mismos parámetros que certs e internal-ca
	./scripts/generate-nginx-cert.sh --force
	./scripts/generate-internal-ca.sh --force

internal-ca: ## Generar la CA interna y los certificados TLS de cada servicio (pide confirmación si ya existen)
#> CERT_EXTRA_SAN=DNS:otro.host,IP:10.0.0.5  nombres o IP adicionales en los certificados
#> CERT_DAYS=...  validez en días    CERT_SERVICES="keycloak postgres ..."  servicios para los que se emite
	./scripts/generate-internal-ca.sh

report-signing-cert: ## Emitir el certificado de sellado de PDFs (RF-REP-06) con la CA interna y guardarlo en infra/tls/signing/report-signing.p12
#> PDF_SIGNING_KEYSTORE_PASSWORD=...  contraseña del keystore (si falta se genera y se imprime)    CERT_DAYS=...  validez en días
	./scripts/generate-report-signing-cert.sh

restart-nginx: ## Reiniciar solo nginx, para que tome un certificado o una configuración nuevos
	$(DC) restart nginx

# ---------- ACCESO A CONTENEDORES ----------
shell-core: ## Abrir una terminal (sh) dentro del contenedor de backend-core
	$(DC) exec backend-core sh

shell-ai: ## Abrir una terminal (bash) dentro del contenedor de backend-ai
	$(DC) exec backend-ai bash

shell-db: ## Abrir la consola psql de PostgreSQL con el usuario y la base de la aplicación
	$(DC) exec postgres sh -c 'psql -U "$$POSTGRES_USER" "$$POSTGRES_DB"'

shell-front: ## Abrir una terminal (sh) dentro del contenedor del frontend
	$(DC) exec frontend sh

# ---------- PRODUCCIÓN ----------
prod-secrets: ## Producción: generar al azar las contraseñas que sigan en su valor inicial, escribirlas en .env y mostrarlas una sola vez. No toca las ya cambiadas
#> PROD_SECRETS_FORCE=1  generar también las que quedan fijadas en los datos, aunque ya existan volúmenes (solo en una instalación nueva)
#> ENV_FILE=/ruta/.env  archivo de variables a usar    PROD_SECRETS_QUIET=1  no mostrar los valores en pantalla
	@./scripts/generate-prod-secrets.sh

prod-up: ## Producción: generar las contraseñas que falten (como prod-secrets), mostrarlas y levantar el stack con docker-compose.prod.yml
#> Acepta los mismos parámetros que prod-secrets
	@./scripts/generate-prod-secrets.sh
	$(DC) -f docker-compose.yml -f docker-compose.prod.yml --profile full up -d --remove-orphans
	@echo "Stack de produccion levantado. Si se generaron claves, se mostraron arriba y quedaron en .env"

deploy-cloudflare: ## Publicar con Cloudflare Tunnel: deja el .env coherente con el dominio (URL pública, CORS e issuers de confianza), levanta el stack con docker-compose.cloudflare.yml y comprueba que la API aceptará los tokens del dominio. Idempotente
#> DOMINIO=prisma.tudominio.com  dominio público (obligatorio)    PROD=1  sumar docker-compose.prod.yml
#> BUILD=1  construir las imágenes locales    PERFIL=full  perfil de compose    ENV_FILE=.env  archivo de variables
#> En .env: INTERNAL_BIND_IP (obligatoria, IP de la VPN) y TUNNEL_ORIGIN_PORT (8443). Antes del cambio guarda .env.bak-<fecha>
	@./scripts/deploy-cloudflare.sh


# ---------- DOCKER SWARM (un solo servidor, 2 réplicas) ----------
swarm-deploy: ## Desplegar o actualizar el stack en Docker Swarm (docker-stack.yml) con las imágenes ya publicadas en GHCR. Inicializa Swarm si hace falta; no construye imágenes. Con ENV=production genera antes las contraseñas que falten
#> TAG=v1.2.0  etiqueta de las imágenes (por defecto, el commit actual)
#> STACK=prisma  nombre del stack    ENV_FILE=.env  archivo de variables    RESOLVE_IMAGE=never  usar imágenes locales
#> En .env: GHCR_ORG (obligatorio), BACKEND_CORE_REPLICAS, FRONTEND_REPLICAS, KEYCLOAK_REPLICAS (2 por defecto)
	@./scripts/generate-prod-secrets.sh --if-production
	./scripts/swarm/deploy.sh

swarm-ps: ## Mostrar los servicios del stack prisma en Swarm y sus tareas en ejecución
	docker stack services prisma
	docker stack ps prisma --no-trunc --filter desired-state=running

swarm-down: ## Quitar el stack prisma de Swarm. Conserva los volúmenes
	docker stack rm prisma

# ---------- CATÁLOGO MCU 5.0 Y BACKLOG ----------
mcu50-build: ## Regenerar la migración V19 del catálogo MCU 5.0 y expected_basico.json a partir de las planillas de docs/mcu-5.0. Necesita Python
	python scripts/mcu50/build.py
	cp scripts/mcu50/expected_basico.json apps/backend-core/src/test/resources/mcu50/expected_basico.json

mcu50-validate: ## Cotejar el cálculo de madurez de PRISMA contra la planilla oficial de Agesic (test MaturityValidationIT). Necesita Maven y Docker
	cd apps/backend-core && mvn -q test -Dtest=MaturityValidationIT

bugasura-build: ## Regenerar docs/mcu-5.0/bugasura-import.csv (backlog para importar en Bugasura) desde docs/proyecto/HistoriasDeUsuario.md. Necesita Python
	python scripts/bugasura/build_import.py
