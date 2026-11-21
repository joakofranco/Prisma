import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const authStore = vi.hoisted(() => ({ isAdmin: true }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
const organizationsService = vi.hoisted(() => ({ getById: vi.fn(), setResearchMode: vi.fn() }));
const evaluationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const aiTraceService = vi.hoisted(() => ({ exportJsonl: vi.fn() }));

vi.mock('@/services/resources', () => ({
  organizationsService,
  evaluationsService,
  aiTraceService,
}));
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStore }));
vi.mock('@/stores/notification', () => ({ useNotificationStore: () => notification }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'org-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/components/charts/EvaluationMaturityTrendChart.vue', () => ({
  default: { template: '<div />' },
}));

import OrganizationDetailView from '@/views/organizations/OrganizationDetailView.vue';

const org = (researchMode: boolean) => ({
  id: 'org-1',
  name: 'Acme',
  identifierIsRut: false,
  sector: 'Público',
  size: 'Grande',
  enabled: true,
  researchMode,
  createdAt: '2026-01-01T00:00:00Z',
});

async function mountView(researchMode = false) {
  organizationsService.getById.mockResolvedValue({ data: org(researchMode) });
  evaluationsService.getAll.mockResolvedValue({ data: { data: [] } });
  const wrapper = mount(OrganizationDetailView, { global: { stubs: { RouterLink: true } } });
  await flushPromises();
  return wrapper;
}

describe('OrganizationDetailView: modo investigación (C8)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authStore.isAdmin = true;
    vi.stubGlobal(
      'confirm',
      vi.fn(() => true),
    );
  });

  it('solo PRISMA_ADMIN ve el interruptor', async () => {
    authStore.isAdmin = false;
    const wrapper = await mountView();

    expect(wrapper.find('[data-testid="research-mode-card"]').exists()).toBe(false);
  });

  it('encender pide confirmación, llama al servicio y avisa', async () => {
    organizationsService.setResearchMode.mockResolvedValue({ data: org(true) });
    const wrapper = await mountView(false);
    expect(wrapper.find('[data-testid="research-mode-on"]').exists()).toBe(false);

    await wrapper.find('[data-testid="toggle-research-mode"]').trigger('click');
    await flushPromises();

    expect(confirm).toHaveBeenCalled();
    expect(organizationsService.setResearchMode).toHaveBeenCalledWith('org-1', true);
    expect(wrapper.find('[data-testid="research-mode-on"]').exists()).toBe(true);
    expect(notification.success).toHaveBeenCalledWith('Modo investigación encendido');
  });

  it('si no confirma, no cambia nada', async () => {
    vi.stubGlobal(
      'confirm',
      vi.fn(() => false),
    );
    const wrapper = await mountView(false);

    await wrapper.find('[data-testid="toggle-research-mode"]').trigger('click');
    await flushPromises();

    expect(organizationsService.setResearchMode).not.toHaveBeenCalled();
  });

  it('apagar no pide confirmación y deja de mostrar el modo activo', async () => {
    organizationsService.setResearchMode.mockResolvedValue({ data: org(false) });
    const wrapper = await mountView(true);
    expect(wrapper.find('[data-testid="research-mode-on"]').exists()).toBe(true);

    await wrapper.find('[data-testid="toggle-research-mode"]').trigger('click');
    await flushPromises();

    expect(confirm).not.toHaveBeenCalled();
    expect(organizationsService.setResearchMode).toHaveBeenCalledWith('org-1', false);
    expect(wrapper.find('[data-testid="research-mode-on"]').exists()).toBe(false);
  });

  it('si el servidor falla avisa y deja el estado como estaba', async () => {
    organizationsService.setResearchMode.mockRejectedValue(new Error('boom'));
    const wrapper = await mountView(false);

    await wrapper.find('[data-testid="toggle-research-mode"]').trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('No se pudo cambiar el modo investigación');
    expect(wrapper.find('[data-testid="research-mode-on"]').exists()).toBe(false);
  });

  it('exporta las trazas en JSONL', async () => {
    aiTraceService.exportJsonl.mockResolvedValue({ data: new Blob(['{}\n']) });
    const createUrl = vi.fn(() => 'blob:x');
    vi.stubGlobal('URL', { createObjectURL: createUrl, revokeObjectURL: vi.fn() });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const wrapper = await mountView(true);

    await wrapper.find('[data-testid="export-traces"]').trigger('click');
    await flushPromises();

    expect(aiTraceService.exportJsonl).toHaveBeenCalled();
    expect(createUrl).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
  });
});
