import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const evaluationsService = vi.hoisted(() => ({
  getById: vi.fn(),
  getResponses: vi.fn(),
  getResults: vi.fn(),
  getDeletable: vi.fn(),
  getAuditTrail: vi.fn(),
  updateStatus: vi.fn(),
}));
const catalogService = vi.hoisted(() => ({ getByVersion: vi.fn() }));
const auditService = vi.hoisted(() => ({ getObservations: vi.fn() }));
const evidenceService = vi.hoisted(() => ({ getByEvaluation: vi.fn() }));
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }));
// null = cualquier rol (como antes); una lista restringe los roles del usuario.
const authState = vi.hoisted(() => ({ roles: null as string[] | null }));
const communityProfilesService = vi.hoisted(() => ({ getAll: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));

vi.mock('@/services/resources', () => ({
  evaluationsService,
  catalogService,
  communityProfilesService,
  auditService,
  evidenceService,
}));
vi.mock('@/composables/useUtils', async (orig) => ({
  ...(await orig<typeof import('@/composables/useUtils')>()),
  useNotification: () => notification,
}));
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    isAdmin: true,
    hasAnyRole: (...roles: string[]) =>
      authState.roles === null || roles.some((r) => authState.roles!.includes(r)),
    user: { id: 'me' },
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'eval-1' } }),
  useRouter: () => router,
}));

import EvaluationDetailView from '@/views/evaluations/EvaluationDetailView.vue';

const result = (fn: string, fnName: string, sub: string) => ({
  functionId: fn,
  functionName: fnName,
  categoryId: 'c',
  categoryName: 'C',
  subcategoryId: sub,
  subcategoryName: `Sub ${sub}`,
  currentLevel: 1,
  targetLevel: 2,
  gap: 1,
});

async function mountView() {
  const wrapper = mount(EvaluationDetailView, {
    attachTo: document.body,
    global: {
      stubs: { RouterLink: true, MaturityRadarChart: true, MaturitySummaryTables: true },
    },
  });
  await flushPromises();
  return wrapper;
}

// Los títulos de las secciones («Gobernar (2)») son los botones de cada grupo.
const groupTitles = (w: Awaited<ReturnType<typeof mountView>>) =>
  w
    .findAll('button')
    .map((b) => b.text())
    .filter((t) => /^(Gobernar|Identificar|Proteger) \(\d+\)$/.test(t));

async function chooseFunction(label: string) {
  const root = document.body.querySelector('[data-testid="function-filter"]')!;
  const input = root.querySelector<HTMLInputElement>('input')!;
  input.click();
  await flushPromises();
  const option = Array.from(document.body.querySelectorAll<HTMLElement>('[role="option"]')).find(
    (o) => o.textContent?.trim() === label,
  )!;
  for (const type of ['mousedown', 'mouseup', 'click']) {
    option.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true }));
  }
  await flushPromises();
}

describe('EvaluationDetailView: filtro por función (RF-VIZ-05)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    evaluationsService.getById.mockResolvedValue({
      data: {
        id: 'eval-1',
        name: 'E',
        status: 'IN_PROGRESS',
        catalogVersion: '5.0',
        communityProfileId: null,
        organizationName: 'Org',
      },
    });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    evaluationsService.getDeletable.mockResolvedValue({ data: [] });
    evaluationsService.getResults.mockResolvedValue({
      data: [
        result('gv', 'Gobernar', 's1'),
        result('gv', 'Gobernar', 's2'),
        result('id', 'Identificar', 's3'),
        result('pr', 'Proteger', 's4'),
      ],
    });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
    communityProfilesService.getAll.mockResolvedValue({ data: [] });
  });

  it('sin filtro muestra todas las funciones', async () => {
    const wrapper = await mountView();

    expect(groupTitles(wrapper)).toEqual(['Gobernar (2)', 'Identificar (1)', 'Proteger (1)']);
  });

  it('al elegir una función deja visibles solo sus subcategorías, al instante y expandida', async () => {
    const wrapper = await mountView();

    await chooseFunction('Gobernar');

    expect(groupTitles(wrapper)).toEqual(['Gobernar (2)']);
    expect(wrapper.text()).toContain('Sub s1');
    expect(wrapper.text()).toContain('Sub s2');
    expect(wrapper.text()).not.toContain('Sub s3');
    // No se volvió a pedir nada al backend: el filtro es del lado del cliente.
    expect(evaluationsService.getResults).toHaveBeenCalledTimes(1);
  });

  it('volver a «Todas las funciones» restaura la lista completa', async () => {
    const wrapper = await mountView();
    await chooseFunction('Identificar');
    expect(groupTitles(wrapper)).toEqual(['Identificar (1)']);

    await chooseFunction('Todas las funciones');

    expect(groupTitles(wrapper)).toEqual(['Gobernar (2)', 'Identificar (1)', 'Proteger (1)']);
  });

  it('no ofrece filtro por prioridad', async () => {
    const wrapper = await mountView();

    expect(wrapper.text().toLowerCase()).not.toContain('prioridad');
  });
});

