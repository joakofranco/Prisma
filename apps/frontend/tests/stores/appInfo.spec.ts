import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const http = vi.hoisted(() => ({ get: vi.fn() }));
const authState = vi.hoisted(() => ({ accessToken: null as string | null }));

vi.mock('axios', () => ({
  default: {
    get: http.get,
    isAxiosError: (e: unknown) => !!(e as { isAxiosError?: boolean })?.isAxiosError,
  },
}));
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authState }));
vi.mock('@/utils/appVersion', () => ({
  FRONTEND_VERSION: '0.6.0',
  FRONTEND_COMMIT: '',
  FRONTEND_BUILD_TIME: '',
  shortCommit: (c?: string | null) => (c ? c.slice(0, 7) : ''),
}));

import { useAppInfoStore } from '@/stores/appInfo';

const info = (over: Record<string, unknown> = {}) => ({
  data: {
    application: 'prisma-backend-core',
    version: '0.6.0',
    environment: 'production',
    development: false,
    ...over,
  },
});

describe('appInfo store: versión y entorno de la API', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    authState.accessToken = null;
  });

  it('pide /version a la API sin credenciales cuando no hay sesión', async () => {
    http.get.mockResolvedValue(info());
    const store = useAppInfoStore();

    await store.fetch();

    expect(http.get).toHaveBeenCalledTimes(1);
    expect(http.get.mock.calls[0][0]).toMatch(/\/version$/);
    expect(http.get.mock.calls[0][1].headers).toBeUndefined();
    expect(store.api?.version).toBe('0.6.0');
    expect(store.loaded).toBe(true);
  });

  it('con sesión manda el token, para recibir también el commit', async () => {
    authState.accessToken = 'abc';
    http.get.mockResolvedValue(info({ commit: 'abc1234' }));
    const store = useAppInfoStore();

    await store.fetch();

    expect(http.get.mock.calls[0][1].headers).toEqual({ Authorization: 'Bearer abc' });
    expect(store.api?.commit).toBe('abc1234');
  });

  it('un token vencido (401) se reintenta sin sesión en vez de fallar', async () => {
    authState.accessToken = 'vencido';
    http.get
      .mockRejectedValueOnce({ isAxiosError: true, response: { status: 401 } })
      .mockResolvedValueOnce(info());
    const store = useAppInfoStore();

    await store.fetch();

    expect(http.get).toHaveBeenCalledTimes(2);
    expect(http.get.mock.calls[1][1].headers).toBeUndefined();
    expect(store.api?.version).toBe('0.6.0');
  });

  it('si la API no responde la web sigue: sin versión ni cartel', async () => {
    http.get.mockRejectedValue(new Error('offline'));
    const store = useAppInfoStore();

    await store.fetch();

    expect(store.api).toBeNull();
    expect(store.loaded).toBe(true);
    expect(store.showEnvironmentBanner).toBe(false);
    expect(store.versionMismatch).toBe(false);
  });

  it('producción no lleva cartel; desarrollo y staging sí', async () => {
    const store = useAppInfoStore();

    http.get.mockResolvedValue(info({ environment: 'production' }));
    await store.fetch();
    expect(store.showEnvironmentBanner).toBe(false);
    expect(store.isProduction).toBe(true);

    http.get.mockResolvedValue(info({ environment: 'development', development: true }));
    await store.fetch();
    expect(store.showEnvironmentBanner).toBe(true);
    expect(store.environmentLabel).toBe('Entorno de desarrollo');

    http.get.mockResolvedValue(info({ environment: 'staging' }));
    await store.fetch();
    expect(store.showEnvironmentBanner).toBe(true);
    expect(store.environmentLabel).toBe('Entorno de pruebas (staging)');
  });

  it('un entorno desconocido también se marca, con su nombre', async () => {
    http.get.mockResolvedValue(info({ environment: 'qa' }));
    const store = useAppInfoStore();

    await store.fetch();

    expect(store.showEnvironmentBanner).toBe(true);
    expect(store.environmentLabel).toBe('Entorno: qa');
  });

  it('detecta una web y una API de versiones distintas', async () => {
    const store = useAppInfoStore();

    http.get.mockResolvedValue(info({ version: '0.6.0' }));
    await store.fetch();
    expect(store.versionMismatch).toBe(false);

    http.get.mockResolvedValue(info({ version: '0.5.2' }));
    await store.fetch();
    expect(store.versionMismatch).toBe(true);
  });

  it('una API sin versión conocida no se cuenta como desajuste', async () => {
    http.get.mockResolvedValue(info({ version: 'unknown' }));
    const store = useAppInfoStore();

    await store.fetch();

    expect(store.versionMismatch).toBe(false);
  });
});
