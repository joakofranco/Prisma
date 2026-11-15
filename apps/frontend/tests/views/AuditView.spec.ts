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
  sendCitationFeedback: vi.fn(),
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

describe('AuditView: citas de la IA (PRI-359)', () => {
  const citation = (n: number) => ({
    evidenceId: `ev-${n}`,
    fileName: `politica-${n}.pdf`,
    location: `Página ${n}`,
    snippet: `fragmento ${n}`,
    score: 0.7,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    vi.spyOn(console, 'error').mockImplementation(() => {});
    auditService.getObservations.mockResolvedValue({ data: [] });
    auditService.getVerdicts.mockResolvedValue({ data: [] });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [{ id: 'ev-1', controlId: 'ctl-1', fileName: 'politica-1.pdf' }],
    });
    evidenceService.getCitations.mockResolvedValue({ data: [citation(1), citation(2)] });
    evidenceService.sendCitationFeedback.mockResolvedValue({ data: undefined });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  async function searchCitations(status = 'IN_AUDIT') {
    const wrapper = await mountView(status);
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Buscar citas'))!
      .trigger('click');
    await flushPromises();
    return wrapper;
  }

  it('cada cita tiene «Aceptar cita» y «Rechazar cita», además de «Registrar validación»', async () => {
    const wrapper = await searchCitations();

    expect(wrapper.findAll('[data-testid="accept-citation"]')).toHaveLength(2);
    expect(wrapper.findAll('[data-testid="reject-citation"]')).toHaveLength(2);
    expect(wrapper.text()).toContain('Registrar validación');
  });

  it('aceptar una cita la envía con el control y el fragmento, y queda marcada', async () => {
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    expect(evidenceService.sendCitationFeedback).toHaveBeenCalledWith('eval-1', [
      {
        controlId: 'ctl-1',
        fileName: 'politica-1.pdf',
        location: 'Página 1',
        snippet: 'fragmento 1',
        score: 0.7,
        decision: 'ACCEPTED',
      },
    ]);
    expect(wrapper.findAll('[data-testid="accept-citation"]')[0].attributes('aria-pressed')).toBe(
      'true',
    );
    expect(wrapper.findAll('[data-testid="accept-citation"]')[1].attributes('aria-pressed')).toBe(
      'false',
    );
  });

  it('rechazar una cita la envía como REJECTED', async () => {
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="reject-citation"]')[1].trigger('click');
    await flushPromises();

    expect(evidenceService.sendCitationFeedback.mock.calls[0][1][0]).toMatchObject({
      snippet: 'fragmento 2',
      decision: 'REJECTED',
    });
    expect(wrapper.findAll('[data-testid="reject-citation"]')[1].attributes('aria-pressed')).toBe(
      'true',
    );
  });

  it('cambiar de opinión envía la nueva decisión; repetir la misma no envía nada', async () => {
    const wrapper = await searchCitations();
    const accept = () => wrapper.findAll('[data-testid="accept-citation"]')[0];
    const reject = () => wrapper.findAll('[data-testid="reject-citation"]')[0];

    await accept().trigger('click');
    await flushPromises();
    await accept().trigger('click');
    await flushPromises();
    expect(evidenceService.sendCitationFeedback).toHaveBeenCalledTimes(1);

    await reject().trigger('click');
    await flushPromises();
    expect(evidenceService.sendCitationFeedback).toHaveBeenCalledTimes(2);
    expect(reject().attributes('aria-pressed')).toBe('true');
    expect(accept().attributes('aria-pressed')).toBe('false');
  });

  it('si el backend rechaza la decisión avisa y la cita no queda marcada', async () => {
    evidenceService.sendCitationFeedback.mockRejectedValue({
      response: { data: { message: 'Solo se pueden evaluar citas en auditoría' } },
    });
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('Solo se pueden evaluar citas en auditoría');
    expect(wrapper.findAll('[data-testid="accept-citation"]')[0].attributes('aria-pressed')).toBe(
      'false',
    );
  });

  it('fuera de IN_AUDIT no se puede aceptar ni rechazar', async () => {
    const wrapper = await searchCitations('RETURNED');

    expect(wrapper.findAll('[data-testid="accept-citation"]')).toHaveLength(0);
    expect(wrapper.findAll('[data-testid="reject-citation"]')).toHaveLength(0);
  });

  it('una búsqueda nueva descarta las decisiones de la anterior', async () => {
    const wrapper = await searchCitations();
    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Buscar citas'))!
      .trigger('click');
    await flushPromises();

    expect(wrapper.findAll('[data-testid="accept-citation"]')[0].attributes('aria-pressed')).toBe(
      'false',
    );
  });

  it('con la IA caída (503) dice que no está disponible y no «sin resultados»', async () => {
    evidenceService.getCitations.mockRejectedValue({
      response: { status: 503, data: { code: 'AI_UNAVAILABLE' } },
    });
    const wrapper = await searchCitations();

    expect(wrapper.find('[data-testid="citations-ai-unavailable"]').text()).toContain(
      'asistente de IA no está disponible',
    );
    expect(wrapper.text()).not.toContain('No se encontraron fragmentos relevantes');
    expect(notification.error).not.toHaveBeenCalled();
  });

  it('una búsqueda sin resultados sigue diciendo que no se encontraron fragmentos', async () => {
    evidenceService.getCitations.mockResolvedValue({ data: [] });
    const wrapper = await searchCitations();

    expect(wrapper.text()).toContain('No se encontraron fragmentos relevantes');
    expect(wrapper.find('[data-testid="citations-ai-unavailable"]').exists()).toBe(false);
  });

  it('otro error (por ejemplo 409 fuera de auditoría) avisa con el mensaje del backend', async () => {
    evidenceService.getCitations.mockRejectedValue({
      response: { status: 409, data: { message: 'La búsqueda de citas solo está disponible' } },
    });
    const wrapper = await searchCitations();

    expect(notification.error).toHaveBeenCalledWith('La búsqueda de citas solo está disponible');
    expect(wrapper.find('[data-testid="citations-ai-unavailable"]').exists()).toBe(false);
  });

  it('tras una búsqueda con IA caída, reintentar y tener éxito limpia el aviso', async () => {
    evidenceService.getCitations.mockRejectedValueOnce({ response: { status: 503 } });
    const wrapper = await searchCitations();
    expect(wrapper.find('[data-testid="citations-ai-unavailable"]').exists()).toBe(true);

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Buscar citas'))!
      .trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="citations-ai-unavailable"]').exists()).toBe(false);
    expect(wrapper.findAll('[data-testid="accept-citation"]')).toHaveLength(2);
  });
  // ---- PRI-360 · RF-EVI-15: la observación queda ligada a la evidencia citada ----

  async function openObservationFromCitation(wrapper: Awaited<ReturnType<typeof searchCitations>>) {
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Registrar validación'))!
      .trigger('click');
    await flushPromises();
  }

  it('«Registrar validación» precarga el texto y al guardar envía el evidenceId de la cita', async () => {
    auditService.createObservation.mockResolvedValue({ data: {} });
    const wrapper = await searchCitations();

    await openObservationFromCitation(wrapper);
    const textarea = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    expect(textarea.value).toContain('politica-1.pdf');
    expect(textarea.value).toContain('Página 1');
    expect(textarea.value).toContain('fragmento 1');
    await Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Crear')!
      .click();
    await flushPromises();

    expect(auditService.createObservation).toHaveBeenCalledWith(
      expect.objectContaining({
        evaluationId: 'eval-1',
        controlId: 'ctl-1',
        evidenceId: 'ev-1',
      }),
    );
  });

  it('una observación nueva (sin cita) no arrastra el evidenceId de la anterior', async () => {
    auditService.createObservation.mockResolvedValue({ data: {} });
    const wrapper = await searchCitations();
    await openObservationFromCitation(wrapper);
    // Cierra sin guardar y abre «Nueva Observación».
    await Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Cancelar')!
      .click();
    await flushPromises();
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Nueva Observación'))!
      .trigger('click');
    await flushPromises();
    const textarea = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    textarea.value = 'Hallazgo general';
    textarea.dispatchEvent(new Event('input'));
    await Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Crear')!
      .click();
    await flushPromises();

    expect(auditService.createObservation.mock.calls[0][0].evidenceId).toBeUndefined();
  });

  it('la lista muestra el documento citado de la observación y permite abrirlo', async () => {
    auditService.getObservations.mockResolvedValue({
      data: [
        {
          id: 'o1',
          evaluationId: 'eval-1',
          type: 'OBSERVATION',
          description: 'Revisión de evidencia para AD.1 — "politica-1.pdf" (Página 2): texto',
          status: 'OPEN',
          createdBy: 'u',
          createdAt: '2026-11-03T10:00:00Z',
          evidenceId: 'ev-1',
          evidenceFileName: 'politica-1.pdf',
        },
        {
          id: 'o2',
          evaluationId: 'eval-1',
          type: 'OBSERVATION',
          description: 'General',
          status: 'OPEN',
          createdBy: 'u',
          createdAt: '2026-11-03T10:00:00Z',
        },
      ],
    });
    evidenceService.download.mockResolvedValue({ data: new Blob(['x']) });
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    URL.createObjectURL = vi.fn(() => 'blob:doc');
    const wrapper = await mountView('IN_AUDIT');

    const cited = wrapper.findAll('[data-testid="observation-evidence"]');
    expect(cited).toHaveLength(1);
    expect(cited[0].text()).toContain('politica-1.pdf');
    await wrapper.find('[data-testid="observation-open-document"]').trigger('click');
    await flushPromises();

    expect(evidenceService.download).toHaveBeenCalledWith('ev-1');
    expect(openSpy).toHaveBeenCalledWith('blob:doc#page=2', '_blank');
  });
});

