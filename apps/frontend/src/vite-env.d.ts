/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_AI_API_BASE_URL: string;
  readonly VITE_KEYCLOAK_URL: string;
  readonly VITE_KEYCLOAK_REALM: string;
  readonly VITE_KEYCLOAK_CLIENT_ID: string;
  /** Commit y fecha de construcción de la web (los pasa el CI; opcionales). */
  readonly VITE_APP_COMMIT?: string;
  readonly VITE_APP_BUILD_TIME?: string;
}

/** Versión de la web (package.json), inyectada por Vite al construir. */
declare const __APP_VERSION__: string;

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