describe('EvaluationDetailView: observaciones del auditor para la organización (PRI-354)', () => {
  const OBS = '[data-testid="auditor-observations"]';

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['ORG_RESPONSIBLE'];
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    evaluationsService.getDeletable.mockResolvedValue({ data: [] });
    evaluationsService.getResults.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
    communityProfilesService.getAll.mockResolvedValue({ data: [] });
    auditService.getObservations.mockResolvedValue({
      data: [
        {
          id: 'o1',
          evaluationId: 'eval-1',
          type: 'NON_CONFORMITY',
          description: 'Falta la política de respaldo',
          status: 'OPEN',
          createdBy: 'u',
          createdAt: '2026-11-03T10:00:00Z',
          createdByName: 'Ana Auditora',
          controlCode: 'PR.1-2',
        },
      ],
    });
  });

  async function mountWithStatus(status: string) {
    evaluationsService.getById.mockResolvedValue({
      data: {
        id: 'eval-1',
        name: 'E',
        status,
        catalogVersion: '5.0',
        communityProfileId: null,
        organizationName: 'Org',
      },
    });
    return mountView();
  }

  afterEach(() => {
    authState.roles = null;
  });

  it('el responsable de la organización las ve cuando la evaluación vuelve', async () => {
    const wrapper = await mountWithStatus('RETURNED');

    expect(wrapper.find(OBS).exists()).toBe(true);
    expect(auditService.getObservations).toHaveBeenCalledWith('eval-1');
    expect(wrapper.find(OBS).text()).toContain('Falta la política de respaldo');
    expect(wrapper.find(OBS).text()).toContain('Control PR.1-2');
  });

  it('el evaluador interno también las ve', async () => {
    authState.roles = ['INTERNAL_EVALUATOR'];
    const wrapper = await mountWithStatus('RETURNED');

    expect(wrapper.find(OBS).exists()).toBe(true);
  });

  it('siguen visibles cuando la evaluación está aprobada o archivada', async () => {
    for (const status of ['APPROVED', 'ARCHIVED']) {
      const wrapper = await mountWithStatus(status);
      expect(wrapper.find(OBS).exists(), status).toBe(true);
    }
  });

  // PRI-298: En curso y Lista para auditoría pasaron a mostrarlas (si las hay); ver el bloque de
  // PRI-298 más abajo. En Borrador no hay auditoría y En auditoría son el borrador del auditor.
  it('no se muestran en borrador ni mientras el auditor trabaja (ni siquiera se piden)', async () => {
    for (const status of ['DRAFT', 'IN_AUDIT']) {
      const wrapper = await mountWithStatus(status);
      expect(wrapper.find(OBS).exists(), status).toBe(false);
    }
    expect(auditService.getObservations).not.toHaveBeenCalled();
  });

  it('el VIEWER no las ve aunque la evaluación haya vuelto', async () => {
    authState.roles = ['VIEWER'];
    const wrapper = await mountWithStatus('RETURNED');

    expect(wrapper.find(OBS).exists()).toBe(false);
    expect(auditService.getObservations).not.toHaveBeenCalled();
  });

  it('el administrador y el auditor tienen su propia pantalla y no ven este bloque', async () => {
    for (const role of ['PRISMA_ADMIN', 'AUDITOR']) {
      authState.roles = [role];
      const wrapper = await mountWithStatus('RETURNED');
      expect(wrapper.find(OBS).exists(), role).toBe(false);
    }
  });

  it('el bloque no tiene botones de edición', async () => {
    const wrapper = await mountWithStatus('RETURNED');

    const block = wrapper.find(OBS);
    expect(block.findAll('button')).toHaveLength(0);
    expect(block.findAll('textarea, input, select')).toHaveLength(0);
  });
});

