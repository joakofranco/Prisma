import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ServicesView from '@/views/services/ServicesView.vue';
import { platformServicesService } from '@/services/resources';
import type { PlatformService } from '@/types';

vi.mock('@/services/resources', () => ({
  platformServicesService: {
    getAll: vi.fn(),
    updateUrl: vi.fn(),
    reset: vi.fn(),
    replaceHost: vi.fn(),
  },
}));

const svc = (key: string, name: string, category: string, url: string): PlatformService => ({
  id: `id-${key}`,
  key,
  name,
  description: `desc ${name}`,
  category,
  url,
  defaultUrl: 'http://localhost:3000',
});

const grafana = svc('grafana', 'Grafana', 'OBSERVABILITY', 'http://localhost:3000');
const portainer = svc('portainer', 'Portainer', 'INFRASTRUCTURE', 'https://localhost:9443');

const mocked = vi.mocked(platformServicesService);

function mountView() {
  return mount(ServicesView, { global: { stubs: { Teleport: true } } });
}

describe('ServicesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    mocked.getAll.mockResolvedValue({ data: [grafana, portainer] } as never);
  });

  it('muestra cada servicio con su nombre, ícono y enlace que abre en otra pestaña', async () => {
    const wrapper = mountView();
    await flushPromises();

    const card = wrapper.get('[data-testid="service-card-grafana"]');
    expect(card.text()).toContain('Grafana');
    expect(card.get('img').attributes('alt')).toBe('Logo de Grafana');
    const open = wrapper.get('[data-testid="service-open-grafana"]');
    expect(open.attributes('href')).toBe('http://localhost:3000');
    expect(open.attributes('target')).toBe('_blank');
    expect(open.attributes('rel')).toContain('noopener');
  });

  it('muestra todos en una grilla que se adapta, con su categoría', async () => {
    const wrapper = mountView();
    await flushPromises();

    const grid = wrapper.get('[data-testid="services-grid"]');
    expect(grid.classes().join(' ')).toContain('auto-fill');
    expect(grid.findAll('article')).toHaveLength(2);
    expect(wrapper.get('[data-testid="service-category-grafana"]').text()).toBe('Observabilidad');
    expect(wrapper.get('[data-testid="service-category-portainer"]').text()).toBe(
      'Infraestructura',
    );
  });

  it('ordena por categoría aunque el servidor los mande mezclados', async () => {
    mocked.getAll.mockResolvedValue({ data: [portainer, grafana] } as never);
    const wrapper = mountView();
    await flushPromises();

    const keys = wrapper.findAll('article').map((a) => a.attributes('data-testid'));
    expect(keys).toEqual(['service-card-grafana', 'service-card-portainer']);
  });

  it('fuera de la VPN muestra el aviso y no la lista', async () => {
    mocked.getAll.mockRejectedValue({
      response: { status: 403, data: { code: 'VPN_REQUIRED', message: 'Solo desde la VPN' } },
    });
    const wrapper = mountView();
    await flushPromises();

    const blocked = wrapper.get('[data-testid="services-vpn-required"]');
    expect(blocked.text()).toContain('Solo desde la VPN');
    expect(wrapper.find('[data-testid="service-card-grafana"]').exists()).toBe(false);
  });

  it('otro error de carga se muestra como alerta', async () => {
    mocked.getAll.mockRejectedValue({ response: { status: 500, data: { message: 'Falló' } } });
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.get('[data-testid="services-load-error"]').text()).toContain('Falló');
  });

  it('edita la URL de un servicio y refleja el cambio', async () => {
    mocked.updateUrl.mockResolvedValue({
      data: { ...grafana, url: 'http://10.8.0.1:3000' },
    } as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="service-edit-grafana"]').trigger('click');
    await wrapper.get('#service-url').setValue('http://10.8.0.1:3000');
    await wrapper.get('[data-testid="service-edit-save"]').trigger('click');
    await flushPromises();

    expect(mocked.updateUrl).toHaveBeenCalledWith('id-grafana', 'http://10.8.0.1:3000');
    expect(wrapper.get('[data-testid="service-url-grafana"]').text()).toBe('http://10.8.0.1:3000');
  });

  it('no guarda una URL inválida', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="service-edit-grafana"]').trigger('click');
    await wrapper.get('#service-url').setValue('localhost:3000');

    expect(wrapper.get('[data-testid="service-edit-save"]').attributes('disabled')).toBeDefined();
    expect(mocked.updateUrl).not.toHaveBeenCalled();
  });

  it('restablece la URL de fábrica solo cuando fue modificada', async () => {
    mocked.getAll.mockResolvedValue({
      data: [{ ...grafana, url: 'http://10.8.0.1:3000' }, portainer],
    } as never);
    mocked.reset.mockResolvedValue({ data: grafana } as never);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find('[data-testid="service-reset-grafana"]').exists()).toBe(true);
    await wrapper.get('[data-testid="service-reset-grafana"]').trigger('click');
    await flushPromises();

    expect(mocked.reset).toHaveBeenCalledWith('id-grafana');
    expect(wrapper.get('[data-testid="service-url-grafana"]').text()).toBe('http://localhost:3000');
  });

  it('cambia el host de todos los servicios', async () => {
    mocked.replaceHost.mockResolvedValue({
      data: [
        { ...grafana, url: 'http://10.8.0.1:3000' },
        { ...portainer, url: 'https://10.8.0.1:9443' },
      ],
    } as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="services-host-open"]').trigger('click');
    await wrapper.get('#services-host').setValue('10.8.0.1');
    await wrapper.get('[data-testid="services-host-save"]').trigger('click');
    await flushPromises();

    expect(mocked.replaceHost).toHaveBeenCalledWith('10.8.0.1');
    expect(wrapper.get('[data-testid="service-url-portainer"]').text()).toBe(
      'https://10.8.0.1:9443',
    );
  });

  it('rechaza un host con esquema o puerto', async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-testid="services-host-open"]').trigger('click');
    await wrapper.get('#services-host').setValue('http://10.8.0.1:3000');

    expect(wrapper.get('[data-testid="services-host-save"]').attributes('disabled')).toBeDefined();
  });
});
