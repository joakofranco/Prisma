import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import { createRouter, createMemoryHistory } from 'vue-router';
import App from '../src/App.vue';

describe('App', () => {
  it('monta el componente raíz con el outlet del router', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    });
    await router.push('/');
    await router.isReady();

    const wrapper = mount(App, {
      global: {
        plugins: [router],
        stubs: {
          NotificationToast: true,
          RouterView: true,
          AppLayout: { template: '<div><slot /></div>' },
        },
      },
    });
    expect(wrapper.findComponent({ name: 'RouterView' }).exists()).toBe(true);
  });
});
