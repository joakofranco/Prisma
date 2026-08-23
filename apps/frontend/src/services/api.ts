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
    if (error.response?.status === 401 && authStore.isAuthenticated) {
      try {
        await authStore.refreshToken();
        const originalRequest = error.config;
        if (originalRequest) {
          originalRequest.headers.Authorization = `Bearer ${authStore.accessToken}`;
          return apiClient(originalRequest);
        }
      } catch {
        authStore.logout();
      }
    }
    return Promise.reject(error);
  },
);

export default apiClient;
