import { test } from '@playwright/test';
import { assertNoWcagViolations } from '../helpers/a11y';
import { AUTHENTICATED_ROUTES } from '../helpers/routes';

// Escaneo WCAG 2.1 AA (Decreto N° 406/022, ver e2e/helpers/a11y.ts) de cada vista principal del
// menú, ya autenticado como PRISMA_ADMIN. Requiere backend-core + Keycloak reales -- ver
// e2e/README.md.

for (const route of AUTHENTICATED_ROUTES) {
  test(`${route.name} (${route.path}) no tiene violaciones WCAG 2.1 AA`, async ({
    page,
  }, testInfo) => {
    await page.goto(route.path);
    // Espera el contenido real (ver comentario equivalente en navigation.spec.ts) antes de
    // escanear: si axe corre sobre la vista todavía en blanco, "pasa" sin haber verificado nada.
    await page
      .getByRole('heading', { name: route.heading })
      .first()
      .waitFor({ state: 'visible', timeout: 10_000 });
    await assertNoWcagViolations(page, testInfo);
  });
}