describe('AuditView: «Buscar citas» según el estado (RF-EVI-14)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    vi.spyOn(console, 'error').mockImplementation(() => {});
    auditService.getObservations.mockResolvedValue({ data: [] });
    auditService.getVerdicts.mockResolvedValue({ data: [] });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [{ id: 'ev-1', controlId: 'ctl-1', fileName: 'politica-1.pdf' }],
    });
    evidenceService.getCitations.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  it('con la evaluación en auditoría se puede buscar citas', async () => {
    const wrapper = await mountView('IN_AUDIT');

    const button = wrapper.find('[data-testid="search-citations"]');
    expect((button.element as HTMLButtonElement).disabled).toBe(false);
  });

  it.each(['READY_FOR_AUDIT', 'RETURNED', 'APPROVED', 'ARCHIVED'])(
    '%s: el botón está deshabilitado y no consulta a la IA',
    async (status) => {
      const wrapper = await mountView(status);

      const button = wrapper.find('[data-testid="search-citations"]');
      expect((button.element as HTMLButtonElement).disabled).toBe(true);
      expect(button.attributes('title')).toContain(
        'solo está disponible con la evaluación en auditoría',
      );

      await button.trigger('click');
      await flushPromises();
      expect(evidenceService.getCitations).not.toHaveBeenCalled();
    },
  );
});
