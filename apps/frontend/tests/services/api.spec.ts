import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { AxiosError } from 'axios';

const authStore = {
  isAuthenticated: true,
  accessToken: 'token',
  refreshToken: vi.fn<() => Promise<boolean>>(),
  logout: vi.fn<() => Promise<void>>(),
};

vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStore }));

import apiClient from '@/services/api';

function respond(config: InternalAxiosRequestConfig, status: number): Promise<AxiosResponse> {
  const response = { data: {}, status, statusText: '', headers: {}, config } as AxiosResponse;
  if (status >= 400) {
    return Promise.reject(new AxiosError('fail', String(status), config, null, response));
  }
  return Promise.resolve(response);
}

describe('apiClient: respuesta 401', () => {
  let adapter: ReturnType<typeof vi.fn<AxiosAdapter>>;

  beforeEach(() => {
    authStore.isAuthenticated = true;
    authStore.refreshToken.mockReset();
    authStore.logout.mockReset();
    authStore.logout.mockResolvedValue();
    adapter = vi.fn<AxiosAdapter>();
    apiClient.defaults.adapter = adapter;
  });

  it('no refresca el token ni cierra sesión cuando falla session-event (evita el bucle de logout)', async () => {
    adapter.mockImplementation((config) => respond(config, 401));

    await expect(apiClient.post('/account/session-event', { event: 'LOGOUT' })).rejects.toThrow();

    expect(authStore.refreshToken).not.toHaveBeenCalled();
    expect(authStore.logout).not.toHaveBeenCalled();
    expect(adapter).toHaveBeenCalledTimes(1);
  });

  it('cierra sesión una sola vez si el refresco del token falla en otra petición', async () => {
    adapter.mockImplementation((config) => respond(config, 401));
    authStore.refreshToken.mockResolvedValue(false);

    await expect(apiClient.get('/users')).rejects.toThrow();

    expect(authStore.refreshToken).toHaveBeenCalledTimes(1);
    expect(authStore.logout).toHaveBeenCalledTimes(1);
    expect(adapter).toHaveBeenCalledTimes(1);
  });

  it('reintenta una sola vez cuando el refresco del token funciona', async () => {
    adapter.mockImplementationOnce((config) => respond(config, 401));
    adapter.mockImplementationOnce((config) => respond(config, 200));
    authStore.refreshToken.mockResolvedValue(true);

    const response = await apiClient.get('/users');

    expect(response.status).toBe(200);
    expect(adapter).toHaveBeenCalledTimes(2);
    expect(authStore.logout).not.toHaveBeenCalled();
  });
});
