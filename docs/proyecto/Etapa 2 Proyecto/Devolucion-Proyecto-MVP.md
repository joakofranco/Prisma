# Anexo — Respuesta a la devolución de Etapa 1 (Área Proyecto)

**Equipo ITDice — PRISMA**
Federico De Armas, Fernando Araujo, Joaquín Franco

---

## 1. Introducción

### 1.1 Problema detectado en la devolución

La devolución del área de Proyecto identificó dos observaciones concretas sobre la Etapa 1:

1. **Definición de MVP inconsistente entre secciones.** En algunas partes del documento (por ejemplo, en los Objetivos Específicos y en la descripción narrativa del Alcance) se mencionaban **evidencias** y **planes de mejora** como si formaran parte del Producto Mínimo Viable, mientras que en otras secciones (la tabla de Tecnología y la organización del Backlog por incrementos) esas mismas funcionalidades aparecían recién en los **Incrementos 2 y 3**. Esa falta de alineación se trasladaba también al Plan de Pruebas, que agrupaba "evidencias, planes de mejora y reportes" junto con "gestión de usuarios, cuestionarios y cálculo de madurez" bajo el mismo rótulo de "funcionalidades del MVP" sin distinguir qué corresponde a cada etapa.
2. **El documento debía resumir el anteproyecto, no reproducirlo.** Se pidió incluir únicamente los elementos fundamentales que permitan introducir la nueva etapa de forma incremental, sin adjuntar la totalidad del contenido anterior.

Este anexo resuelve el punto 1 fijando **una única definición de MVP**, consistente en todo el cuerpo del documento (Alcance, Tabla de Tecnología, Backlog y Plan de Pruebas), y sirve como referencia única a la que deben remitirse el resto de las secciones en lugar de redefinir el MVP cada vez.

### 1.2 Criterio usado para fijar el límite del MVP

El corte entre "MVP" y "resto del alcance" no es arbitrario: coincide exactamente con lo que el equipo ya construyó y puede levantarse con un único comando reproducible (`docker compose -f docker-compose.yml -f docker-compose.mvp.yml --profile mvp up -d`, alias `make mvp-up`). Ese perfil de despliegue incluye **sólo** `postgres`, `redis`, `keycloak` (+ `keycloak-db`), `backend-core`, `backend-ai` y `nginx` — deliberadamente **sin MinIO** (por lo que la carga de evidencias no es funcional) y **sin Ollama/Chroma** (por lo que no hay asistencia de IA). Usar el propio artefacto de despliegue como fuente de verdad, en lugar de una lista redactada a mano, es lo que evita que el documento y el sistema real diverjan.

---

## 2. Definición única de MVP (Incremento 1)

> **Toda referencia a "MVP" o "Producto Mínimo Viable" en este documento remite exclusivamente a lo definido en esta sección.** Ninguna otra sección debe listar funcionalidades adicionales bajo ese nombre.

### 2.1 Incluido en el MVP

| Módulo | Detalle |
|---|---|
| Gestión de organizaciones, usuarios y roles | Alta/baja/edición de organizaciones y usuarios; roles diferenciados (`PRISMA_ADMIN`, `ORG_RESPONSIBLE`, `INTERNAL_EVALUATOR`, `AUDITOR`, `VIEWER`); aislamiento de datos entre organizaciones (multi-tenancy) — ver el anexo de Ciberseguridad para el mecanismo. |
| Catálogo MCU 5.0 | Estructuración jerárquica (funciones → categorías → subcategorías → requisitos → controles) cargada desde el catálogo oficial de AGESIC, con versionado (`catalog_versions`) anclado a cada evaluación. |
| Evaluación guiada | Generación del cuestionario a partir del catálogo vigente, registro de respuestas (cumple/no cumple + observación) y control del ciclo de vida de la evaluación (borrador → en curso → lista para auditoría). |
| Cálculo de madurez | Cálculo automático del nivel de madurez global y por función/categoría/subcategoría, con identificación de brecha respecto al nivel objetivo. |
| Resultados básicos | Visualización de los resultados de la evaluación (nivel alcanzado, brechas) sin tableros gráficos avanzados. |
| Autenticación | Login vía Keycloak (OIDC/OAuth2), control de acceso por rol en cada endpoint. |

### 2.2 Explícitamente fuera del MVP (Incrementos posteriores)

