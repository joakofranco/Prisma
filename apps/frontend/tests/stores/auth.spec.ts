import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const recordSessionEvent = vi.hoisted(() => vi.fn<(event: string) => Promise<void>>());
const logoutKeycloak = vi.hoisted(() => vi.fn<() => Promise<void>>());

vi.mock('@/services/resources', () => ({ accountService: { recordSessionEvent } }));
vi.mock('@/services/auth', () => ({
  initKeycloak: vi.fn(),
  loginKeycloak: vi.fn(),
  logoutKeycloak,
  getKeycloak: vi.fn(() => null),
  updateToken: vi.fn(),
  startTokenRefresh: vi.fn(),
  stopTokenRefresh: vi.fn(),
}));
vi.mock('@/plugins/logrocket', () => ({ identifyLogRocketUser: vi.fn() }));

import { useAuthStore } from '@/stores/auth';

describe('auth store: logout', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    recordSessionEvent.mockReset();
    logoutKeycloak.mockReset();
    recordSessionEvent.mockResolvedValue();
    logoutKeycloak.mockResolvedValue();
  });

  it('manda un solo LOGOUT aunque se llame varias veces a la vez', async () => {
    const store = useAuthStore();

    await Promise.all([store.logout(), store.logout(), store.logout()]);

    expect(recordSessionEvent).toHaveBeenCalledTimes(1);
    expect(recordSessionEvent).toHaveBeenCalledWith('LOGOUT');
    expect(logoutKeycloak).toHaveBeenCalledTimes(1);
  });

  it('cierra la sesión de Keycloak aunque el aviso de LOGOUT falle', async () => {
    recordSessionEvent.mockRejectedValue(new Error('401'));
    const store = useAuthStore();

    await store.logout();

    expect(logoutKeycloak).toHaveBeenCalledTimes(1);
    expect(store.isAuthenticated).toBe(false);
  });
});
