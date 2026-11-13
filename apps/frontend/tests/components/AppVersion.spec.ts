import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ accessToken: null }) }));
vi.mock('@/utils/appVersion', () => ({
  FRONTEND_VERSION: '0.6.0',
  FRONTEND_COMMIT: 'fed9876543',
  FRONTEND_BUILD_TIME: '2026-11-13T09:00:00Z',
  shortCommit: (c?: string | null) => (c ? c.slice(0, 7) : ''),
}));

import AppVersion from '@/components/layout/AppVersion.vue';
import { useAppInfoStore } from '@/stores/appInfo';

function setApi(over: Record<string, unknown> | null) {
  useAppInfoStore().api =
    over === null
      ? null
      : {
          application: 'prisma-backend-core',
          version: '0.6.0',
          environment: 'production',
          development: false,
          ...over,
        };
}

describe('AppVersion: versión de la web y de la API', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('muestra una sola versión cuando la web y la API coinciden', () => {
    setApi({});
    const wrapper = mount(AppVersion);

    expect(wrapper.text()).toBe('v0.6.0');
    expect(wrapper.find('[data-testid="version-mismatch"]').exists()).toBe(false);
  });

  it('muestra las dos, resaltadas, cuando no coinciden', () => {
    setApi({ version: '0.5.2' });
    const wrapper = mount(AppVersion);

    const mismatch = wrapper.find('[data-testid="version-mismatch"]');
    expect(mismatch.text()).toBe('web v0.6.0 · API v0.5.2');
    expect(wrapper.attributes('title')).toContain('versiones distintas');
  });

  it('sin respuesta de la API muestra la versión de la web', () => {
    setApi(null);
    const wrapper = mount(AppVersion);

    expect(wrapper.text()).toBe('v0.6.0');
    expect(wrapper.attributes('title')).toContain('API: sin información');
  });

  it('en un entorno que no es producción lo nombra', () => {
    setApi({ environment: 'development', development: true });

    expect(mount(AppVersion).text()).toContain('development');
  });

  it('en producción no agrega el entorno', () => {
    setApi({ environment: 'production' });

    expect(mount(AppVersion).text()).not.toContain('production');
  });

  it('el detalle trae commit y fecha de la web y de la API', () => {
    setApi({ commit: 'abc1234def', buildTime: '2026-11-13T10:00:00Z' });
    const title = mount(AppVersion).attributes('title')!;

    expect(title).toContain('Web: v0.6.0 (fed9876)');
    expect(title).toContain('Web construida: 2026-11-13T09:00:00Z');
    expect(title).toContain('API: v0.6.0 (abc1234)');
    expect(title).toContain('API construida: 2026-11-13T10:00:00Z');
    expect(title).toContain('Entorno: production');
  });

  it('sin sesión la API no manda commit y el detalle no lo inventa', () => {
    setApi({});
    const title = mount(AppVersion).attributes('title')!;

    expect(title).toContain('API: v0.6.0');
    expect(title).not.toContain('API: v0.6.0 (');
  });
});
