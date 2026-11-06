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
const evaluationsService = vi.hoisted(() => ({ getById: vi.fn(), getFlags: vi.fn() }));
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
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
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

  it('lista los controles marcados para revisar y quién los marcó (PRI-352)', async () => {
    evaluationsService.getFlags.mockResolvedValue({
      data: [{ controlId: 'ctl-2', flaggedBy: 'u', flaggedByName: 'Ana Pérez', flaggedAt: null }],
    });
    const wrapper = await mountView();

    const items = wrapper.findAll('[data-testid="flagged-control"]');
    expect(items).toHaveLength(1);
    expect(items[0].text()).toContain('AD.2');
    expect(items[0].text()).toContain('Ana Pérez');
  });

  it('no muestra la sección si no hay controles marcados', async () => {
    const wrapper = await mountView();

    expect(wrapper.find('[data-testid="flagged-controls"]').exists()).toBe(false);
  });
});

describe('AuditView: observaciones (PRI-354)', () => {
  const observation = (id: string, status = 'OPEN') => ({
    id,
    evaluationId: 'eval-1',
    type: 'OBSERVATION',
    description: `Hallazgo ${id}`,
    status,
    createdBy: 'u',
    createdAt: '2026-11-03T10:00:00Z',
    createdByName: 'Ana',
  });

  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    vi.spyOn(console, 'error').mockImplementation(() => {});
    auditService.getObservations.mockResolvedValue({
      data: [observation('o1'), observation('o2', 'IN_PROGRESS')],
    });
    auditService.getVerdicts.mockResolvedValue({ data: [] });
    auditService.updateObservation.mockResolvedValue({ data: {} });
    auditService.createObservation.mockResolvedValue({ data: {} });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  const buttonWithTitle = (wrapper: Awaited<ReturnType<typeof mountView>>, title: string) =>
    wrapper.findAll(`button[title="${title}"]`);

  it('un fallo al cambiar el estado se avisa con el mensaje del backend', async () => {
    auditService.updateObservation.mockRejectedValue({
      response: {
        data: {
          message:
            'Solo se pueden modificar observaciones mientras la evaluación está en auditoría',
        },
      },
    });
    const wrapper = await mountView();

    await buttonWithTitle(wrapper, 'Marcar en progreso')[0].trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'Solo se pueden modificar observaciones mientras la evaluación está en auditoría',
    );
  });

  it('si el error no trae mensaje, el aviso es genérico (no queda en silencio)', async () => {
    auditService.updateObservation.mockRejectedValue(new Error(''));
    const wrapper = await mountView();

    await buttonWithTitle(wrapper, 'Marcar resuelta')[0].trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'No se pudo cambiar el estado de la observación',
    );
  });

  it('cambiar el estado con éxito no avisa de error y recarga la lista', async () => {
    const wrapper = await mountView();
    const calls = auditService.getObservations.mock.calls.length;

    await buttonWithTitle(wrapper, 'Marcar en progreso')[0].trigger('click');
    await flushPromises();

    expect(auditService.updateObservation).toHaveBeenCalledWith('o1', { status: 'IN_PROGRESS' });
    expect(notification.error).not.toHaveBeenCalled();
    expect(auditService.getObservations.mock.calls.length).toBe(calls + 1);
  });

  it('cada observación tiene un botón «Editar» que abre el modal con sus datos', async () => {
    const wrapper = await mountView();
    expect(wrapper.findAll('[data-testid="edit-observation"]')).toHaveLength(2);

    await wrapper.findAll('[data-testid="edit-observation"]')[0].trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Editar Observación');
    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    expect(box.value).toBe('Hallazgo o1');
  });

  it('editar tipo y descripción llama a updateObservation y recarga', async () => {
    const wrapper = await mountView();
    await wrapper.findAll('[data-testid="edit-observation"]')[0].trigger('click');
    await flushPromises();

    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = 'Hallazgo corregido';
    box.dispatchEvent(new Event('input'));
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Guardar')!
      .click();
    await flushPromises();

    expect(auditService.updateObservation).toHaveBeenCalledWith('o1', {
      type: 'OBSERVATION',
      description: 'Hallazgo corregido',
    });
    expect(notification.success).toHaveBeenCalledWith('Observación actualizada');
    expect(auditService.createObservation).not.toHaveBeenCalled();
  });

  it('un error al editar se avisa con el mensaje del backend', async () => {
    auditService.updateObservation.mockRejectedValue({
      response: { data: { message: 'La descripción de la observación no puede estar vacía' } },
    });
    const wrapper = await mountView();
    await wrapper.findAll('[data-testid="edit-observation"]')[0].trigger('click');
    await flushPromises();

    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Guardar')!
      .click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'La descripción de la observación no puede estar vacía',
    );
  });

  it('no deja guardar una descripción vacía y lo dice en el campo', async () => {
    const wrapper = await mountView();
    await wrapper.findAll('[data-testid="edit-observation"]')[0].trigger('click');
    await flushPromises();

    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = '   ';
    box.dispatchEvent(new Event('input'));
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Guardar')!
      .click();
    await flushPromises();

    expect(auditService.updateObservation).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('La descripción es obligatoria');
  });

  it('«Nueva observación» sigue creando (el modal no queda en modo edición)', async () => {
    const wrapper = await mountView();
    await wrapper.findAll('[data-testid="edit-observation"]')[0].trigger('click');
    await flushPromises();
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Cancelar')!
      .click();
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Nueva Observación'))!
      .trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Nueva Observación');
    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    expect(box.value).toBe('');
  });

  it('crear con un error del backend avisa con su mensaje', async () => {
    auditService.createObservation.mockRejectedValue({
      response: {
        data: {
          message:
            'Solo se pueden registrar observaciones mientras la evaluación está en auditoría',
        },
      },
    });
    const wrapper = await mountView();
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Nueva Observación'))!
      .trigger('click');
    await flushPromises();

    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = 'algo';
    box.dispatchEvent(new Event('input'));
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Crear')!
      .click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'Solo se pueden registrar observaciones mientras la evaluación está en auditoría',
    );
  });

  it('fuera de IN_AUDIT se ocultan «Nueva observación», «Editar» y los botones de estado', async () => {
    for (const status of ['DRAFT', 'RETURNED', 'APPROVED', 'ARCHIVED']) {
      document.body.innerHTML = '';
      const wrapper = await mountView(status);

      expect(
        wrapper.findAll('button').some((b) => b.text().includes('Nueva Observación')),
        status,
      ).toBe(false);
      expect(wrapper.findAll('[data-testid="edit-observation"]'), status).toHaveLength(0);
      expect(buttonWithTitle(wrapper, 'Marcar en progreso'), status).toHaveLength(0);
      expect(buttonWithTitle(wrapper, 'Marcar resuelta'), status).toHaveLength(0);
      expect(wrapper.find('[data-testid="observations-locked"]').exists(), status).toBe(true);
    }
  });

  it('en IN_AUDIT no aparece el aviso de bloqueo', async () => {
    const wrapper = await mountView('IN_AUDIT');

    expect(wrapper.find('[data-testid="observations-locked"]').exists()).toBe(false);
  });

  it('fuera de IN_AUDIT las observaciones siguen visibles para el auditor', async () => {
    const wrapper = await mountView('RETURNED');

    expect(wrapper.text()).toContain('Hallazgo o1');
    expect(wrapper.text()).toContain('Hallazgo o2');
  });
});
