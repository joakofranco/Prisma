import { test, expect } from '@playwright/test';
import { assertNoWcagViolations } from '../helpers/a11y';

// Suite sin sesión (project "public-chromium" en playwright.config.ts): la app ya no tiene una
// pantalla de login propia, entrar a "/" sin sesión redirige directo al formulario de Keycloak
// (tema "prisma", infra/keycloak/themes/prisma/login).
const FIRST_PAINT_TIMEOUT = 20_000;

test('entrar a la app sin sesión lleva directo al login de Keycloak', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#username')).toBeVisible({ timeout: FIRST_PAINT_TIMEOUT });
  await expect(page.locator('#password')).toBeVisible();
  await expect(page.locator('#kc-login')).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
});

test('el formulario de login es operable con teclado', async ({ page }) => {
  await page.goto('/');
  const username = page.locator('#username');
  await username.waitFor({ state: 'visible', timeout: FIRST_PAINT_TIMEOUT });
  await expect(username).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.locator('#password')).toBeFocused();
});

test('el login de Keycloak no tiene violaciones WCAG 2.1 AA (Decreto N° 406/022)', async ({
  page,
}, testInfo) => {
  // El tema anima la entrada de la tarjeta (prisma-rise, 0.5 s) y axe medía el contraste a mitad de
  // la animación, con los colores mezclados con el fondo. El tema respeta prefers-reduced-motion.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await page.locator('#username').waitFor({ state: 'visible', timeout: FIRST_PAINT_TIMEOUT });
  await assertNoWcagViolations(page, testInfo);
});
