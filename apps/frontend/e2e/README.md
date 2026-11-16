# E2E + Accesibilidad (Playwright)

Suite de Playwright para PRISMA: humo de navegación end-to-end (login real contra Keycloak +
recorrido de las vistas principales) y verificación de accesibilidad **WCAG 2.1 AA**, el nivel
exigido a los sitios del Estado uruguayo por el **Decreto N° 406/022** (accesibilidad digital,
normativa técnica de AGESIC) — ver [impo.com.uy/bases/decretos-originales/406-2022](https://www.impo.com.uy/bases/decretos-originales/406-2022).
PRISMA es un proyecto académico que evalúa el Marco de Ciberseguridad v5.0 de AGESIC y está pensado
para organizaciones del Estado, por eso se adopta esa norma como objetivo.

## Estructura

```
e2e/
├── auth.setup.ts           # login real contra Keycloak, guarda la sesión en .auth/admin.json
├── helpers/
│   ├── keycloak.ts         # loginViaKeycloak() -- completa el formulario real de Keycloak
│   ├── a11y.ts             # assertNoWcagViolations() -- axe-core con los tags WCAG 2.1 AA
│   └── routes.ts           # rutas autenticadas "estáticas" a recorrer
├── public/                 # sin sesión (project "public-chromium"): sólo el login de Keycloak
└── authenticated/          # con sesión (projects "chromium"/"firefox"/"webkit"): resto del menú
```

## Cómo correrlo

### 1. Sólo accesibilidad del login de Keycloak + de las vistas autenticadas (chromium)

```bash
npm run test:a11y
```

### 2. Todo (multi-browser, humo + accesibilidad)

```bash
npm run test:e2e            # headless
npm run test:e2e:ui         # con la UI de Playwright, para depurar
```

### Requisitos de entorno

Los tests autenticados (todo lo que vive en `e2e/authenticated/`, más el login real de
`auth.setup.ts`) necesitan Keycloak **y** backend-core realmente corriendo y alcanzables:

```bash
# Desde la raíz del repo, con .env configurado (ver .env.example):
docker compose --profile app --profile security up -d postgres redis backend-core keycloak-db keycloak
```

Luego, en `apps/frontend/.env` (no versionado, copiar de `.env.example`):

```
VITE_KEYCLOAK_URL=http://localhost:8180/auth
```

El `/auth` es obligatorio: Keycloak corre con `KC_HTTP_RELATIVE_PATH=/auth` (ver
`docker-compose.yml`), así que nunca responde en la raíz del host.

Playwright arma el frontend con `vite build && vite preview` en el puerto **5173** (no el 4173 por
defecto de `vite preview`; ver `playwright.config.ts`). El realm versionado acepta cualquier
`redirectUri` (`"*"` en `infra/keycloak/realm-prisma.json`); si el realm de tu ambiente tiene una
lista fija de hosts, el login real falla en la pantalla de Keycloak con "Invalid parameter:
redirect_uri" hasta que se agregue `http://localhost:5173`.

**Importante — issuer del token:** si accedés a Keycloak directo por `localhost:8180` (como hace
esta suite en local, sin pasar por nginx), el JWT que emite tiene `iss=http://localhost:8180/auth/realms/prisma`.
`backend-core` sólo confía por defecto en los issuers de `https://prisma.local` y
`https://localhost` (`KEYCLOAK_TRUSTED_ISSUERS`, ver `docker-compose.yml`), así que **todas las
llamadas a la API devuelven 401** salvo que agregues ese origen a la lista:

```bash
export KEYCLOAK_TRUSTED_ISSUERS="https://prisma.local/auth/realms/prisma,https://localhost/auth/realms/prisma,http://localhost:8180/auth/realms/prisma"
docker compose --profile app --profile security up -d backend-core   # recrea el contenedor con el nuevo env
```

Sin este paso, `auth.setup.ts` y los tests de accesibilidad del login (que no llaman a la API)
funcionan igual; sólo `e2e/authenticated/navigation.spec.ts` (que además verifica "cero errores de
consola") y las vistas con datos que dependen del backend se ven afectadas por los 401.

### Contra el stack completo (CI / staging)

Si ya tenés el frontend corriendo en algún lado (contenedor, nginx, staging), apuntá la suite ahí
en vez de que Playwright levante su propio preview:

```bash
E2E_BASE_URL=https://localhost npx playwright test
```

## Usuario de prueba

El realm seedeado (`infra/keycloak/realm-prisma.json`) trae un único usuario:
`admin@prisma.local` / `Admin1234!` (rol `PRISMA_ADMIN`, cubre todas las rutas del menú). Tiene la
contraseña marcada como temporal, así que Keycloak puede pedir cambiarla en el primer login de un
realm recién importado — `helpers/keycloak.ts` maneja esa pantalla sola. Se puede sobreescribir
por `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD` para correr contra otro realm.

## Hallazgos detectados al construir esta suite (no corregidos acá)

Quedan documentados para que quien los aborde no tenga que re-descubrirlos:

1. **Botón de colapsar sidebar sin nombre accesible** (`AppSidebar.vue`) — **corregido**: el botón
   ya tiene `aria-label` y `title` («Contraer menú» / «Expandir menú»).
2. **Contraste insuficiente en texto `slate-400`** (regla axe `color-contrast`, impacto
   **serio**) — se ve sobre todo en estados vacíos ("sin resultados") y textos secundarios
   `text-xs`. WCAG 1.4.3.
3. **Dos `<h1>` por vista autenticada**: `AppHeader` pinta su propio heading con el título de la
   ruta además del `<h1>` de la vista. No es una violación axe per se, pero rompe la navegación por
   headings de un lector de pantalla y obligó a esta suite a usar `.first()` en varios
   `getByRole('heading', ...)`.
4. **Sin pantalla de login propia**: la app usa `onLoad: 'login-required'`, así que entrar sin sesión
   redirige directo al formulario de Keycloak (tema `prisma`). `e2e/public/login.spec.ts` espera
   `#username` en ese formulario.
