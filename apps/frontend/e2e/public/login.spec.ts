import { test, expect } from '@playwright/test';
import { assertNoWcagViolations } from '../helpers/a11y';

// Suite sin sesión (project "public-chromium" en playwright.config.ts): valida /login, la única
// vista alcanzable sin autenticarse contra Keycloak.
//
// Por qué se espera el BOTÓN y no el heading "PRISMA" para dar por pintada la vista: mientras el
// router resuelve su navegación inicial (guard async, ver src/router/index.ts), App.vue puede
// pintar por un instante el layout autenticado (AppLayout, con su propio heading de fallback que
// también dice "PRISMA") antes de conmutar al layout "blank" real de Login -- ver el hallazgo
// documentado en e2e/README.md. "Iniciar Sesión con Keycloak" sólo existe en LoginView, así que
// no da ese falso positivo.
const FIRST_PAINT_TIMEOUT = 15_000;

test('la pantalla de login carga y ofrece el botón de Keycloak', async ({ page }) => {
  await page.goto('/login');
  const loginButton = page.getByRole('button', { name: /iniciar sesión con keycloak/i });
  await expect(loginButton).toBeVisible({ timeout: FIRST_PAINT_TIMEOUT });
  await expect(page.getByRole('heading', { name: 'PRISMA' })).toBeVisible();
});

test('el botón de login es alcanzable con teclado', async ({ page }) => {
  await page.goto('/login');
  const loginButton = page.getByRole('button', { name: /iniciar sesión con keycloak/i });
  await loginButton.waitFor({ state: 'visible', timeout: FIRST_PAINT_TIMEOUT });

  // WCAG 2.1.1 (Teclado): toda funcionalidad debe operarse sin mouse. Es el único control de la
  // página, así que debe llegar por Tab en pocos saltos (sin quedar atrapado antes ni saltearlo).
  for (let i = 0; i < 5; i++) {
    await page.keyboard.press('Tab');
    if (await loginButton.evaluate((el) => el === document.activeElement)) break;
  }
  await expect(loginButton).toBeFocused();
});

test('/login no tiene violaciones WCAG 2.1 AA (Decreto N° 406/022)', async ({ page }, testInfo) => {
  await page.goto('/login');
  // Espera el contenido real: si se escanea antes de que la app termine de montar Login (ver nota
  // arriba), axe puede analizar el flash del layout autenticado y "pasar" sin haber verificado
  // realmente /login.
  await page
    .getByRole('button', { name: /iniciar sesión con keycloak/i })
    .waitFor({ state: 'visible', timeout: FIRST_PAINT_TIMEOUT });
  await assertNoWcagViolations(page, testInfo);
});
