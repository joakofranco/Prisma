import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const auditService = vi.hoisted(() => ({ getObservations: vi.fn() }));

vi.mock('@/services/resources', () => ({ auditService }));

import AuditObservationsReadOnly from '../../src/components/evaluations/AuditObservationsReadOnly.vue';

const obs = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  evaluationId: 'eval-1',
  type: 'NON_CONFORMITY',
  description: `Descripción ${id}`,
  status: 'OPEN',
  createdBy: 'u',
  createdAt: '2026-11-03T10:00:00Z',
  createdByName: 'Ana Auditora',
  controlCode: null,
  subcategoryCode: null,
  ...extra,
});

async function factory() {
  const wrapper = mount(AuditObservationsReadOnly, { props: { evaluationId: 'eval-1' } });
  await flushPromises();
  return wrapper;
}

describe('AuditObservationsReadOnly (PRI-354)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('pide las observaciones de la evaluación y muestra tipo, descripción, estado, autor y fecha', async () => {
    auditService.getObservations.mockResolvedValue({ data: [obs('1')] });
    const wrapper = await factory();

    expect(auditService.getObservations).toHaveBeenCalledWith('eval-1');
    const text = wrapper.find('[data-testid="auditor-observation"]').text();
    expect(text).toContain('No Conformidad');
    expect(text).toContain('Descripción 1');
    expect(text).toContain('Abierta');
    expect(text).toContain('Registrada por Ana Auditora');
    expect(text).toMatch(/2026|03\/11|3 nov/);
  });

  it('muestra el código del control y de la subcategoría cuando los tiene', async () => {
    auditService.getObservations.mockResolvedValue({
      data: [obs('1', { controlCode: 'PL.1-1', subcategoryCode: 'GV.OC' }), obs('2')],
    });
    const wrapper = await factory();

    const items = wrapper.findAll('[data-testid="auditor-observation"]');
    expect(items[0].text()).toContain('Control PL.1-1');
    expect(items[0].text()).toContain('GV.OC');
    expect(items[1].text()).not.toContain('Control');
  });

  it('es de solo lectura: no hay botones ni campos para editar', async () => {
    auditService.getObservations.mockResolvedValue({ data: [obs('1'), obs('2')] });
    const wrapper = await factory();

    expect(wrapper.findAll('button')).toHaveLength(0);
    expect(wrapper.findAll('input, textarea, select')).toHaveLength(0);
  });

  it('si el auditor no registró observaciones lo dice', async () => {
    auditService.getObservations.mockResolvedValue({ data: [] });
    const wrapper = await factory();

    expect(wrapper.find('[data-testid="auditor-observations-empty"]').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="auditor-observation"]')).toHaveLength(0);
  });

  it('si no se pueden cargar muestra un aviso, no una lista vacía engañosa', async () => {
    auditService.getObservations.mockRejectedValue(new Error('boom'));
    const wrapper = await factory();

    expect(wrapper.find('[role="alert"]').text()).toContain('No se pudieron cargar');
    expect(wrapper.find('[data-testid="auditor-observations-empty"]').exists()).toBe(false);
  });

  it('tiene un título accesible y los tipos tienen su color', async () => {
    auditService.getObservations.mockResolvedValue({
      data: [obs('1', { type: 'RECOMMENDATION' }), obs('2', { type: 'OBSERVATION' })],
    });
    const wrapper = await factory();

    const section = wrapper.find('section[aria-labelledby]');
    expect(wrapper.find(`[id="${section.attributes('aria-labelledby')}"]`).text()).toContain(
      'Observaciones del auditor',
    );
    const items = wrapper.findAll('[data-testid="auditor-observation"]');
    expect(items[0].classes().join(' ')).toContain('amber');
    expect(items[1].classes().join(' ')).toContain('blue');
  });
});
