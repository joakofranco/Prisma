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
CYAN := \033[36m
GREEN := \033[32m
YELLOW := \033[33m
RESET := \033[0m

# Detección del comando docker compose
DC := docker compose

.DEFAULT_GOAL := help
.PHONY: help up down restart logs ps build rebuild pull clean nuke \
        test test-frontend test-core test-ai lint format \
        seed migrate backup restore health pull-llm shell-core shell-ai \
        sonar security-scan sync-knowledge-base \
        certs certs-force restart-nginx \
        mvp-up mvp-down mvp-logs mvp-ps mvp-seed \
        mcu50-build mcu50-validate bugasura-build

# Archivos de compose para el stack MVP (mínimo, sin LLM ni servicios extra)
MVP := -f docker-compose.yml -f docker-compose.mvp.yml

# ---------------------------------------------------------------------
help: ## Mostrar esta ayuda
	@echo ""
	@echo "$(CYAN)╔═══════════════════════════════════════════════════════════╗$(RESET)"
	@echo "$(CYAN)║           PRISMA — Comandos disponibles                    ║$(RESET)"
	@echo "$(CYAN)╚═══════════════════════════════════════════════════════════╝$(RESET)"
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  $(GREEN)%-20s$(RESET) %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo ""

# ---------- CICLO DE VIDA DE LA STACK ----------
up: ## Levantar toda la stack (perfil full)
	@echo "$(CYAN)🚀 Levantando toda la stack...$(RESET)"
	$(DC) --profile full up -d --build --remove-orphans

up-app: ## Levantar sólo la aplicación (rápido: front, back, DB, redis, ollama)
	$(DC) --profile app up -d --build

up-core: ## Sólo aplicación + IAM + almacenamiento
	$(DC) --profile app --profile security up -d --build

# ---------- STACK MÍNIMO (MVP) ----------
mvp-up: ## MVP: sólo front, back, keycloak, postgres (+redis, nginx) — SIN LLM ni extras
	@echo "$(CYAN)🚀 Levantando stack MVP (sin Ollama/LLM, sin observabilidad)...$(RESET)"
	$(DC) $(MVP) --profile mvp up -d --build --remove-orphans

mvp-down: ## MVP: detener el stack mínimo (preserva volúmenes)
	$(DC) $(MVP) --profile mvp down --remove-orphans

mvp-logs: ## MVP: seguir logs del stack mínimo
	$(DC) $(MVP) --profile mvp logs -f --tail=100

mvp-ps: ## MVP: listar contenedores del stack mínimo
	$(DC) $(MVP) --profile mvp ps

mvp-seed: ## MVP: precargar catálogo MCU 5.0 + datos demo (sin ingest de IA)
	@echo "$(CYAN)🌱 Cargando catálogo MCU 5.0 y datos demo...$(RESET)"
	$(DC) $(MVP) exec backend-core java -jar /app/app.jar --seed

# ---------- MCU 5.0 alineado a las planillas Agesic 2025 ----------
mcu50-build: ## Regenerar V19__mcu50_align_agesic_2025.sql + expected_basico.json desde docs/mcu-5.0/Planilla MCU 5.0 *.xlsx
	python scripts/mcu50/build.py
	cp scripts/mcu50/expected_basico.json apps/backend-core/src/test/resources/mcu50/expected_basico.json

mcu50-validate: ## Cotejar el cálculo de madurez de PRISMA contra la planilla oficial de Agesic (seed 5.0)
	cd apps/backend-core && mvn -q test -Dtest=MaturityValidationIT

bugasura-build: ## Regenerar docs/mcu-5.0/bugasura-import.csv (backlog completo) desde docs/HistoriasDeUsuario.md
	python scripts/bugasura/build_import.py

down: ## Detener toda la stack (preserva volúmenes)
	@echo "$(YELLOW)⏹  Deteniendo stack...$(RESET)"
	$(DC) --profile full down --remove-orphans

restart: ## Reiniciar toda la stack
	$(MAKE) down
	$(MAKE) up

logs: ## Seguir logs de todos los servicios
	$(DC) --profile full logs -f --tail=100

logs-core: ## Sólo logs del backend-core
	$(DC) logs -f backend-core

logs-ai: ## Sólo logs del backend-ai
	$(DC) logs -f backend-ai

logs-front: ## Sólo logs del frontend
	$(DC) logs -f frontend

logs-elastic: ## Sólo logs de Elasticsearch/Kibana/Filebeat
	$(DC) logs -f elasticsearch kibana filebeat

ps: ## Listar contenedores activos
	$(DC) --profile full ps

build: ## Reconstruir las imágenes locales
	$(DC) --profile full build --parallel

rebuild: ## Reconstruir SIN caché
	$(DC) --profile full build --no-cache --parallel

pull: ## Descargar imágenes upstream
	$(DC) --profile full pull

health: ## Chequear salud de los servicios
	@echo "$(CYAN)🩺 Health check...$(RESET)"
	@curl -sf http://localhost:8081/actuator/health && echo " backend-core OK" || echo " backend-core FAIL"
	@curl -sf http://localhost:8000/health         && echo " backend-ai OK"   || echo " backend-ai FAIL"
	@curl -sfI http://localhost:8080               > /dev/null && echo " frontend OK" || echo " frontend FAIL"

# ---------- LIMPIEZA ----------
clean: ## Detener y eliminar contenedores + redes (preserva volúmenes)
	$(DC) --profile full down --remove-orphans

nuke: ## ⚠️  ELIMINA volúmenes y datos persistentes (DESTRUCTIVO)
	@echo "$(YELLOW)⚠️  Esto destruirá TODOS los datos locales. Ctrl+C para cancelar.$(RESET)"
	@sleep 5
	$(DC) --profile full down -v --remove-orphans
	docker volume prune -f

# ---------- TESTS ----------
test: test-frontend test-core test-ai ## Ejecutar TODAS las suites de tests

test-frontend: ## Tests del frontend (Vitest)
	@echo "$(CYAN)🧪 Tests frontend...$(RESET)"
	cd apps/frontend && npm ci && npm run test:unit -- --coverage

test-core: ## Tests del backend-core (JUnit + Testcontainers)
	@echo "$(CYAN)🧪 Tests backend-core...$(RESET)"
	cd apps/backend-core && mvn -B verify

test-ai: ## Tests del backend-ai (pytest)
	@echo "$(CYAN)🧪 Tests backend-ai...$(RESET)"
	cd apps/backend-ai && pytest --cov=app --cov-report=term-missing

test-e2e: ## E2E con Playwright (necesita stack levantada)
	cd apps/frontend && npm run test:e2e

# ---------- LINT & FORMAT ----------
lint: ## Correr todos los linters
	@echo "$(CYAN)🔍 Linting...$(RESET)"
	cd apps/frontend     && npm run lint
	cd apps/backend-core && mvn -B spotless:check checkstyle:check
	cd apps/backend-ai   && ruff check app tests && black --check app tests

format: ## Auto-formatear todo el código
	@echo "$(CYAN)✨ Formateando...$(RESET)"
	cd apps/frontend     && npm run format
	cd apps/backend-core && mvn -B spotless:apply
	cd apps/backend-ai   && ruff check --fix app tests && black app tests

# ---------- BASE DE DATOS ----------
migrate: ## Ejecutar migraciones Flyway
	$(DC) exec backend-core mvn flyway:migrate

seed: ## Precargar catálogo MCU 5.0 y datos demo
	@echo "$(CYAN)🌱 Cargando catálogo MCU 5.0 y datos demo...$(RESET)"
	$(DC) exec backend-core java -jar /app/app.jar --seed
	$(DC) exec backend-ai python -m app.scripts.ingest_mcu_docs

backup: ## Backup de Postgres
	@mkdir -p backups
	@STAMP=$$(date +%Y%m%d_%H%M%S); \
	$(DC) exec -T postgres pg_dump -U $(POSTGRES_USER) $(POSTGRES_DB) | gzip > backups/postgres_$${STAMP}.sql.gz; \
	echo "✅ Backup en backups/postgres_$${STAMP}.sql.gz"

restore: ## Restaurar Postgres desde el backup más reciente
	@LATEST=$$(ls -t backups/postgres_*.sql.gz | head -1); \
	echo "♻️  Restaurando desde $$LATEST..."; \
	gunzip -c $$LATEST | $(DC) exec -T postgres psql -U $(POSTGRES_USER) $(POSTGRES_DB)

# ---------- IA / OLLAMA ----------
pull-llm: ## Descargar Llama 3 en Ollama (~4.7 GB, primera vez)
	@echo "$(CYAN)📥 Descargando modelo Llama 3...$(RESET)"
	$(DC) exec ollama ollama pull llama3:8b
	$(DC) exec ollama ollama pull nomic-embed-text
	@echo "$(GREEN)✅ Modelos descargados$(RESET)"

# ---------- CALIDAD & SEGURIDAD ----------
sonar: ## Análisis SonarQube local
	@echo "$(CYAN)📊 Ejecutando SonarQube Scanner...$(RESET)"
	docker run --rm --network prisma-net \
	  -e SONAR_HOST_URL=http://prisma-sonarqube:9000 \
	  -e SONAR_TOKEN=$${SONAR_TOKEN} \
	  -v $(PWD):/usr/src \
	  sonarsource/sonar-scanner-cli

security-scan: ## Trivy scan local
	@echo "$(CYAN)🛡  Ejecutando Trivy...$(RESET)"
	docker run --rm -v $(PWD):/src aquasec/trivy:latest fs /src --severity CRITICAL,HIGH

# ---------- RAG: BASE DE CONOCIMIENTO ----------
sync-knowledge-base: ## Copia docs/mcu-5.0 al build context de backend-ai (la empaqueta la imagen)
	@echo "$(CYAN)🔄 Sincronizando la knowledge base del RAG para la imagen de backend-ai...$(RESET)"
	rm -rf apps/backend-ai/knowledge-base;
	mkdir -p apps/backend-ai/knowledge-base;
	cp -r docs/mcu-5.0/. apps/backend-ai/knowledge-base/;

# ---------- KEYCLOAK: MFA (TOTP) POR ROL ----------
mfa-enable: ## Activar MFA obligatoria en Keycloak para PRISMA_ADMIN y ORG_RESPONSIBLE (idempotente)
	./scripts/keycloak/enable-mfa.sh

mfa-status: ## Ver el estado de la MFA y qué usuarios con rol obligado ya registraron OTP
	./scripts/keycloak/enable-mfa.sh --status

mfa-disable: ## Volver al flujo de login original de Keycloak (MFA solo voluntaria)
	./scripts/keycloak/enable-mfa.sh --disable

# ---------- TLS / CERTIFICADOS ----------
certs: ## Generar el certificado autofirmado de nginx (pide confirmación si ya existe uno)
	./scripts/generate-nginx-cert.sh

certs-force: ## Regenerar el certificado de nginx sin preguntar (sobreescribe el actual)
	./scripts/generate-nginx-cert.sh --force

restart-nginx: ## Reiniciar solo nginx (para que tome un certificado nuevo)
	$(DC) restart nginx

# ---------- ACCESO A CONTENEDORES ----------
shell-core: ## Shell dentro del backend-core
	$(DC) exec backend-core sh

shell-ai: ## Shell dentro del backend-ai
	$(DC) exec backend-ai bash

shell-db: ## Consola psql
	$(DC) exec postgres psql -U $(POSTGRES_USER) $(POSTGRES_DB)

shell-front: ## Shell dentro del frontend
	$(DC) exec frontend sh