describe('EvaluationDetailView: reapertura por observaciones (PRI-298, RF-AUD-08)', () => {
  const OBS = '[data-testid="auditor-observations"]';
  const NOTICE = '[data-testid="reopened-notice"]';

  const observation = (id: string, status: string, type = 'NON_CONFORMITY') => ({
    id,
    evaluationId: 'eval-1',
    type,
    description: `Observación ${id}`,
    status,
    createdBy: 'u',
    createdAt: '2026-10-21T10:00:00Z',
    createdByName: 'Ana Auditora',
  });

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['ORG_RESPONSIBLE'];
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    evaluationsService.getDeletable.mockResolvedValue({ data: [] });
    evaluationsService.getResults.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
    communityProfilesService.getAll.mockResolvedValue({ data: [] });
    auditService.getObservations.mockResolvedValue({
      data: [
        observation('o1', 'OPEN'),
        observation('o2', 'IN_PROGRESS', 'OBSERVATION'),
        observation('o3', 'RESOLVED', 'RECOMMENDATION'),
      ],
    });
    evaluationsService.getAuditTrail.mockResolvedValue({
      data: [
        {
          id: 'e1',
          action: 'UPDATE_STATUS',
          status: 'IN_AUDIT',
          occurredAt: '2026-10-15T10:00:00Z',
          cycleNumber: 1,
        },
        {
          id: 'e2',
          action: 'UPDATE_STATUS',
          status: 'RETURNED',
          occurredAt: '2026-10-21T12:00:00Z',
          cycleNumber: 1,
        },
        {
          id: 'e3',
          action: 'UPDATE_STATUS',
          status: 'IN_PROGRESS',
          occurredAt: '2026-10-22T09:00:00Z',
          cycleNumber: 1,
        },
      ],
    });
  });

  afterEach(() => {
    authState.roles = null;
  });

  async function mountWithStatus(status: string) {
    evaluationsService.getById.mockResolvedValue({
      data: {
        id: 'eval-1',
        name: 'E',
        status,
        catalogVersion: '5.0',
        communityProfileId: null,
        organizationName: 'Org',
      },
    });
    return mountView();
  }

  it('después de «Reanudar» (En curso) la organización sigue viendo las observaciones', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(wrapper.find(OBS).exists()).toBe(true);
    expect(wrapper.find(OBS).text()).toContain('Observación o1');
    expect(wrapper.findAll('[data-testid="auditor-observation"]')).toHaveLength(3);
  });

  it('muestra el aviso de reapertura con la fecha de devolución y el conteo', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');

    const notice = wrapper.find(NOTICE);
    expect(notice.exists()).toBe(true);
    expect(notice.text()).toContain('Evaluación reabierta para corregir lo que observó el auditor');
    expect(notice.text()).toContain('21/10/2026');
    expect(notice.text()).toContain('3 observaciones');
    expect(notice.text()).toContain('2 abiertas');
    expect(notice.text()).toContain('enviarla a auditoría');
  });

  it('«Ver observaciones» lleva al bloque de observaciones', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');
    const block = document.getElementById('auditor-observations')!;
    const scroll = vi.fn();
    block.scrollIntoView = scroll;

    await wrapper.find('[data-testid="reopened-notice-go"]').trigger('click');

    expect(scroll).toHaveBeenCalledTimes(1);
  });

  it('al reenviarla (Lista para auditoría) siguen visibles, sin el aviso', async () => {
    const wrapper = await mountWithStatus('READY_FOR_AUDIT');

    expect(wrapper.find(OBS).exists()).toBe(true);
    expect(wrapper.find(NOTICE).exists()).toBe(false);
  });

  it('en el primer ciclo (sin observaciones) no hay aviso ni bloque vacío', async () => {
    auditService.getObservations.mockResolvedValue({ data: [] });
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(wrapper.find(NOTICE).exists()).toBe(false);
    expect(wrapper.find(OBS).exists()).toBe(false);
    expect(document.body.textContent).not.toContain('El auditor no registró observaciones');
  });

  it('si no se puede leer el historial, el aviso sale igual sin la fecha', async () => {
    evaluationsService.getAuditTrail.mockRejectedValue(new Error('offline'));
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(wrapper.find(NOTICE).exists()).toBe(true);
    expect(wrapper.find(NOTICE).text()).toContain('El auditor la devolvió con');
  });

  it('una sola observación se nombra en singular', async () => {
    auditService.getObservations.mockResolvedValue({ data: [observation('o1', 'OPEN')] });
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(wrapper.find(NOTICE).text()).toContain('1 observación');
    expect(wrapper.find(NOTICE).text()).toContain('1 abierta');
  });

  it('el aviso es para la organización: el VIEWER no lo ve', async () => {
    authState.roles = ['VIEWER'];
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(wrapper.find(NOTICE).exists()).toBe(false);
    expect(auditService.getObservations).not.toHaveBeenCalled();
  });

  it('en Devuelta el bloque sigue igual y sin el aviso', async () => {
    const wrapper = await mountWithStatus('RETURNED');

    expect(wrapper.find(OBS).exists()).toBe(true);
    expect(wrapper.find(NOTICE).exists()).toBe(false);
  });
});

