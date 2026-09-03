# 🧪 Testing — PRISMA

Cuatro suites automatizadas, una por capa, más una metodología de QA manual/exploratoria contra el
stack real que en la práctica encontró más bugs de integración que las cuatro juntas. Este
documento cubre las cinco.

## Resumen

| Suite | Dónde | Herramienta | Qué cubre |
|---|---|---|---|
| Backend Core (unit) | `apps/backend-core/src/test` | JUnit 5 + Mockito | Cada `Service`, `Controller` (MockMvc standalone, sin Spring Security real), entidades, DTOs, config |
| Backend Core (integración) | `PrismaApplicationIT.java` | JUnit 5 + Testcontainers | Contexto Spring real + Postgres real (contenedor efímero) — migraciones Flyway aplicadas de cero, catálogo sembrado, ciclo de vida completo de una evaluación |
| Backend AI | `apps/backend-ai/tests` | pytest + pytest-asyncio | Endpoints FastAPI (`TestClient`), loaders de documentos, vector store, seguridad del header interno |
| Frontend (unit) | `apps/frontend/tests` | Vitest + Vue Test Utils | Lógica pura (`utils/validators.ts`, `utils/helpers.ts`) y montaje de `App.vue` — **cobertura real de componentes/vistas es baja hoy**, ver "Estado real de la cobertura" abajo |
| Frontend (e2e) | `apps/frontend/e2e` | Playwright + axe-core | Login real contra Keycloak, navegación por las vistas autenticadas, accesibilidad WCAG 2.1 AA |

## Correr todo

```bash
make test              # frontend + backend-core + backend-ai, en ese orden
make test-frontend      # sólo Vitest
make test-core          # sólo JUnit + Testcontainers -- necesita Docker corriendo
make test-ai            # sólo pytest
make test-e2e           # Playwright -- necesita Keycloak + backend-core arriba, ver más abajo
```

## Backend Core — JUnit + Mockito + Testcontainers

```bash
cd apps/backend-core
mvn clean verify
```

**Importante — `clean` no es opcional al cambiar de rama.** Sin `clean`, `target/classes` puede
quedar con migraciones Flyway de un checkout anterior, y Testcontainers levanta un Postgres nuevo
con ESAS migraciones viejas en vez de las del código actual — se manifiesta como columnas
inexistentes o conteos que no cierran, y hace perder tiempo pensando que es un bug real. Ver el
incidente completo en [`RUNBOOK.md`](RUNBOOK.md).

Este repo no trae `mvnw` versionado — si no tenés Maven en el PATH, hay que apuntar
`JAVA_HOME`/`PATH` a una instalación local (JDK 25 + Maven 3.9) antes de correr los comandos de
arriba.

**Convenciones:**
- Un test class por clase de producción (`OrganizationServiceTest` para `OrganizationService`).
- `@ExtendWith(MockitoExtension.class)` + `@MockitoSettings(strictness = Strictness.LENIENT)` — se
  usa `LENIENT` a propósito porque varios tests comparten un `@BeforeEach` con stubs por defecto
  (p.ej. `currentUser.isPrismaAdmin() → true`) que no todos los tests individuales necesitan.
- `ControllersMockMvcTest` usa `MockMvcBuilders.standaloneSetup(...)`, **no** levanta Spring
  Security — sirve para probar serialización/deserialización y wiring de los controllers, no para
  probar `@PreAuthorize`. Los tests de autorización real viven en `PrismaApplicationIT` o se
  prueban a mano contra el stack (ver más abajo).
