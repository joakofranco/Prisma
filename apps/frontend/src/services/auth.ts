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
        resolve(authenticated);
      })
      .catch((err) => {
        console.error('Keycloak init error:', err);
        reject(err);
      });
  });
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