describe('EvaluationDetailView: panel «Qué sigue» dentro de la zona 3', () => {
  const PANEL = '[data-testid="action-panel"]';

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['PRISMA_ADMIN'];
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    evaluationsService.getDeletable.mockResolvedValue({ data: [] });
    evaluationsService.getResults.mockResolvedValue({ data: [] });
    evaluationsService.getAuditTrail.mockResolvedValue({ data: [] });
    evaluationsService.updateStatus.mockResolvedValue({ data: {} });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
    communityProfilesService.getAll.mockResolvedValue({ data: [] });
    auditService.getObservations.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({ data: [{ id: 'e1' }, { id: 'e2' }] });
  });

  afterEach(() => {
    authState.roles = null;
  });

  async function mountWithStatus(status: string) {
    evaluationsService.getById.mockResolvedValue({
      data: {
        id: 'eval-1',
        name: 'E',
        status,
        catalogVersion: '5.0',
        communityProfileId: null,
        organizationName: 'Org',
      },
    });
    return mountView();
  }

  const testIds = (w: Awaited<ReturnType<typeof mountView>>, prefix: string) =>
    w
      .findAll(`button[data-testid^="${prefix}"]`)
      .map((b) => b.attributes('data-testid')!.slice(prefix.length));

  it('ya no existe la tarjeta «Acciones de Flujo»: el panel vive en la tarjeta del stepper', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(wrapper.text()).not.toContain('Acciones de Flujo');
    expect(wrapper.find(PANEL).exists()).toBe(true);
    expect(wrapper.text()).toContain('Ciclo de vida de la evaluación');
    expect(wrapper.find('[data-testid="action-panel-eyebrow"]').text()).toBe(
      'Tu turno · Administrador',
    );
  });

  it('el administrador ve responder y enviar a auditoría en En curso', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(testIds(wrapper, 'action-')).toEqual(['respond', 'submit']);
    expect(wrapper.find('[data-testid="action-submit"]').text()).toContain('Enviar a auditoría');
  });

  it('«Responder controles» navega a la pantalla de respuestas', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');

    await wrapper.find('[data-testid="action-respond"]').trigger('click');

    expect(router.push).toHaveBeenCalledWith('/evaluations/eval-1/respond');
  });

  it('un botón de estado cambia el estado de la evaluación', async () => {
    const wrapper = await mountWithStatus('DRAFT');
    evaluationsService.updateStatus.mockResolvedValue({
      data: { id: 'eval-1', name: 'E', status: 'IN_PROGRESS', organizationName: 'Org' },
    });

    await wrapper.find('[data-testid="action-start"]').trigger('click');
    await flushPromises();

    expect(evaluationsService.updateStatus).toHaveBeenCalledWith('eval-1', 'IN_PROGRESS');
  });

  it('Archivar pide confirmación antes de cambiar el estado', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');

    await wrapper.find('[data-testid="secondary-archive"]').trigger('click');
    await flushPromises();

    expect(evaluationsService.updateStatus).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Archivar evaluación');
  });

  it('Evidencias está a la vista con su contador y lleva a la pantalla de evidencias', async () => {
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(evidenceService.getByEvaluation).toHaveBeenCalledWith('eval-1');
    expect(wrapper.find('[data-testid="tool-evidence-badge"]').text()).toBe('2');

    await wrapper.find('[data-testid="tool-evidence"]').trigger('click');

    expect(router.push).toHaveBeenCalledWith('/evidence/eval-1');
  });

  it('si no se pueden leer las evidencias, la tarjeta se muestra igual, sin número', async () => {
    evidenceService.getByEvaluation.mockRejectedValue(new Error('offline'));
    const wrapper = await mountWithStatus('IN_PROGRESS');

    expect(wrapper.find('[data-testid="tool-evidence"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="tool-evidence-badge"]').exists()).toBe(false);
  });

  it('en Devuelta, la organización ve las observaciones primero y con su contador', async () => {
    authState.roles = ['ORG_RESPONSIBLE'];
    auditService.getObservations.mockResolvedValue({
      data: [
        {
          id: 'o1',
          status: 'OPEN',
          type: 'NON_CONFORMITY',
          description: 'x',
          createdAt: '2026-11-03',
        },
        {
          id: 'o2',
          status: 'RESOLVED',
          type: 'OBSERVATION',
          description: 'y',
          createdAt: '2026-11-03',
        },
      ],
    });
    const wrapper = await mountWithStatus('RETURNED');

    expect(testIds(wrapper, 'tool-')).toEqual(['observations', 'evidence']);
    expect(wrapper.find('[data-testid="tool-observations-badge"]').text()).toBe('1 abiertas');
    expect(wrapper.find('[data-testid="action-resume"]').text()).toContain('Reanudar y corregir');
  });

  it('el administrador no pide las observaciones (tiene su pantalla de Auditoría)', async () => {
    await mountWithStatus('RETURNED');

    expect(auditService.getObservations).not.toHaveBeenCalled();
  });

  it('el visualizador ve solo lectura: sin botones de estado y con cartel de espera', async () => {
    authState.roles = ['VIEWER'];
    const wrapper = await mountWithStatus('READY_FOR_AUDIT');

    expect(testIds(wrapper, 'action-')).toEqual([]);
    expect(wrapper.find('[data-testid="action-waiting"]').text()).toContain('Esperando auditor');
    expect(wrapper.find('[data-testid="tool-observations"]').exists()).toBe(false);
  });

  it('el auditor ve aprobar, devolver e ir a revisar en En auditoría', async () => {
    authState.roles = ['AUDITOR'];
    const wrapper = await mountWithStatus('IN_AUDIT');

    expect(testIds(wrapper, 'action-')).toEqual(['review', 'approve', 'return']);
    await wrapper.find('[data-testid="action-review"]').trigger('click');
    expect(router.push).toHaveBeenCalledWith('/audit/eval-1');
  });

  it('en Aprobada el plan de mejora es el botón principal y los reportes son del responsable', async () => {
    authState.roles = ['ORG_RESPONSIBLE'];
    const responsible = await mountWithStatus('APPROVED');
    expect(testIds(responsible, 'action-')).toEqual(['improvement', 'reports']);

    authState.roles = ['INTERNAL_EVALUATOR'];
    const evaluator = await mountWithStatus('APPROVED');
    expect(testIds(evaluator, 'action-')).toEqual(['improvement']);
  });
});

