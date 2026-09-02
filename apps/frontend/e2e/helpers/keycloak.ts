import type { Page } from '@playwright/test';

/**
 * Usuario semilla del realm de Keycloak (ver infra/keycloak/realm-prisma.json). Se puede
 * sobreescribir por env var para correr contra un realm distinto (p.ej. un ambiente de staging)
 * sin tocar código.
 */
export const E2E_ADMIN = {
  email: process.env.E2E_ADMIN_EMAIL ?? 'admin@prisma.local',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'Admin1234!',
};

/**
 * Ejecuta el login real contra Keycloak (sin mockear el redirect ni el token): hace click en
 * "Iniciar sesión con Keycloak" en /login, completa el formulario servido por Keycloak en su
 * propio origen y vuelve a la app ya autenticado en /dashboard.
 *
 * El usuario semilla admin@prisma.local tiene `credentials.temporary: true` en el realm export,
 * así que Keycloak puede exigir la "required action" UPDATE_PASSWORD en el primer login de cada
 * realm nuevo: se maneja acá para que el resto de los tests no tenga que preocuparse por eso.
 */
export async function loginViaKeycloak(
  page: Page,
  { email, password }: { email: string; password: string } = E2E_ADMIN,
): Promise<void> {
  await page.goto('/login');
  await page.getByRole('button', { name: /iniciar sesión con keycloak/i }).click();

  // Cross-origin redirect a Keycloak (login.gub / prisma-keycloak). Se espera por los campos del
  // formulario en vez de por la URL exacta: el hostname/puerto varía según el ambiente
  // (localhost:8180 en local, otro host en docker compose/CI).
  const username = page.locator('#username');
  await username.waitFor({ state: 'visible', timeout: 15_000 });
  await username.fill(email);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();

  // Required action de cambio de contraseña temporal (sólo aplica la primera vez que el realm
  // seedeado hace login). Si no aparece en unos segundos, se asume que ya fue completada en una
  // corrida anterior contra el mismo Keycloak y se sigue de largo.
  const passwordNew = page.locator('#password-new');
  const requiresPasswordUpdate = await passwordNew
    .waitFor({ state: 'visible', timeout: 3_000 })
    .then(() => true)
    .catch(() => false);

  if (requiresPasswordUpdate) {
    // Reutilizamos la misma contraseña: no rota nada y evita que la siguiente corrida de la
    // suite deje de conocer la contraseña vigente del usuario semilla.
    await passwordNew.fill(password);
    await page.locator('#password-confirm').fill(password);
    await page.locator('input[type="submit"]').click();
  }

  await page.waitForURL(/\/dashboard/, { timeout: 15_000 });
}
