import { test, expect } from '@playwright/test';

// Menú de usuario del encabezado (C18): se cierra al clickear afuera, con ESC y al navegar. Requiere
// backend-core + Keycloak reales -- ver e2e/README.md.

test.describe('menú de usuario', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: /panel/i }).first()).toBeVisible({
      timeout: 10_000,
    });
  });

  const trigger = (page: import('@playwright/test').Page) =>
    page.locator('header button[aria-haspopup="true"]');

  test('abre con click y se cierra al clickear fuera de él', async ({ page }) => {
    await trigger(page).click();
    await expect(page.getByText('Cerrar Sesión')).toBeVisible();
    await expect(trigger(page)).toHaveAttribute('aria-expanded', 'true');

    await page.locator('main').click({ position: { x: 300, y: 300 } });
    await expect(page.getByText('Cerrar Sesión')).toBeHidden();
    await expect(trigger(page)).toHaveAttribute('aria-expanded', 'false');
  });

  test('se cierra con ESC y devuelve el foco al botón', async ({ page }) => {
    await trigger(page).click();
    await expect(page.getByText('Cerrar Sesión')).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(page.getByText('Cerrar Sesión')).toBeHidden();
    await expect(trigger(page)).toBeFocused();
  });

  test('no queda abierto al navegar por el menú lateral', async ({ page }) => {
    await trigger(page).click();
    await expect(page.getByText('Cerrar Sesión')).toBeVisible();

    await page
      .locator('aside')
      .getByRole('link', { name: /usuarios/i })
      .click();
    await expect(page).toHaveURL(/\/users/);
    await expect(page.getByText('Cerrar Sesión')).toBeHidden();
  });

  test('"Mi Cuenta" navega y cierra el menú', async ({ page }) => {
    await trigger(page).click();
    await page.getByText('Mi Cuenta').click();
    await expect(page).toHaveURL(/\/account/);
    await expect(page.getByText('Cerrar Sesión')).toBeHidden();
  });
});