describe('EvaluationDetailView: aviso al enviar a auditoría con controles sin responder', () => {
  const control = (id: string) => ({ id, code: id, description: id, targetLevel: 1 });
  const tree = (ids: string[]) => ({
    data: {
      functions: [
        {
          id: 'f1',
          name: 'F',
          categories: [
            {
              id: 'c1',
              name: 'C',
              subcategories: [
                { id: 's1', name: 'S', requirements: [{ id: 'r1', controls: ids.map(control) }] },
              ],
            },
          ],
        },
      ],
    },
  });
  const answer = (controlId: string) => ({ id: `r-${controlId}`, controlId, compliant: true });

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['PRISMA_ADMIN'];
    evaluationsService.getResults.mockResolvedValue({ data: [] });
    evaluationsService.getDeletable.mockResolvedValue({ data: [] });
    evaluationsService.getAuditTrail.mockResolvedValue({ data: [] });
    evaluationsService.updateStatus.mockResolvedValue({
      data: { id: 'eval-1', name: 'E', status: 'READY_FOR_AUDIT', organizationName: 'Org' },
    });
    communityProfilesService.getAll.mockResolvedValue({ data: [] });
    auditService.getObservations.mockResolvedValue({ data: [] });
    evidenceService.getByEvaluation.mockResolvedValue({ data: [] });
  });

  afterEach(() => {
    authState.roles = null;
  });

  async function mountReady(status: string, controls: string[], answered: string[]) {
    catalogService.getByVersion.mockResolvedValue(tree(controls));
    evaluationsService.getResponses.mockResolvedValue({ data: answered.map(answer) });
    evaluationsService.getById.mockResolvedValue({
      data: {
        id: 'eval-1',
        name: 'E',
        status,
        catalogVersion: '5.0',
        communityProfileId: null,
        organizationName: 'Org',
      },
    });
    return mountView();
  }

  const dialogText = () => document.body.textContent ?? '';
  const dialogButton = (label: string) =>
    Array.from(document.body.querySelectorAll('button')).find((b) =>
      b.textContent?.includes(label),
    ) as HTMLButtonElement;

  it('avisa cuántos controles faltan y que cada uno cuenta como «no cumple»', async () => {
    const wrapper = await mountReady('IN_PROGRESS', ['a', 'b', 'c', 'd'], ['a']);

    await wrapper.find('[data-testid="action-submit"]').trigger('click');
    await flushPromises();

    expect(evaluationsService.updateStatus).not.toHaveBeenCalled();
    expect(dialogText()).toContain('Hay controles sin responder');
    expect(dialogText()).toContain('3 de los 4 controles no tienen respuesta');
    expect(dialogText()).toContain('se cuentan como «no cumple»');
    expect(dialogText()).toContain(
      '¿Querés enviar la evaluación a auditoría en estas condiciones?',
    );
  });

  it('con un solo control pendiente lo dice en singular', async () => {
    const wrapper = await mountReady('IN_PROGRESS', ['a', 'b'], ['a']);

    await wrapper.find('[data-testid="action-submit"]').trigger('click');
    await flushPromises();

    expect(dialogText()).toContain('1 de los 2 controles no tiene respuesta');
  });

  it('si confirma, la evaluación se envía a auditoría', async () => {
    const wrapper = await mountReady('IN_PROGRESS', ['a', 'b'], ['a']);
    await wrapper.find('[data-testid="action-submit"]').trigger('click');
    await flushPromises();

    dialogButton('Enviar a auditoría igual').click();
    await flushPromises();

    expect(evaluationsService.updateStatus).toHaveBeenCalledWith('eval-1', 'READY_FOR_AUDIT');
  });

  it('si cancela, no se envía nada', async () => {
    const wrapper = await mountReady('IN_PROGRESS', ['a', 'b'], ['a']);
    await wrapper.find('[data-testid="action-submit"]').trigger('click');
    await flushPromises();

    dialogButton('Cancelar').click();
    await flushPromises();

    expect(evaluationsService.updateStatus).not.toHaveBeenCalled();
  });

  it('con todos los controles respondidos se envía directo, sin aviso', async () => {
    const wrapper = await mountReady('IN_PROGRESS', ['a', 'b'], ['a', 'b']);

    await wrapper.find('[data-testid="action-submit"]').trigger('click');
    await flushPromises();

    expect(dialogText()).not.toContain('Hay controles sin responder');
    expect(evaluationsService.updateStatus).toHaveBeenCalledWith('eval-1', 'READY_FOR_AUDIT');
  });

  it('las respuestas a controles de otro perfil no cuentan como respondidos', async () => {
    const wrapper = await mountReady('IN_PROGRESS', ['a', 'b'], ['a', 'zzz']);

    await wrapper.find('[data-testid="action-submit"]').trigger('click');
    await flushPromises();

    expect(dialogText()).toContain('1 de los 2 controles no tiene respuesta');
  });

  it('el aviso es solo para el envío a auditoría: iniciar o reanudar no lo muestran', async () => {
    const wrapper = await mountReady('DRAFT', ['a', 'b'], []);

    await wrapper.find('[data-testid="action-start"]').trigger('click');
    await flushPromises();

    expect(dialogText()).not.toContain('Hay controles sin responder');
    expect(evaluationsService.updateStatus).toHaveBeenCalledWith('eval-1', 'IN_PROGRESS');
  });
});
