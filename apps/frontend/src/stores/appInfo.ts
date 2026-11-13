import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import axios from 'axios';
import { API_BASE_URL } from '@/utils/constants';
import { FRONTEND_VERSION } from '@/utils/appVersion';
import { useAuthStore } from '@/stores/auth';

/** Lo que informa `GET /api/version`. `commit` y `buildTime` solo vienen con sesión iniciada. */
export interface ApiVersionInfo {
  application: string;
  version: string;
  environment: string;
  development: boolean;
  commit?: string | null;
  buildTime?: string | null;
}

const ENVIRONMENT_LABELS: Record<string, string> = {
  development: 'Entorno de desarrollo',
  staging: 'Entorno de pruebas (staging)',
};

/**
 * Versión y entorno de la API. Se pide una vez al arrancar (es público: lo necesita la pantalla de
 * login) y otra vez al iniciar sesión, para sumar el commit. Con un cliente propio, sin los
 * interceptores de sesión: un token vencido no tiene que cerrar la sesión ni redirigir por una
 * consulta informativa.
 */
export const useAppInfoStore = defineStore('appInfo', () => {
  const api = ref<ApiVersionInfo | null>(null);
  const loaded = ref(false);

  async function request(token: string | null) {
    return axios.get<ApiVersionInfo>(`${API_BASE_URL}/version`, {
      timeout: 5000,
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    });
  }

  async function fetch() {
    const token = useAuthStore().accessToken;
    try {
      let response;
      try {
        response = await request(token || null);
      } catch (err) {
        // Un token vencido da 401 aun en un endpoint público: se reintenta sin sesión.
        if (token && axios.isAxiosError(err) && err.response?.status === 401) {
          response = await request(null);
        } else {
          throw err;
        }
      }
      api.value = response.data;
    } catch {
      // Sin la información la web funciona igual: solo no muestra versión ni cartel.
      api.value = null;
    } finally {
      loaded.value = true;
    }
  }

  const environment = computed(() => api.value?.environment ?? null);
  const isProduction = computed(() => environment.value === 'production');
  /** Cualquier entorno que no sea producción se marca con un cartel. */
  const showEnvironmentBanner = computed(() => api.value !== null && !isProduction.value);
  const environmentLabel = computed(() => {
    if (!environment.value) return '';
    return ENVIRONMENT_LABELS[environment.value] ?? `Entorno: ${environment.value}`;
  });
  /** La web y la API son de versiones distintas (despliegue a medias). */
  const versionMismatch = computed(
    () =>
      api.value !== null &&
      api.value.version !== 'unknown' &&
      api.value.version !== FRONTEND_VERSION,
  );

  return {
    api,
    loaded,
    fetch,
    environment,
    isProduction,
    showEnvironmentBanner,
    environmentLabel,
    versionMismatch,
  };
});