- Gate de cobertura JaCoCo: 80% de líneas por paquete (`pom.xml`). Hay una segunda regla pensada
  para exigir 100% en el motor de cálculo de madurez, pero apunta a un paquete
  (`uy.edu.prisma.maturity.*`) que no existe — la lógica real vive en
  `EvaluationService.calculateMaturity` dentro de `application`, cubierta sólo por la regla del
  80%. Ver [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Backend AI — pytest

```bash
cd apps/backend-ai
python -m venv .venv && .venv/Scripts/activate   # o source .venv/bin/activate en Linux/Mac
pip install -r requirements.txt -r requirements-dev.txt
pytest -q
```

No necesita Docker ni Ollama corriendo — el vector store y el cliente de Ollama se mockean.
Config en `pytest.ini` (`asyncio_mode = auto`, cobertura con `--cov=app`).

## Frontend — Vitest

```bash
cd apps/frontend
npm run test:unit -- --coverage
```

### Estado real de la cobertura

`utils/validators.ts` y `utils/helpers.ts` están cerca del 100% (los 6 schemas de Zod y todo el
formateo/etiquetado de estados). El resto de `src/` — los 19 `views/`, los `stores/` de Pinia, los
componentes reutilizables (`BaseButton`, `DataTable`, etc.) — **no tiene tests unitarios propios
todavía**. No es una regresión reciente, es cobertura que nunca se escribió; escribirla en serio
(mocks de `keycloak-js`/Axios, montaje de componentes con Vue Test Utils) es un esfuerzo del
tamaño de un proyecto aparte, no algo para sumar de pasada arreglando otra cosa.

## Frontend — Playwright (E2E + accesibilidad)

Documentado en detalle en [`../apps/frontend/e2e/README.md`](../apps/frontend/e2e/README.md) —
resumen:

```bash
cd apps/frontend
npm run test:a11y     # sólo accesibilidad
npm run test:e2e      # todo, headless
npm run test:e2e:ui   # con la UI de Playwright, para depurar
```

Necesita Keycloak + backend-core corriendo y **el mismo ajuste de `KEYCLOAK_TRUSTED_ISSUERS`** que
cualquier acceso directo a Keycloak sin pasar por nginx (ver [`RUNBOOK.md`](RUNBOOK.md) y el propio
README de `e2e/`). Trae una lista de hallazgos de accesibilidad ya detectados y **no corregidos
todavía** (falta de `aria-label` en el botón de colapsar el sidebar, contraste insuficiente en
texto secundario, headings duplicados) — leerla antes de asumir que un fallo nuevo de axe es tuyo.

## QA manual contra el stack real

Los cuatro suites de arriba prueban unidades aisladas (con mocks) o, como mucho, un contexto
Spring contra un Postgres efímero. Ninguna levanta el stack completo con Keycloak, nginx, MinIO y
Ollama reales, con dos organizaciones distintas y un usuario de cada rol interactuando — y ahí es
donde en la práctica aparecieron los bugs más importantes de este proyecto, ninguno detectado por
un test unitario:

| Bug encontrado | Por qué el mock no lo veía |
|---|---|
| Login roto en `http://localhost:8080` (nginx reenviaba `X-Forwarded-Host` sin puerto) | Es un problema de configuración de nginx + Keycloak real — no hay nginx en ningún test |
| `KEYCLOAK_TRUSTED_ISSUERS` sin el origen correcto → 401 en loop | Necesita un JWT real emitido por un Keycloak real, con el `iss` que le corresponde a cómo se accedió |
| `GET /api/organizations`/`GET /api/users` sin aislamiento por tenant | Los tests unitarios de `OrganizationServiceTest`/`UserServiceTest` de ese momento nunca probaban "un segundo tenant no debería ver esto" porque nadie había escrito ese caso — hacía falta *dos organizaciones reales* para notarlo |
| URL de evidencia con el hostname interno de Docker | El mock de `MinioClient` no valida si la URL resultante es alcanzable desde fuera de la red de contenedores |
| El SDK de MinIO necesita conectividad real para firmar sin región fija | Sólo aparece contra un MinIO real, corriendo dentro de Docker |
| Filebeat 9.x dejó de indexar nada tras el upgrade de ELK 8→9 (input `container` removido, deprecado desde 8.x) | El contenedor arrancaba "sano" (healthcheck en verde) y sin errores visibles a simple vista — el log de error quedaba en un archivo dentro del propio contenedor, no en stdout; sólo se notó al confirmar `docs.count` en Elasticsearch |
| `X-Forwarded-For` spoofeable en la bitácora (nginx usaba `$proxy_add_x_forwarded_for`, que hereda el header del cliente en vez de fijarlo) | Sólo se nota probando con un cliente que manda su propio header — ningún test unitario ni de integración pasa por nginx |

**Procedimiento recomendado antes de un release, o después de tocar login/RBAC/multi-tenant/ciclo
de vida de evaluación:**

1. `make up-core` (app + IAM + storage — no hace falta el perfil `full` con monitoreo/SonarQube).
2. Crear 2 organizaciones y un usuario de cada rol (`PRISMA_ADMIN`, `ORG_RESPONSIBLE`,
   `INTERNAL_EVALUATOR`, `AUDITOR`, `VIEWER`) — al menos uno de los dos roles con tenant
   (`ORG_RESPONSIBLE`/`INTERNAL_EVALUATOR`/`VIEWER`) en cada organización, para poder probar
   aislamiento cruzado.
3. Recorrer el flujo completo con cada rol: crear evaluación, responder controles, subir
   evidencia, calcular madurez, enviar a auditoría, auditar, aprobar, generar reportes, plan de
   mejora.
4. Para cada endpoint mutante nuevo o tocado, probar explícitamente el caso negativo: ¿un usuario
   de OTRA organización puede verlo/tocarlo? ¿Un rol sin permiso recibe 403 real (no sólo lo
   esconde la UI)?
5. Ver `iss` del token real (jwt.io) si algo da 401 inesperado — ver [`RUNBOOK.md`](RUNBOOK.md).

### Obtener un token para probar la API a mano

El client `prisma-frontend` de Keycloak no tiene `directAccessGrantsEnabled` (usa PKCE, no ROPC) —
para pedir un token por `curl` hay que usar el client `prisma-backend` (confidencial, service
account, `directAccessGrantsEnabled: true`):

```bash
curl -s -X POST http://localhost:8180/auth/realms/prisma/protocol/openid-connect/token \
  -d "client_id=prisma-backend" -d "client_secret=$KEYCLOAK_ADMIN_CLIENT_SECRET" \
  -d "username=admin@prisma.local" -d "password=Admin1234!" -d "grant_type=password"
```

Si el usuario tiene la contraseña marcada como temporal (todo usuario recién creado, incluido el
semilla), esto falla con `invalid_grant: Account is not fully set up` — ver la solución en
[`RUNBOOK.md`](RUNBOOK.md).

## Referencias

- Setup del entorno: [`Dev-Config.md`](Dev-Config.md)
- Incidentes reales y sus causas raíz: [`RUNBOOK.md`](RUNBOOK.md)
- Referencia de endpoints (para saber qué probar): [`API.md`](API.md)
- Convenciones de código por tecnología: [`CONTRIBUTING.md`](CONTRIBUTING.md)
