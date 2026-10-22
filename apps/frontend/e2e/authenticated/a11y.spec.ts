import { expect, test } from '@playwright/test';
import { assertNoWcagViolations } from '../helpers/a11y';
import { AUTHENTICATED_ROUTES } from '../helpers/routes';

// Escaneo WCAG 2.1 AA (Decreto N° 406/022, ver e2e/helpers/a11y.ts) de cada vista principal del
// menú, ya autenticado como PRISMA_ADMIN, en tema CLARO y en tema OSCURO. Requiere backend-core +
// Keycloak reales -- ver e2e/README.md.
//
// El tema oscuro se fuerza con la misma clave de localStorage que usa stores/theme.ts antes de que
// cargue la app, así que ahí se prueba exactamente lo que ve quien lo activa: la mayoría de los
// fallos de contraste (WCAG 1.4.3) aparecen SOLO en uno de los dos temas.

const THEMES = ['light', 'dark'] as const;

for (const theme of THEMES) {
  for (const route of AUTHENTICATED_ROUTES) {
    test(`${route.name} (${route.path}) no tiene violaciones WCAG 2.1 AA [tema ${theme}]`, async ({
      page,
    }, testInfo) => {
      await page.addInitScript((t) => {
        try {
          localStorage.setItem('prisma:theme', t);
        } catch {
          // storage bloqueado: la app arranca con su tema por defecto
        }
      }, theme);
      await page.goto(route.path);
      // Espera el contenido real (ver comentario equivalente en navigation.spec.ts) antes de
      // escanear: si axe corre sobre la vista todavía en blanco, "pasa" sin haber verificado nada.
      await page
        .getByRole('heading', { name: route.heading })
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 });
      // Confirma que el tema pedido realmente se aplicó (si no, el test "pasaría" sin probar nada).
      await expect(page.locator('html')).toHaveClass(
        theme === 'dark' ? /\bdark\b/ : /^((?!\bdark\b).)*$/,
      );
      await assertNoWcagViolations(page, testInfo);
    });
  }
}
