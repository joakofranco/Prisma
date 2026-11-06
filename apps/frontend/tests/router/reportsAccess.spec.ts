import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const authState = vi.hoisted(() => ({ roles: [] as string[] }));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    isAuthenticated: true,
    init: async () => {},
    hasAnyRole: (...r: string[]) => r.some((x) => authState.roles.includes(x)),
  }),
}));

import router from '@/router';

async function visit(roles: string[]) {
  authState.roles = roles;
  await router.push('/dashboard');
  await router.push('/reports');
  return router.currentRoute.value.path;
}

// La primera visita a /reports compila la vista real (import dinámico): con la suite en
// paralelo puede pasar los 5 s por defecto.
vi.setConfig({ testTimeout: 30000 });

describe('ruta /reports (RF-REP-05)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    window.scrollTo = vi.fn();
  });

  it('declara que solo PRISMA_ADMIN y ORG_RESPONSIBLE pueden entrar', () => {
    const route = router.getRoutes().find((r) => r.path === '/reports');
    expect(route?.meta.roles).toEqual(['PRISMA_ADMIN', 'ORG_RESPONSIBLE']);
  });

  it.each([['PRISMA_ADMIN'], ['ORG_RESPONSIBLE']])('%s entra a /reports', async (role) => {
    expect(await visit([role])).toBe('/reports');
  });

  it.each([['AUDITOR'], ['INTERNAL_EVALUATOR'], ['VIEWER']])(
    '%s es redirigido al panel',
    async (role) => {
      expect(await visit([role])).toBe('/dashboard');
    },
  );
});
