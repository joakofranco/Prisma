/**
 * Versión de ESTA compilación de la web: la de package.json (la inyecta Vite) y, si el CI los pasó,
 * el commit y la fecha de construcción. La de la API se pide en runtime (stores/appInfo.ts).
 */
export const FRONTEND_VERSION: string =
  typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev';
export const FRONTEND_COMMIT: string = import.meta.env.VITE_APP_COMMIT || '';
export const FRONTEND_BUILD_TIME: string = import.meta.env.VITE_APP_BUILD_TIME || '';

/** Commit abreviado (7 caracteres) para mostrar. */
export function shortCommit(commit: string | null | undefined): string {
  return commit ? commit.slice(0, 7) : '';
}
