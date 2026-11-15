import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ accessToken: null }) }));

import EnvironmentBanner from '@/components/layout/EnvironmentBanner.vue';
import { useAppInfoStore } from '@/stores/appInfo';

const BASE_TITLE = 'PRISMA — Plataforma de Ciberseguridad';

function setEnvironment(environment: string | null) {
  const store = useAppInfoStore();
  store.api =
    environment === null
      ? null
      : {
          application: 'prisma-backend-core',
          version: '0.6.0',
          environment,
          development: environment === 'development',
        };
}

describe('EnvironmentBanner: marca los entornos que no son producción', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    document.title = BASE_TITLE;
  });

  it('en desarrollo muestra la franja, la etiqueta y marca la pestaña', async () => {
    setEnvironment('development');
    const wrapper = mount(EnvironmentBanner);
    await flushPromises();

    expect(wrapper.find('[data-testid="environment-banner"]').exists()).toBe(true);
    const label = wrapper.find('[data-testid="environment-label"]').text();
    expect(label).toContain('Entorno de desarrollo');
    expect(label).toContain('datos de prueba');
    expect(document.title).toBe(`[DEV] ${BASE_TITLE}`);
  });

  it('en staging dice que es de pruebas y no habla de datos de prueba', async () => {
    setEnvironment('staging');
    const wrapper = mount(EnvironmentBanner);
    await flushPromises();

    const label = wrapper.find('[data-testid="environment-label"]').text();
    expect(label).toContain('Entorno de pruebas (staging)');
    expect(label).not.toContain('datos de prueba');
    expect(document.title).toBe(`[STAGING] ${BASE_TITLE}`);
  });

  it('en producción no hay cartel ni se toca el título', async () => {
    setEnvironment('production');
    const wrapper = mount(EnvironmentBanner);
    await flushPromises();

    expect(wrapper.find('[data-testid="environment-banner"]').exists()).toBe(false);
    expect(document.title).toBe(BASE_TITLE);
  });

  it('sin información de la API no inventa un cartel', async () => {
    setEnvironment(null);
    const wrapper = mount(EnvironmentBanner);
    await flushPromises();

    expect(wrapper.find('[data-testid="environment-banner"]').exists()).toBe(false);
    expect(document.title).toBe(BASE_TITLE);
  });

  it('no intercepta los clics de la página', async () => {
    setEnvironment('development');
    const wrapper = mount(EnvironmentBanner);
    await flushPromises();

    expect(wrapper.find('[data-testid="environment-banner"]').classes()).toContain(
      'pointer-events-none',
    );
  });

  it('el prefijo no se acumula si el entorno cambia', async () => {
    setEnvironment('development');
    mount(EnvironmentBanner);
    await flushPromises();
    expect(document.title).toBe(`[DEV] ${BASE_TITLE}`);

    setEnvironment('staging');
    await flushPromises();

    expect(document.title).toBe(`[STAGING] ${BASE_TITLE}`);
  });

  it('si el entorno pasa a producción, la pestaña vuelve a su título', async () => {
    setEnvironment('development');
    mount(EnvironmentBanner);
    await flushPromises();
    expect(document.title).toContain('[DEV]');

    setEnvironment('production');
    await flushPromises();

    expect(document.title).toBe(BASE_TITLE);
  });
});
