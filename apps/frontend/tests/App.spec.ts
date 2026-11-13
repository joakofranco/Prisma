import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { reactive } from 'vue';
import { createRouter, createMemoryHistory } from 'vue-router';

const http = vi.hoisted(() => ({ get: vi.fn() }));
const authState = vi.hoisted(() => ({
  value: null as unknown as { isAuthenticated: boolean; accessToken: string | null },
}));

vi.mock('axios', () => ({ default: { get: http.get, isAxiosError: () => false } }));
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authState.value }));

import App from '../src/App.vue';

async function mountApp() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  });
  await router.push('/');
  await router.isReady();

  const wrapper = mount(App, {
    global: {
      plugins: [createPinia(), router],
      stubs: {
        NotificationToast: true,
        RouterView: true,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  });
  await flushPromises();
  return wrapper;
}

describe('App', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    authState.value = reactive({ isAuthenticated: false, accessToken: null });
    http.get.mockResolvedValue({
      data: {
        application: 'prisma-backend-core',
        version: '0.6.0',
        environment: 'development',
        development: true,
      },
    });
  });

  it('monta el componente raíz con el outlet del router', async () => {
    const wrapper = await mountApp();

    expect(wrapper.findComponent({ name: 'RouterView' }).exists()).toBe(true);
  });

  it('pide la versión y el entorno de la API al arrancar, y otra vez al iniciar sesión', async () => {
    await mountApp();
    expect(http.get).toHaveBeenCalledTimes(1);

    authState.value.isAuthenticated = true;
    await flushPromises();

    expect(http.get).toHaveBeenCalledTimes(2);
  });

  it('en un entorno de desarrollo muestra el cartel', async () => {
    const wrapper = await mountApp();

    expect(wrapper.find('[data-testid="environment-banner"]').exists()).toBe(true);
  });
});
