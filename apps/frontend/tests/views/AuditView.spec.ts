import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const auditService = vi.hoisted(() => ({
  getObservations: vi.fn(),
  createObservation: vi.fn(),
  updateObservation: vi.fn(),
  getVerdicts: vi.fn(),
  setVerdict: vi.fn(),
}));
const evidenceService = vi.hoisted(() => ({
  getByEvaluation: vi.fn(),
  getCitations: vi.fn(),
  download: vi.fn(),
}));
const evaluationsService = vi.hoisted(() => ({ getById: vi.fn() }));
const catalogService = vi.hoisted(() => ({ getByVersion: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));

vi.mock('@/services/resources', () => ({
  auditService,
  evidenceService,
  evaluationsService,
  catalogService,
}));
vi.mock('@/composables/useUtils', () => ({ useNotification: () => notification }));
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasAnyRole: () => true }) }));
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { evaluationId: 'eval-1' } }) }));

import AuditView from '@/views/audit/AuditView.vue';

const control = (id: string, code: string) => ({
  id,
  code,
  description: `desc ${code}`,
  targetLevel: 1,
});

const catalog = {
  functions: [
    {
      id: 'f',
      name: 'F',
      description: '',
      categories: [
        {
          id: 'c',
          name: 'C',
          description: '',
          subcategories: [
            {
              id: 's',
              name: 'S',
              description: '',
              requirements: [
                {
                  id: 'r',
                  code: 'R',
                  description: '',
                  controls: [control('ctl-1', 'AD.1'), control('ctl-2', 'AD.2')],
                },
              ],
            },
          ],
        },
      ],
    },
  ],
};

async function mountView(status = 'IN_AUDIT') {
  evaluationsService.getById.mockResolvedValue({
    data: { id: 'eval-1', name: 'E', status, catalogVersion: '5.0', communityProfileId: null },
  });
  const wrapper = mount(AuditView, { global: { stubs: { RouterLink: true } } });
  await flushPromises();
  return wrapper;
}

describe('AuditView: dictamen por control (RF-AUD-06)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    auditService.getObservations.mockResolvedValue({ data: [] });
    auditService.getVerdicts.mockResolvedValue({
      data: [{ controlId: 'ctl-1', verdict: 'VALIDATED', setBy: null, setByName: null }],
    });
    auditService.setVerdict.mockResolvedValue({ data: {} });
    evidenceService.getByEvaluation.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  it('lista los controles con su dictamen guardado y el resumen', async () => {
    const wrapper = await mountView();

    const rows = wrapper.findAll('[data-testid="verdict-row"]');
    expect(rows).toHaveLength(2);
    expect((rows[0].find('select').element as HTMLSelectElement).value).toBe('VALIDATED');
    expect((rows[1].find('select').element as HTMLSelectElement).value).toBe('');
    expect(wrapper.find('[data-testid="verdict-summary"]').text()).toContain('Validados: 1');
    expect(wrapper.find('[data-testid="verdict-summary"]').text()).toContain('Sin dictamen: 1');
  });

  it('al elegir un dictamen lo envía al backend', async () => {
    const wrapper = await mountView();

    await wrapper
      .findAll('[data-testid="verdict-row"]')[1]
      .find('select')
      .setValue('NEEDS_MORE_EVIDENCE');
    await flushPromises();

    expect(auditService.setVerdict).toHaveBeenCalledWith('eval-1', 'ctl-2', 'NEEDS_MORE_EVIDENCE');
  });

  it('si el backend rechaza el dictamen, vuelve al valor anterior y avisa', async () => {
    auditService.setVerdict.mockRejectedValue(new Error('boom'));
    const wrapper = await mountView();

    const select = wrapper.findAll('[data-testid="verdict-row"]')[0].find('select');
    await select.setValue('NOT_VALIDATED');
    await flushPromises();

    expect(notification.error).toHaveBeenCalled();
    expect((select.element as HTMLSelectElement).value).toBe('VALIDATED');
  });

  it('con la evaluación fuera de auditoría los selectores quedan deshabilitados', async () => {
    const wrapper = await mountView('APPROVED');

    const select = wrapper.find('[data-testid="verdict-row"] select');
    expect((select.element as HTMLSelectElement).disabled).toBe(true);
    expect(wrapper.find('[data-testid="verdict-section"]').text()).toContain(
      'solo mientras la evaluación está en auditoría',
    );
  });

});
