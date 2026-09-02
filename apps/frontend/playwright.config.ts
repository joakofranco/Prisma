import { defineConfig, devices } from '@playwright/test';

// OJO: 5173, no el 4173 por defecto de `vite preview`. El cliente Keycloak "prisma-frontend"
// (infra/keycloak/realm-prisma.json -> redirectUris) sólo tiene whitelisteado localhost:5173
// (el puerto de `vite dev`, ver vite.config.ts) además de prisma.local/localhost detrás de nginx
// -- contra el 4173 el login real falla con "Invalid parameter: redirect_uri" en la propia
// pantalla de Keycloak.
const PREVIEW_PORT = 5173;

// Si E2E_BASE_URL está seteada (p.ej. en CI, apuntando al stack completo levantado con
// docker compose --profile app --profile security), se usa esa URL y Playwright NO intenta
// levantar un servidor propio. Sin ella, se asume uso local: se builda una vez y se sirve
// estático con `vite preview`.
//
// Se usa build+preview y NO `vite dev`: contra el dev server, el primer request de cada worker
// dispara la transformación on-demand de todo el grafo de módulos (router, pinia, keycloak-js,
// axios, chart.js...) y puede tardar más de los 5s por defecto de un `toBeVisible`, haciendo
// fallar tests de forma intermitente sin que haya ningún bug real de por medio.
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${PREVIEW_PORT}`;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  // En CI un test.only olvidado no debe pasar silenciosamente.
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // El proyecto "setup" (login contra Keycloak real) no es paralelizable con los que dependen
  // de él, pero los tests dentro de cada proyecto sí -- 1 worker en CI evita flakiness contra
  // un Keycloak/backend compartido que además rate-limitea intentos de login.
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['list'],
    ...(process.env.CI ? ([['github']] as const) : []),
  ],
  timeout: 30_000,
  expect: { timeout: 5_000 },
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    // Autentica una vez contra Keycloak real (no se mockea: es lo único que verifica el flujo
    // de login/roles de punta a punta) y persiste la sesión en e2e/.auth/admin.json para que el
    // resto de los tests autenticados no repitan el login -- ver e2e/auth.setup.ts.
    { name: 'setup', testMatch: /auth\.setup\.ts/ },

    // Suite pública: no depende de "setup" porque debe correr SIN sesión (ej. accesibilidad de
    // /login). Vive en e2e/public/**.
    {
      name: 'public-chromium',
      testDir: './e2e/public',
      use: { ...devices['Desktop Chrome'] },
    },

    // Suites autenticadas (humo + accesibilidad de las vistas post-login). Cross-browser real:
    // Keycloak/PKCE y varios estilos usan APIs con diferencias sutiles entre motores.
    {
      name: 'chromium',
      testDir: './e2e/authenticated',
      use: { ...devices['Desktop Chrome'], storageState: 'e2e/.auth/admin.json' },
      dependencies: ['setup'],
    },
    {
      name: 'firefox',
      testDir: './e2e/authenticated',
      use: { ...devices['Desktop Firefox'], storageState: 'e2e/.auth/admin.json' },
      dependencies: ['setup'],
    },
    {
      name: 'webkit',
      testDir: './e2e/authenticated',
      use: { ...devices['Desktop Safari'], storageState: 'e2e/.auth/admin.json' },
      dependencies: ['setup'],
    },
  ],

  // Sólo builda y levanta un preview local cuando no se apunta al stack de docker compose (CI),
  // donde el frontend ya está corriendo y E2E_BASE_URL apunta a él.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `npx vite build && npx vite preview --port ${PREVIEW_PORT}`,
        url: baseURL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
});