| Funcionalidad | Incremento | Motivo de exclusión del MVP |
|---|---|---|
| Carga y gestión de evidencias | 2 | Depende de MinIO, que el perfil `mvp` no despliega; la funcionalidad de negocio (registrar evidencia) no es indispensable para validar el flujo central de evaluación → cálculo → resultado. |
| Auditoría (revisión por auditor, observaciones) | 2 | Depende del ciclo de vida completo de la evaluación con estados `IN_AUDIT`/`APPROVED`/`RETURNED`, que se valida junto con evidencias. |
| Trazabilidad / bitácora consultable desde la UI | 2 | La bitácora técnica (`audit.audit_logs`) ya se escribe desde el MVP por diseño (ver anexo de Ciberseguridad), pero su **consulta desde la aplicación** ("Actividad del Sistema") es una funcionalidad de negocio de Incremento 2. |
| Planes de mejora | 3 | Se generan a partir de las brechas detectadas por auditoría; depende de que el Incremento 2 esté cerrado. |
| Reportes ejecutivos y técnicos, tableros gráficos | 3 | Son una capa de presentación sobre datos que ya existen desde el MVP; no bloquean la validación del núcleo funcional. |
| MFA obligatorio, importación de nuevas versiones del catálogo, análisis asistido por IA | 4 | Funcionalidades avanzadas de seguridad/negocio que no condicionan la aceptación del MVP. |

### 2.3 Consecuencia para el Plan de Pruebas

La cobertura de pruebas del MVP se acota a los módulos de la sección 2.1: autenticación y control de acceso, gestión y separación de organizaciones, carga y respuesta de cuestionarios, y cálculo de niveles de madurez y brechas. La cobertura de evidencias, planes de mejora, auditoría y reportes se evalúa como parte de la aceptación de los Incrementos 2 y 3 respectivamente, no del MVP — evitando que el mismo módulo aparezca simultáneamente como criterio de aceptación de dos etapas distintas.

---

## 3. Tabla de Tecnología (versión consistente con el MVP)

| Capa | Tecnología | Uso en el MVP | Evolución (fuera del MVP) |
| ----- | ----- | ----- | ----- |
| Frontend | Vue 3 + Vite + Tailwind + Pinia + Axios | Formularios de evaluación, navegación, estado de sesión, resultados básicos. | Gráficos y tableros de resultados (Incremento 3). |
| Backend | Java 21 + Spring Boot 3.5 | API REST, RBAC, cálculo de madurez, persistencia. | Sin cambios estructurales previstos; separación de servicios sólo si se justifica por carga real. |
| Datos | PostgreSQL 17 (con JSONB para la bitácora técnica) | Fuente transaccional del MVP completo (organizaciones, catálogo, evaluaciones, resultados). | Réplica/optimización según mediciones reales de uso (ver anexo de Base de Datos). |
| Identidad | Keycloak (OIDC/OAuth2 + PKCE) | Autenticación, roles, JWT, SSO — **ya no sujeto a PoC, es la solución definitiva** (ver anexo de Ciberseguridad). | MFA obligatorio para roles privilegiados (Incremento 4). |
| Despliegue | Docker + Docker Compose (perfil `mvp`), Git, CI/CD básico | Entorno reproducible con un único comando. | Kubernetes/Terraform evaluados y documentados, pero fuera del alcance del MVP (ver `Kubernetes-Analisis-Viabilidad.md`). |
| Evidencias | MinIO (S3-compatible) + metadatos en PostgreSQL | **No incluido** — diseño de metadatos ya definido en el modelo de datos, sin funcionalidad activa. | Carga, descarga y borrado de evidencia (Incremento 2). |
| IA | FastAPI + LangChain + Ollama + ChromaDB | **No incluido.** | RAG local con fuentes citadas y revisión humana (Incremento 4). |
| QA/Operación | JUnit 5, Spring Test, Vitest, Playwright, Bugasura, logs centralizados (Loki/Grafana) | Automatización de pruebas del núcleo del MVP; registro de defectos. | Observabilidad centralizada y alertas de seguridad activas (ver anexo de Ciberseguridad). |

---

## 4. Conclusión

Con esta corrección, el documento de proyecto tiene un único punto de definición del MVP (sección 2), al que remiten sin contradicción la tabla de Tecnología, el Backlog por incrementos y el Plan de Pruebas. El criterio de corte no es una decisión de redacción sino el reflejo directo de lo que el perfil `docker-compose.mvp.yml` efectivamente despliega, lo que garantiza que la definición documental y el sistema construido no diverjan en futuras entregas.
