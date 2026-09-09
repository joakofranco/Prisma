import Keycloak from 'keycloak-js';
import { KEYCLOAK_URL, KEYCLOAK_REALM, KEYCLOAK_CLIENT_ID } from '@/utils/constants';

let keycloak: Keycloak | null = null;

export function initKeycloak(): Promise<boolean> {
  keycloak = new Keycloak({
    url: KEYCLOAK_URL,
    realm: KEYCLOAK_REALM,
    clientId: KEYCLOAK_CLIENT_ID,
  });

  return new Promise((resolve, reject) => {
    keycloak!
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
      })
      .then((authenticated) => {
        // Sin esto, keycloak-js no refresca el token por su cuenta: una pantalla larga (armar un
        // perfil comunitario, responder una evaluación) no dispara requests, el access token vence
        // y recién se descubre al guardar, con un 401. onTokenExpired + el timer de abajo
        // (startTokenRefresh) lo renuevan en segundo plano.
        keycloak!.onTokenExpired = () => {
          void keycloak!.updateToken(30).catch(() => undefined);
        };
        resolve(authenticated);
      })
      .catch((err) => {
        console.error('Keycloak init error:', err);
        reject(err);
      });
  });
}

let refreshTimer: ReturnType<typeof setInterval> | null = null;

/** Refresca el token en segundo plano cada 60s (si le quedan &lt;70s de vida). Idempotente. */
export function startTokenRefresh(): void {
  if (refreshTimer) return;
  refreshTimer = setInterval(() => {
    void updateToken(70);
  }, 60_000);
}

export function stopTokenRefresh(): void {
  if (refreshTimer) {
    clearInterval(refreshTimer);
    refreshTimer = null;
  }
}

export function getKeycloak(): Keycloak {
  if (!keycloak) throw new Error('Keycloak not initialized');
  return keycloak;
}

export async function loginKeycloak(): Promise<void> {
  const kc = getKeycloak();
  await kc.login({ redirectUri: window.location.origin });
}

export async function logoutKeycloak(): Promise<void> {
  const kc = getKeycloak();
  await kc.logout({ redirectUri: window.location.origin });
}

export function getKeycloakToken(): string | undefined {
  return getKeycloak()?.token;
}

export function getKeycloakRefreshToken(): string | undefined {
  return getKeycloak()?.refreshToken;
}

export function isTokenExpired(): boolean {
  return getKeycloak()?.isTokenExpired() ?? true;
}

export async function updateToken(minValidity: number = 30): Promise<boolean> {
  const kc = getKeycloak();
  try {
    return await kc.updateToken(minValidity);
  } catch {
    return false;
  }
}
