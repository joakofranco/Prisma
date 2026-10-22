import { test as setup } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loginViaKeycloak } from './helpers/keycloak';

// Consumido por playwright.config.ts (projects "chromium"/"firefox"/"webkit" -> use.storageState)
// para que sólo este archivo pague el costo del login real contra Keycloak.
export const ADMIN_STORAGE_STATE = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '.auth/admin.json',
);

setup('autenticarse como PRISMA_ADMIN', async ({ page }, testInfo) => {
  await loginViaKeycloak(page);
  // Cada proyecto de setup puede indicar dónde guardar su sesión (metadata.storageState, ver
  // playwright.config.ts); sin eso se usa la de Chromium/Firefox.
  const file =
    (testInfo.project.metadata.storageState as string | undefined) ?? ADMIN_STORAGE_STATE;
  await page.context().storageState({ path: file });
});
