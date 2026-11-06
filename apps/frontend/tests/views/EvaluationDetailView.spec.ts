import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const evaluationsService = vi.hoisted(() => ({
  getById: vi.fn(),
  getResponses: vi.fn(),
  getResults: vi.fn(),
  getDeletable: vi.fn(),
}));
const catalogService = vi.hoisted(() => ({ getByVersion: vi.fn() }));
const auditService = vi.hoisted(() => ({ getObservations: vi.fn() }));
// null = cualquier rol (como antes); una lista restringe los roles del usuario.
const authState = vi.hoisted(() => ({ roles: null as string[] | null }));
const communityProfilesService = vi.hoisted(() => ({ getAll: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));

vi.mock('@/services/resources', () => ({
  evaluationsService,
  catalogService,
  communityProfilesService,
  auditService,
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
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
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

  it('no se muestran mientras la evaluación no volvió (ni siquiera se piden)', async () => {
    for (const status of ['DRAFT', 'IN_PROGRESS', 'READY_FOR_AUDIT', 'IN_AUDIT']) {
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
