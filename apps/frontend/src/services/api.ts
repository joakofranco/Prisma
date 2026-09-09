import axios from 'axios';
import type { AxiosInstance, AxiosError } from 'axios';
import { API_BASE_URL } from '@/utils/constants';
import { useAuthStore } from '@/stores/auth';

const apiClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config) => {
  const authStore = useAuthStore();
  if (authStore.accessToken) {
    config.headers.Authorization = `Bearer ${authStore.accessToken}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const authStore = useAuthStore();
    const originalRequest = error.config as
      (typeof error.config & { _retried?: boolean }) | undefined;
    if (
      error.response?.status === 401 &&
      authStore.isAuthenticated &&
      originalRequest &&
      !originalRequest._retried
    ) {
      // refreshToken() nunca lanza: devuelve false si el refresh token también venció.
      const refreshed = await authStore.refreshToken();
      if (!refreshed) {
        await authStore.logout();
        return Promise.reject(error);
      }
      originalRequest._retried = true;
      originalRequest.headers.Authorization = `Bearer ${authStore.accessToken}`;
      return apiClient(originalRequest);
    }
    return Promise.reject(error);
  },
);

export default apiClient;
