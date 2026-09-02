import { test, expect } from '@playwright/test';
import { AUTHENTICATED_ROUTES } from '../helpers/routes';

// Humo: cada vista principal del menú carga para un PRISMA_ADMIN, muestra su heading esperado y
// no tira errores de consola/página (p.ej. una promesa rota al llamar al backend). Requiere
// backend-core + Keycloak reales corriendo -- ver e2e/README.md. La sesión viene de
// e2e/auth.setup.ts vía storageState (projects "chromium"/"firefox"/"webkit").

for (const route of AUTHENTICATED_ROUTES) {
  test(`${route.name} (${route.path}) carga sin errores`, async ({ page }) => {
    const consoleErrors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    const pageErrors: string[] = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));

    await page.goto(route.path);
    // 10s en vez del default (5s): la carga inicial re-verifica el token contra Keycloak antes de
    // pintar la vista, sumado a la llamada real al backend para los datos de la página.
    await expect(page.getByRole('heading', { name: route.heading }).first()).toBeVisible({
      timeout: 10_000,
    });

    expect(pageErrors, `Errores no capturados en ${route.path}`).toEqual([]);
    expect(consoleErrors, `console.error en ${route.path}`).toEqual([]);
  });
}

test('la barra lateral permite navegar entre secciones', async ({ page }) => {
  await page.goto('/dashboard');
  await page.getByRole('link', { name: /catálogo/i }).click();
  await expect(page).toHaveURL(/\/catalog/);
  // .first(): AppHeader también pinta un <h1> con el título de la ruta (ver hallazgo en
  // e2e/README.md sobre headings duplicados), así que "Catálogo" matchea dos elementos.
  await expect(page.getByRole('heading', { name: /catálogo/i }).first()).toBeVisible();
});
