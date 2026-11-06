import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const evaluationsService = vi.hoisted(() => ({
  getById: vi.fn(),
  getResponses: vi.fn(),
  getResults: vi.fn(),
  getDeletable: vi.fn(),
}));
const catalogService = vi.hoisted(() => ({ getByVersion: vi.fn() }));
const communityProfilesService = vi.hoisted(() => ({ getAll: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));

vi.mock('@/services/resources', () => ({
  evaluationsService,
  catalogService,
  communityProfilesService,
}));
vi.mock('@/composables/useUtils', async (orig) => ({
  ...(await orig<typeof import('@/composables/useUtils')>()),
  useNotification: () => notification,
}));
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAdmin: true, hasAnyRole: () => true, user: { id: 'me' } }),
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
