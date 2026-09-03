# 🤝 Guía de Contribución

## Flujo de trabajo (trunk-based, ramas cortas por PR)

> El plan original (`docs/Proyecto.md`) era GitFlow con una rama `dev` de integración y ramas
> personales `dev/<nombre>` por integrante. **En la práctica el repo no usa ese modelo** — ver
> [`README.md` → Estrategia de branching](../README.md#estrategia-de-branching), que lo documenta
> explícitamente. El flujo real, más simple, es:

1. **Toda rama parte desde `main`** (no existe una rama `dev` de integración en el día a día; la
   `dev` que ves en `git branch -r` sólo la usa `deploy.yml` para el entorno remoto, no el
   desarrollo).
2. **Preferir feature branches efímeras** (< 1 semana de vida).
3. **PRs pequeños** (< 500 líneas) son más fáciles de revisar — `pr-checks.yml` marca con una
   advertencia (no bloqueante) los que superan 1000 líneas.
4. **Un PR = una responsabilidad**. Si añadís una feature y refactorizás por el camino, hacé dos PRs.
5. **El PR se mergea contra `main`**, nunca contra `dev`.

## Ciclo típico

```
main ── (checkout -b) ──► fix/PRISMA-123-descripcion
                                │
                       (commits, Conventional Commits desde el primero)
                                │
                       (push + PR contra main)
                                │
                       (CI verde + CODEOWNERS si aplica)
                                │
                       (merge a main)
```

## Convenciones de commits

Usamos [Conventional Commits](https://www.conventionalcommits.org/):

```
<tipo>(<scope>): <descripción en minúscula>

[cuerpo opcional]

[footer opcional, ej. BREAKING CHANGE, refs #123]
```

Tipos permitidos: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.

Scopes: `frontend`, `backend-core`, `backend-ai`, `infra`, `docs`, `ci`, `deps`.

Ejemplos:

```
feat(backend-core): agrega endpoint POST /api/v1/evaluations
fix(frontend): corrige validación de nivel de madurez
test(backend-ai): añade tests del retriever RAG
chore(deps): bumpea spring-boot 3.3.5 → 3.5.16
```

## Pull Requests

1. Título debe seguir Conventional Commits.
2. Rellenar el template completo (`.github/PULL_REQUEST_TEMPLATE/`).
3. Añadir labels: `type: feature|bugfix|...` + área afectada.
4. Verificar que el CI está verde antes de pedir review.
5. Resolver TODOS los comentarios antes del merge.

### Validaciones automáticas del PR

El workflow `pr-checks.yml` valida:

- **Conventional Commits**: el título del PR debe seguir el formato `<tipo>(<scope>): <descripción>`.
- **Tamaño**: PRs > 1000 líneas generan advertencia (recomendado < 500).
- **Labels**: al menos un label de tipo (`type: feature`, `type: bugfix`, etc.).
- **Rama al día**: máximo 20 commits detrás de la rama base.
- **Descripción**: mínimo 30 caracteres.
- **ESLint**: código Frontend sin errores de lint (`--max-warnings 0`).
- **Prettier**: formato e indentación correctos (`prettier --check`).

## Revisión de código

Como reviewer, chequeá:

- ✅ ¿La funcionalidad hace lo que dice hacer? — probado no sólo con tests unitarios: para cambios
  en flujos completos (login, ciclo de vida de una evaluación, RBAC/multi-tenant), levantar el
  stack real (`make up-core`) y probarlo a mano por lo menos una vez sigue encontrando bugs que el
  mock no ve — ver [`Testing.md`](../docs/Testing.md#qa-manual-contra-el-stack-real) para varios
  ejemplos reales de esto en este mismo repo.
- ✅ ¿Hay tests suficientes? El gate de JaCoCo en `backend-core/pom.xml` exige ≥80% de líneas por
  paquete; hay una segunda regla al 100% pensada para el motor de cálculo de madurez, pero
  apunta a `uy.edu.prisma.maturity.*`, un paquete que **no existe** (la lógica real vive en
  `EvaluationService.calculateMaturity`, dentro de `application`) — la regla nunca se evalúa contra
  ninguna clase real. Hasta que se corrija el `<includes>`, no asumas que ese código tiene 100% sólo
  porque el build pasa.
- ✅ ¿Sigue las convenciones de código?
- ✅ ¿No introduce vulnerabilidades? (OWASP Top 10, especialmente BOLA/IDOR)
- ✅ ¿No rompe el multitenancy?
- ✅ ¿Se actualizó documentación afectada?
- ✅ ¿No hay secretos, credenciales o datos sensibles?
- ✅ ¿La performance es razonable? (queries N+1, allocations excesivas)

## Standards por tecnología

### Frontend (Vue 3)

- Usar Composition API (no Options API).
- Un componente = un archivo `.vue`.
- Props tipadas con TypeScript.
- Store por dominio (no un mega-store).
- Tests con Vitest + Vue Testing Library.

### Backend Core (Spring Boot)

- Arquitectura hexagonal — separar `domain`, `application`, `infrastructure`.
- Nunca inyectar `EntityManager` en controllers.
- DTOs SIEMPRE (no exponer entidades JPA).
- Excepciones de negocio custom → mapeadas por `@ControllerAdvice` a RFC 7807.
- Tests unitarios con JUnit 5 + Mockito. Integración con Testcontainers.

### Backend AI (FastAPI)

- Type hints obligatorios (`mypy` en CI).
- Pydantic v2 para todos los schemas.
- Async/await donde tenga sentido.
- Tests con pytest + pytest-asyncio.
