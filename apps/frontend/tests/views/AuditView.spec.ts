import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const auditService = vi.hoisted(() => ({
  getObservations: vi.fn(),
  createObservation: vi.fn(),
  updateObservation: vi.fn(),
  getVerdicts: vi.fn(),
  setVerdict: vi.fn(),
  undoCitationVerdict: vi.fn(),
}));
const evidenceService = vi.hoisted(() => ({
  getByEvaluation: vi.fn(),
  getCitations: vi.fn(),
  getSavedCitations: vi.fn(),
  getSuggestions: vi.fn(),
  decideSuggestion: vi.fn(),
  refreshCitations: vi.fn(),
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

  it('cada cita tiene «Aceptar cita» y «Rechazar cita», además de «Registrar como observación»', async () => {
    const wrapper = await searchCitations();

    expect(wrapper.findAll('[data-testid="accept-citation"]')).toHaveLength(2);
    expect(wrapper.findAll('[data-testid="reject-citation"]')).toHaveLength(2);
    expect(wrapper.text()).toContain('Registrar como observación');
  });

  it('aceptar una cita la envía con el control y el fragmento, y queda marcada', async () => {
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    expect(evidenceService.sendCitationFeedback).toHaveBeenCalledWith(
      'eval-1',
      [
        {
          controlId: 'ctl-1',
          fileName: 'politica-1.pdf',
          location: 'Página 1',
          snippet: 'fragmento 1',
          score: 0.7,
          decision: 'ACCEPTED',
          evidenceId: 'ev-1',
          citationId: null,
        },
      ],
      false,
    );
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

    // Con citas ya cargadas el botón pasa a ser «Actualizar» y busca de nuevo.
    evidenceService.refreshCitations.mockResolvedValue({ data: [citation(1), citation(2)] });
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Actualizar'))!
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
      .find((b) => b.text().includes('Registrar como observación'))!
      .trigger('click');
    await flushPromises();
  }

  it('«Registrar como observación» precarga el texto y al guardar envía el evidenceId de la cita', async () => {
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

describe('AuditView: aceptar una cita valida el control (C4a) y puntaje (C3.1)', () => {
  const citation = (n: number, score: number) => ({
    evidenceId: `ev-${n}`,
    fileName: `politica-${n}.pdf`,
    location: `Página ${n}`,
    snippet: `fragmento ${n}`,
    score,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    vi.spyOn(console, 'error').mockImplementation(() => {});
    auditService.getObservations.mockResolvedValue({ data: [] });
    auditService.getVerdicts.mockResolvedValue({ data: [] });
    auditService.setVerdict.mockResolvedValue({ data: {} });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [{ id: 'ev-1', controlId: 'ctl-1', fileName: 'politica-1.pdf' }],
    });
    evidenceService.getCitations.mockResolvedValue({
      data: [citation(1, 0.41), citation(2, 0.83), citation(3, 0.55)],
    });
    evidenceService.sendCitationFeedback.mockResolvedValue({
      data: {
        verdicts: [{ controlId: 'ctl-1', verdict: 'VALIDATED', source: 'CITATION', changed: true }],
      },
    });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  async function searchCitations() {
    const wrapper = await mountView('IN_AUDIT');
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('Buscar citas'))!
      .trigger('click');
    await flushPromises();
    return wrapper;
  }

  const verdictRow = (wrapper: Awaited<ReturnType<typeof searchCitations>>, i: number) =>
    wrapper.findAll('[data-testid="verdict-row"]')[i];

  it('muestra el puntaje de cada cita y marca la mejor coincidencia', async () => {
    const wrapper = await searchCitations();

    const scores = wrapper.findAll('[data-testid="citation-score"]').map((n) => n.text());
    expect(scores).toEqual(['Relevancia 0,41', 'Relevancia 0,83', 'Relevancia 0,55']);
    const best = wrapper.findAll('[data-testid="best-citation"]');
    expect(best).toHaveLength(1);
    // La mejor es la segunda (0,83).
    const cards = wrapper.findAll('[data-testid="citation-score"]');
    expect(cards[1].element.parentElement!.textContent).toContain('Mejor coincidencia');
  });

  it('con una sola cita no hay «mejor coincidencia»', async () => {
    evidenceService.getCitations.mockResolvedValue({ data: [citation(1, 0.9)] });
    const wrapper = await searchCitations();

    expect(wrapper.find('[data-testid="best-citation"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="citation-score"]').text()).toContain('0,90');
  });

  it('una cita sin puntaje no rompe la pantalla', async () => {
    evidenceService.getCitations.mockResolvedValue({
      data: [{ ...citation(1, 0), score: null }],
    });
    const wrapper = await searchCitations();

    expect(wrapper.find('[data-testid="citation-score"]').text()).toContain('—');
  });

  it('el texto ya no dice que la IA es «sólo una guía» y explica qué significa aceptar', async () => {
    const wrapper = await searchCitations();

    expect(wrapper.text()).not.toContain('Es sólo una guía');
    expect(wrapper.text()).toContain('Aceptar una cita');
    expect(wrapper.text()).toContain('lo pasa a «Validado»');
    expect(wrapper.text()).toContain('Rechazar una cita no cambia el dictamen');
  });

  it('aceptar una cita pasa el control a Validado y lo muestra «por cita»', async () => {
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="accept-citation"]')[1].trigger('click');
    await flushPromises();

    const row = verdictRow(wrapper, 0);
    expect((row.find('select').element as HTMLSelectElement).value).toBe('VALIDATED');
    expect(row.find('[data-testid="verdict-source"]').text()).toBe(
      'Validado por cita (politica-2.pdf, Página 2)',
    );
  });

  it('un dictamen que ya venía de una cita se muestra «por cita» al cargar', async () => {
    auditService.getVerdicts.mockResolvedValue({
      data: [
        {
          controlId: 'ctl-1',
          verdict: 'VALIDATED',
          source: 'CITATION',
          sourceRef: { fileName: 'politica-9.pdf', location: 'Página 9' },
        },
      ],
    });
    const wrapper = await mountView('IN_AUDIT');

    expect(verdictRow(wrapper, 0).find('[data-testid="verdict-source"]').text()).toContain(
      'politica-9.pdf, Página 9',
    );
  });

  it('un control que ya estaba Validado a mano no pasa a «por cita»', async () => {
    evidenceService.sendCitationFeedback.mockResolvedValue({
      data: {
        verdicts: [{ controlId: 'ctl-1', verdict: 'VALIDATED', source: 'MANUAL', changed: false }],
      },
    });
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    expect(verdictRow(wrapper, 0).find('[data-testid="verdict-source"]').exists()).toBe(false);
  });

  it('rechazar una cita no toca el dictamen', async () => {
    evidenceService.sendCitationFeedback.mockResolvedValue({ data: { verdicts: [] } });
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="reject-citation"]')[0].trigger('click');
    await flushPromises();

    expect((verdictRow(wrapper, 0).find('select').element as HTMLSelectElement).value).toBe('');
    expect(wrapper.find('[data-testid="verdict-source"]').exists()).toBe(false);
  });

  it('fijar el dictamen a mano después le quita el origen «por cita»', async () => {
    const wrapper = await searchCitations();
    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    await verdictRow(wrapper, 0).find('select').setValue('NOT_VALIDATED');
    await flushPromises();

    expect(wrapper.find('[data-testid="verdict-source"]').exists()).toBe(false);
    expect(auditService.setVerdict).toHaveBeenCalledWith('eval-1', 'ctl-1', 'NOT_VALIDATED');
  });

  // ---- pisar un dictamen puesto a mano pide confirmación (D10) ----

  const overrideError = {
    response: {
      status: 409,
      data: {
        code: 'VERDICT_OVERRIDE_REQUIRED',
        message: 'ya tiene un dictamen',
        details: { currentVerdict: ['NOT_VALIDATED'], controlCode: ['AD.1'] },
      },
    },
  };

  it('si hay un dictamen manual distinto, pide confirmación y no marca la cita todavía', async () => {
    evidenceService.sendCitationFeedback.mockRejectedValueOnce(overrideError);
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    const message = document.body.querySelector('[data-testid="override-message"]')!;
    expect(message.textContent).toContain('AD.1');
    expect(message.textContent).toContain('No validado');
    expect(message.textContent).toContain('Validado');
    expect(wrapper.findAll('[data-testid="accept-citation"]')[0].attributes('aria-pressed')).toBe(
      'false',
    );
    expect(notification.error).not.toHaveBeenCalled();
  });

  it('al confirmar repite el pedido con la confirmación y queda aceptada', async () => {
    evidenceService.sendCitationFeedback.mockRejectedValueOnce(overrideError);
    const wrapper = await searchCitations();
    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    (document.body.querySelector('[data-testid="override-confirm"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(evidenceService.sendCitationFeedback).toHaveBeenCalledTimes(2);
    expect(evidenceService.sendCitationFeedback.mock.calls[1][2]).toBe(true);
    expect(evidenceService.sendCitationFeedback.mock.calls[1][1][0].decision).toBe('ACCEPTED');
    expect(wrapper.findAll('[data-testid="accept-citation"]')[0].attributes('aria-pressed')).toBe(
      'true',
    );
    expect((verdictRow(wrapper, 0).find('select').element as HTMLSelectElement).value).toBe(
      'VALIDATED',
    );
  });

  it('al cancelar no se repite el pedido ni cambia nada', async () => {
    evidenceService.sendCitationFeedback.mockRejectedValueOnce(overrideError);
    const wrapper = await searchCitations();
    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    (document.body.querySelector('[data-testid="override-cancel"]') as HTMLButtonElement).click();
    await flushPromises();

    expect(evidenceService.sendCitationFeedback).toHaveBeenCalledTimes(1);
    expect(wrapper.findAll('[data-testid="accept-citation"]')[0].attributes('aria-pressed')).toBe(
      'false',
    );
  });

  it('un 409 que no es de dictamen se avisa como cualquier error', async () => {
    evidenceService.sendCitationFeedback.mockRejectedValueOnce({
      response: {
        status: 409,
        data: { code: 'CONFLICT', message: 'La evaluación no está en auditoría' },
      },
    });
    const wrapper = await searchCitations();

    await wrapper.findAll('[data-testid="accept-citation"]')[0].trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('La evaluación no está en auditoría');
    expect(document.body.querySelector('[data-testid="override-message"]')).toBeNull();
  });

  // ---- C4b: «Registrar como observación» deja feedback de la diferencia ----

  const openObservation = async (wrapper: Awaited<ReturnType<typeof searchCitations>>) => {
    await wrapper.find('[data-testid="register-observation"]').trigger('click');
    await flushPromises();
  };
  const clickCreate = async () => {
    await Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Crear')!
      .click();
    await flushPromises();
  };

  it('al guardar envía la cita con el texto que la app precargó', async () => {
    auditService.createObservation.mockResolvedValue({ data: {} });
    const wrapper = await searchCitations();

    await openObservation(wrapper);
    const prefilled = document.body.querySelector<HTMLTextAreaElement>('textarea')!.value;
    await clickCreate();

    const sent = auditService.createObservation.mock.calls[0][0];
    expect(sent.citation).toEqual({
      fileName: 'politica-1.pdf',
      location: 'Página 1',
      snippet: 'fragmento 1',
      score: 0.41,
      prefillText: prefilled,
    });
    expect(sent.description).toBe(prefilled);
  });

  it('si la cita está guardada, la observación lleva su id para enlazar la traza (C8)', async () => {
    evidenceService.getCitations.mockResolvedValue({
      data: [{ ...citation(1), citationId: 'cit-9' }, citation(2)],
    });
    auditService.createObservation.mockResolvedValue({ data: {} });
    const wrapper = await searchCitations();

    await openObservation(wrapper);
    await clickCreate();

    expect(auditService.createObservation.mock.calls[0][0].citation.citationId).toBe('cit-9');
  });

  it('si el auditor reescribe el texto, la cita lleva el precargado original', async () => {
    auditService.createObservation.mockResolvedValue({ data: {} });
    const wrapper = await searchCitations();

    await openObservation(wrapper);
    const textarea = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    const prefilled = textarea.value;
    textarea.value = 'Texto reescrito por el auditor';
    textarea.dispatchEvent(new Event('input'));
    await clickCreate();

    const sent = auditService.createObservation.mock.calls[0][0];
    expect(sent.description).toBe('Texto reescrito por el auditor');
    expect(sent.citation.prefillText).toBe(prefilled);
  });

  it('si cierra el formulario sin guardar no se envía nada', async () => {
    const wrapper = await searchCitations();

    await openObservation(wrapper);
    await Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.trim() === 'Cancelar')!
      .click();
    await flushPromises();

    expect(auditService.createObservation).not.toHaveBeenCalled();
  });

  it('una observación libre no lleva cita aunque antes se haya abierto una desde una cita', async () => {
    auditService.createObservation.mockResolvedValue({ data: {} });
    const wrapper = await searchCitations();
    await openObservation(wrapper);
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
    await clickCreate();

    expect(auditService.createObservation.mock.calls[0][0]).not.toHaveProperty('citation');
  });
});

describe('AuditView: citas guardadas, revertir y deshacer (C2 / hito 3)', () => {
  const saved = (n: number, status: string, extra: Record<string, unknown> = {}) => ({
    citationId: `cit-${n}`,
    fragmentId: `ev-1::${n}`,
    controlId: 'ctl-1',
    status,
    position: n,
    evidenceId: 'ev-1',
    fileName: `politica-${n}.pdf`,
    location: `Página ${n}`,
    snippet: `fragmento ${n}`,
    score: 0.5 + n / 10,
    ...extra,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    vi.spyOn(console, 'error').mockImplementation(() => {});
    auditService.getObservations.mockResolvedValue({ data: [] });
    auditService.getVerdicts.mockResolvedValue({ data: [] });
    auditService.undoCitationVerdict.mockResolvedValue({ data: undefined });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [{ id: 'ev-1', controlId: 'ctl-1', fileName: 'politica-1.pdf' }],
    });
    evidenceService.getSavedCitations.mockResolvedValue({ data: [] });
    evidenceService.getCitations.mockResolvedValue({ data: [saved(1, 'PENDING')] });
    evidenceService.refreshCitations.mockResolvedValue({ data: [saved(1, 'PENDING')] });
    evidenceService.sendCitationFeedback.mockResolvedValue({ data: { verdicts: [] } });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  const acceptButtons = (wrapper: Awaited<ReturnType<typeof mountView>>) =>
    wrapper.findAll('[data-testid="accept-citation"]');
  const rejectButtons = (wrapper: Awaited<ReturnType<typeof mountView>>) =>
    wrapper.findAll('[data-testid="reject-citation"]');
  const searchButton = (wrapper: Awaited<ReturnType<typeof mountView>>) =>
    wrapper.find('[data-testid="search-citations"]');

  it('al abrir en auditoría muestra las citas guardadas con su decisión, sin buscar', async () => {
    evidenceService.getSavedCitations.mockResolvedValue({
      data: [saved(2, 'REJECTED'), saved(1, 'ACCEPTED'), saved(3, 'PENDING')],
    });
    const wrapper = await mountView('IN_AUDIT');

    // Ordenadas por posición: 1 aceptada, 2 rechazada, 3 sin decidir.
    expect(acceptButtons(wrapper)).toHaveLength(3);
    expect(acceptButtons(wrapper).map((b) => b.attributes('aria-pressed'))).toEqual([
      'true',
      'false',
      'false',
    ]);
    expect(rejectButtons(wrapper).map((b) => b.attributes('aria-pressed'))).toEqual([
      'false',
      'true',
      'false',
    ]);
    expect(wrapper.text()).toContain('politica-1.pdf');
    expect(evidenceService.getCitations).not.toHaveBeenCalled();
    expect(evidenceService.refreshCitations).not.toHaveBeenCalled();
  });

  it('con citas guardadas el botón dice «Actualizar»; sin ellas, «Buscar citas»', async () => {
    const without = await mountView('IN_AUDIT');
    expect(searchButton(without).text()).toBe('Buscar citas');

    evidenceService.getSavedCitations.mockResolvedValue({ data: [saved(1, 'PENDING')] });
    const withSaved = await mountView('IN_AUDIT');
    expect(searchButton(withSaved).text()).toBe('Actualizar');
  });

  it('«Actualizar» busca de nuevo (refresh) y trae las decisiones que conserva el servidor', async () => {
    evidenceService.getSavedCitations.mockResolvedValue({ data: [saved(1, 'ACCEPTED')] });
    evidenceService.refreshCitations.mockResolvedValue({
      data: [saved(7, 'PENDING'), saved(1, 'ACCEPTED', { position: 1 })],
    });
    const wrapper = await mountView('IN_AUDIT');

    await searchButton(wrapper).trigger('click');
    await flushPromises();

    expect(evidenceService.refreshCitations).toHaveBeenCalledWith('eval-1', 'ctl-1');
    expect(evidenceService.getCitations).not.toHaveBeenCalled();
    expect(acceptButtons(wrapper).map((b) => b.attributes('aria-pressed'))).toEqual([
      'false',
      'true',
    ]);
  });

  it('fuera de auditoría no consulta las citas guardadas', async () => {
    await mountView('RETURNED');

    expect(evidenceService.getSavedCitations).not.toHaveBeenCalled();
  });

  it('si no se pueden leer las guardadas, la pantalla sigue funcionando', async () => {
    evidenceService.getSavedCitations.mockRejectedValue(new Error('boom'));
    const wrapper = await mountView('IN_AUDIT');

    expect(searchButton(wrapper).text()).toBe('Buscar citas');
    expect(notification.error).not.toHaveBeenCalled();
  });

  it('decidir una cita guardada envía su citationId', async () => {
    evidenceService.getSavedCitations.mockResolvedValue({ data: [saved(1, 'PENDING')] });
    const wrapper = await mountView('IN_AUDIT');

    await acceptButtons(wrapper)[0].trigger('click');
    await flushPromises();

    expect(evidenceService.sendCitationFeedback.mock.calls[0][1][0]).toMatchObject({
      citationId: 'cit-1',
      decision: 'ACCEPTED',
      controlId: 'ctl-1',
    });
    expect(acceptButtons(wrapper)[0].attributes('aria-pressed')).toBe('true');
  });

  it('la decisión de una cita guardada no se reenvía si ya está así', async () => {
    evidenceService.getSavedCitations.mockResolvedValue({ data: [saved(1, 'ACCEPTED')] });
    const wrapper = await mountView('IN_AUDIT');

    await acceptButtons(wrapper)[0].trigger('click');
    await flushPromises();

    expect(evidenceService.sendCitationFeedback).not.toHaveBeenCalled();
  });

  it('rechazar la única cita de respaldo revierte el dictamen «por cita» en pantalla', async () => {
    auditService.getVerdicts.mockResolvedValue({
      data: [
        {
          controlId: 'ctl-1',
          verdict: 'VALIDATED',
          source: 'CITATION',
          sourceRef: { fileName: 'politica-1.pdf', location: 'Página 1' },
        },
      ],
    });
    evidenceService.getSavedCitations.mockResolvedValue({ data: [saved(1, 'ACCEPTED')] });
    evidenceService.sendCitationFeedback.mockResolvedValue({
      data: { verdicts: [{ controlId: 'ctl-1', verdict: null, source: null, changed: true }] },
    });
    const wrapper = await mountView('IN_AUDIT');
    expect(wrapper.find('[data-testid="verdict-source"]').exists()).toBe(true);

    await rejectButtons(wrapper)[0].trigger('click');
    await flushPromises();

    const row = wrapper.findAll('[data-testid="verdict-row"]')[0];
    expect((row.find('select').element as HTMLSelectElement).value).toBe('');
    expect(wrapper.find('[data-testid="verdict-source"]').exists()).toBe(false);
    expect(rejectButtons(wrapper)[0].attributes('aria-pressed')).toBe('true');
  });

  // ---- «Deshacer» ----

  const withCitationVerdict = () => {
    auditService.getVerdicts.mockResolvedValue({
      data: [
        {
          controlId: 'ctl-1',
          verdict: 'VALIDATED',
          source: 'CITATION',
          sourceRef: { fileName: 'politica-1.pdf', location: 'Página 1' },
        },
      ],
    });
    evidenceService.getSavedCitations.mockResolvedValue({ data: [saved(1, 'ACCEPTED')] });
  };

  it('un dictamen por cita ofrece «Deshacer»', async () => {
    withCitationVerdict();
    const wrapper = await mountView('IN_AUDIT');

    expect(wrapper.find('[data-testid="undo-verdict"]').text()).toBe('Deshacer');
    expect(wrapper.find('[data-testid="verdict-source"]').text()).toBe(
      'Validado por cita (politica-1.pdf, Página 1)',
    );
  });

  it('«Deshacer» quita el dictamen y deja la cita sin decidir', async () => {
    withCitationVerdict();
    const wrapper = await mountView('IN_AUDIT');

    await wrapper.find('[data-testid="undo-verdict"]').trigger('click');
    await flushPromises();

    expect(auditService.undoCitationVerdict).toHaveBeenCalledWith('eval-1', 'ctl-1');
    const row = wrapper.findAll('[data-testid="verdict-row"]')[0];
    expect((row.find('select').element as HTMLSelectElement).value).toBe('');
    expect(wrapper.find('[data-testid="verdict-source"]').exists()).toBe(false);
    expect(acceptButtons(wrapper)[0].attributes('aria-pressed')).toBe('false');
    expect(rejectButtons(wrapper)[0].attributes('aria-pressed')).toBe('false');
  });

  it('si no se puede deshacer muestra el mensaje del servidor y no cambia nada', async () => {
    withCitationVerdict();
    auditService.undoCitationVerdict.mockRejectedValue({
      response: {
        data: { message: 'Solo se puede deshacer un dictamen que salió de aceptar una cita' },
      },
    });
    const wrapper = await mountView('IN_AUDIT');

    await wrapper.find('[data-testid="undo-verdict"]').trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'Solo se puede deshacer un dictamen que salió de aceptar una cita',
    );
    expect(wrapper.find('[data-testid="verdict-source"]').exists()).toBe(true);
  });

  it('un dictamen fijado a mano no ofrece «Deshacer»', async () => {
    auditService.getVerdicts.mockResolvedValue({
      data: [{ controlId: 'ctl-1', verdict: 'VALIDATED', source: 'MANUAL' }],
    });
    const wrapper = await mountView('IN_AUDIT');

    expect(wrapper.find('[data-testid="undo-verdict"]').exists()).toBe(false);
  });

  it('fuera de auditoría no se puede deshacer', async () => {
    withCitationVerdict();
    const wrapper = await mountView('RETURNED');

    expect(wrapper.find('[data-testid="undo-verdict"]').exists()).toBe(false);
  });
});

describe('AuditView: evidencia sin control asignado y sugerencias (F4, hito 4)', () => {
  const globalEvidence = (id: string, extra: Record<string, unknown> = {}) => ({
    id,
    controlId: null,
    fileName: `politica-${id}.pdf`,
    scanStatus: 'CLEAN',
    indexStatus: 'INDEXED',
    aiIndexed: true,
    ...extra,
  });
  const suggestion = (id: string, evidenceId: string, controlCode: string, extra = {}) => ({
    id,
    evidenceId,
    fileName: `politica-${evidenceId}.pdf`,
    controlId: `ctl-${controlCode}`,
    controlCode,
    controlDescription: `desc ${controlCode}`,
    score: 0.81,
    rank: 1,
    status: 'PENDING',
    ...extra,
  });

  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    vi.spyOn(console, 'error').mockImplementation(() => {});
    auditService.getObservations.mockResolvedValue({ data: [] });
    auditService.getVerdicts.mockResolvedValue({ data: [] });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({ data: [globalEvidence('g1')] });
    evidenceService.getSavedCitations.mockResolvedValue({ data: [] });
    evidenceService.getSuggestions.mockResolvedValue({ data: [] });
    evidenceService.decideSuggestion.mockResolvedValue({ data: {} });
    evidenceService.getCitations.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  const q = (wrapper: Awaited<ReturnType<typeof mountView>>, testid: string) =>
    wrapper.find(`[data-testid="${testid}"]`);

  it('lista la evidencia sin control (hoy no aparecía en ningún lado) y lo explica', async () => {
    const wrapper = await mountView('IN_AUDIT');

    expect(q(wrapper, 'global-evidence').exists()).toBe(true);
    expect(wrapper.findAll('[data-testid="global-evidence-item"]')).toHaveLength(1);
    expect(q(wrapper, 'global-evidence-item').text()).toContain('politica-g1.pdf');
    expect(q(wrapper, 'global-evidence-item').text()).toContain('indexada');
    expect(q(wrapper, 'global-evidence').text()).toContain('no asigna');
    // Con solo evidencia global ya no dice que no hay evidencia.
    expect(wrapper.text()).not.toContain('Aún no hay evidencia cargada para esta evaluación.');
  });

  it('sin evidencia global no muestra la sección', async () => {
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [{ id: 'ev-1', controlId: 'ctl-1', fileName: 'a.pdf' }],
    });
    const wrapper = await mountView('IN_AUDIT');

    expect(q(wrapper, 'global-evidence').exists()).toBe(false);
  });

  it('una evidencia en cuarentena o infectada no se ofrece como global', async () => {
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [
        globalEvidence('g1', { scanStatus: 'QUARANTINED' }),
        globalEvidence('g2', { scanStatus: 'INFECTED' }),
      ],
    });
    const wrapper = await mountView('IN_AUDIT');

    expect(q(wrapper, 'global-evidence').exists()).toBe(false);
  });

  it('muestra las sugerencias con su confianza: «podría pertenecer a CA.1-2 (confianza 0,81)»', async () => {
    evidenceService.getSuggestions.mockResolvedValue({
      data: [
        suggestion('s2', 'g1', 'CA.1-3', { rank: 2, score: 0.55 }),
        suggestion('s1', 'g1', 'CA.1-2'),
      ],
    });
    const wrapper = await mountView('IN_AUDIT');

    const items = wrapper.findAll('[data-testid="suggestion"]').map((n) => n.text());
    expect(items).toHaveLength(2);
    // Ordenadas por posición.
    expect(items[0]).toContain('Podría pertenecer a CA.1-2 (confianza 0,81)');
    expect(items[1]).toContain('CA.1-3 (confianza 0,55)');
  });

  it('aceptar una sugerencia la registra y suma el control a la lista de citas', async () => {
    evidenceService.getSuggestions.mockResolvedValue({
      data: [suggestion('s1', 'g1', 'CA.1-2')],
    });
    const wrapper = await mountView('IN_AUDIT');
    expect(wrapper.find('[data-testid="search-citations"]').exists()).toBe(false);

    await wrapper.find('[data-testid="accept-suggestion"]').trigger('click');
    await flushPromises();

    expect(evidenceService.decideSuggestion).toHaveBeenCalledWith('s1', 'ACCEPTED');
    expect(q(wrapper, 'suggestion-accepted').exists()).toBe(true);
    // El control aparece con su botón «Buscar citas» (las citas incluyen la evidencia global).
    expect(wrapper.text()).toContain('CA.1-2');
    expect(wrapper.find('[data-testid="search-citations"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="accept-suggestion"]').exists()).toBe(false);
  });

  it('rechazar una sugerencia la oculta', async () => {
    evidenceService.getSuggestions.mockResolvedValue({
      data: [suggestion('s1', 'g1', 'CA.1-2')],
    });
    const wrapper = await mountView('IN_AUDIT');

    await wrapper.find('[data-testid="reject-suggestion"]').trigger('click');
    await flushPromises();

    expect(evidenceService.decideSuggestion).toHaveBeenCalledWith('s1', 'REJECTED');
    expect(wrapper.findAll('[data-testid="suggestion"]')).toHaveLength(0);
    expect(wrapper.find('[data-testid="search-citations"]').exists()).toBe(false);
  });

  it('una sugerencia ya aceptada llega marcada y su control ya está en la lista', async () => {
    evidenceService.getSuggestions.mockResolvedValue({
      data: [suggestion('s1', 'g1', 'CA.1-2', { status: 'ACCEPTED' })],
    });
    const wrapper = await mountView('IN_AUDIT');

    expect(q(wrapper, 'suggestion-accepted').exists()).toBe(true);
    expect(wrapper.find('[data-testid="search-citations"]').exists()).toBe(true);
  });

  it('si no se puede registrar la decisión avisa y no cambia nada', async () => {
    evidenceService.getSuggestions.mockResolvedValue({ data: [suggestion('s1', 'g1', 'CA.1-2')] });
    evidenceService.decideSuggestion.mockRejectedValue({
      response: { data: { message: 'Las sugerencias solo están disponibles en auditoría' } },
    });
    const wrapper = await mountView('IN_AUDIT');

    await wrapper.find('[data-testid="accept-suggestion"]').trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'Las sugerencias solo están disponibles en auditoría',
    );
    expect(wrapper.find('[data-testid="accept-suggestion"]').exists()).toBe(true);
  });

  it('fuera de auditoría no consulta sugerencias ni ofrece decidirlas', async () => {
    const wrapper = await mountView('RETURNED');

    expect(evidenceService.getSuggestions).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="accept-suggestion"]').exists()).toBe(false);
  });

  it('si todavía no hay sugerencias (la IA las calcula) no es un error', async () => {
    evidenceService.getSuggestions.mockRejectedValue(new Error('boom'));
    const wrapper = await mountView('IN_AUDIT');

    expect(notification.error).not.toHaveBeenCalled();
    expect(q(wrapper, 'global-evidence').exists()).toBe(true);
  });

  it('las citas que salen de evidencia global se marcan «Sin control asignado»', async () => {
    evidenceService.getSuggestions.mockResolvedValue({
      data: [suggestion('s1', 'g1', 'CA.1-2', { status: 'ACCEPTED' })],
    });
    evidenceService.getCitations.mockResolvedValue({
      data: [
        {
          citationId: 'c1',
          status: 'PENDING',
          evidenceId: 'g1',
          fileName: 'politica-g1.pdf',
          location: 'Página 1',
          snippet: 'texto global',
          score: 0.7,
          unassigned: true,
        },
        {
          citationId: 'c2',
          status: 'PENDING',
          evidenceId: 'ev-1',
          fileName: 'del-control.pdf',
          location: 'Página 2',
          snippet: 'texto propio',
          score: 0.6,
          unassigned: false,
        },
      ],
    });
    const wrapper = await mountView('IN_AUDIT');

    await wrapper.find('[data-testid="search-citations"]').trigger('click');
    await flushPromises();

    const badges = wrapper.findAll('[data-testid="citation-unassigned"]');
    expect(badges).toHaveLength(1);
    expect(badges[0].text()).toBe('Sin control asignado');
  });
});
