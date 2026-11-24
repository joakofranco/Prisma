import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

// RF-VIZ-06 (PRI-320): pantalla «Comparar evaluaciones».
const comparisonService = vi.hoisted(() => ({
  candidates: vi.fn(),
  compare: vi.fn(),
  downloadPdf: vi.fn(),
}));
const organizationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
const auth = vi.hoisted(() => ({ isAdmin: false, isAuditor: false }));
const route = vi.hoisted(() => ({ query: {} as Record<string, string> }));
const replace = vi.hoisted(() => vi.fn());
const saveBlob = vi.hoisted(() => vi.fn());

vi.mock('@/services/resources', () => ({ comparisonService, organizationsService }));
vi.mock('@/composables/useUtils', async (orig) => ({
  ...(await orig<typeof import('@/composables/useUtils')>()),
  useNotification: () => notification,
}));
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    get isAdmin() {
      return auth.isAdmin;
    },
    get isAuditor() {
      return auth.isAuditor;
    },
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace, push: vi.fn() }),
}));
vi.mock('@/utils/files', async (orig) => ({
  ...(await orig<typeof import('@/utils/files')>()),
  saveBlob,
}));

import EvaluationCompareView from '@/views/evaluations/EvaluationCompareView.vue';

const CANDIDATES = [
  {
    id: 'e-2026',
    name: 'Anual 2026',
    profileName: 'Estándar',
    approvedAt: '2026-10-28T10:00:00Z',
    globalMaturity: 1,
  },
  {
    id: 'e-2025',
    name: 'Anual 2025',
    profileName: 'Estándar',
    approvedAt: '2025-06-02T10:00:00Z',
    globalMaturity: 0,
  },
];

const evaluation = (id: string, maturity: number, compliant: number) => ({
  id,
  name: id,
  profileName: 'Estándar',
  approvedAt: null,
  globalMaturity: 1,
  maturityAverage: maturity,
  compliantControls: compliant,
  totalControls: 234,
  compliancePercent: 50,
});

const sub = (
  code: string,
  functionId: string,
  functionName: string,
  a: number | null,
  b: number | null,
) => ({
  subcategoryId: code,
  code,
  name: `Nombre ${code}`,
  functionId,
  functionName,
  categoryName: 'Cat',
  levelA: a,
  levelB: b,
  delta: a !== null && b !== null ? b - a : null,
  targetLevel: 3,
});

const COMPARISON = {
  organizationId: 'org-1',
  organizationName: 'Intendencia de Rivera',
  evaluationA: evaluation('e-2025', 0.95, 82),
  evaluationB: evaluation('e-2026', 1.48, 142),
  sameProfile: true,
  subcategoriesUp: 1,
  subcategoriesDown: 1,
  subcategoriesSame: 1,
  onlyInA: 0,
  onlyInB: 1,
  reading: 'Entre ambas evaluaciones la madurez global subió 0,53 (de 0,95 a 1,48).',
  functions: [
    { functionId: 'gv', functionName: 'Gobernar', levelA: 1.5, levelB: 2, delta: 0.5 },
    { functionId: 'rs', functionName: 'Responder', levelA: 2, levelB: 1, delta: -1 },
  ],
  subcategories: [
    sub('GV.OC-01', 'gv', 'Gobernar', 1, 3),
    sub('GV.PO-01', 'gv', 'Gobernar', null, 1),
    sub('GV.RM-02', 'gv', 'Gobernar', 2, 2),
    sub('RS.MA-01', 'rs', 'Responder', 2, 1),
  ],
};

function mountView() {
  return mount(EvaluationCompareView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  });
}

const text = (w: ReturnType<typeof mountView>, id: string) =>
  w.find(`[data-testid="${id}"]`).text();

describe('EvaluationCompareView (PRI-320)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    auth.isAdmin = false;
    auth.isAuditor = false;
    route.query = {};
    comparisonService.candidates.mockResolvedValue({ data: CANDIDATES });
    comparisonService.compare.mockResolvedValue({ data: COMPARISON });
    organizationsService.getAll.mockResolvedValue({
      data: { data: [{ id: 'org-1', name: 'Intendencia de Rivera', enabled: true }], total: 1 },
    });
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('el responsable compara por defecto la anterior (A) con la más reciente (B)', async () => {
    const w = mountView();
    await flushPromises();

    expect(comparisonService.candidates).toHaveBeenCalledWith(undefined);
    expect(comparisonService.compare).toHaveBeenCalledWith('e-2025', 'e-2026');
    expect(w.find('[data-testid="compare-organization"]').exists()).toBe(false);
    expect(replace).toHaveBeenCalledWith({ query: { a: 'e-2025', b: 'e-2026' } });
    const summary = text(w, 'compare-summary');
    expect(summary).toContain('0,95 → 1,48');
    expect(summary).toContain('▲ +0,53');
    expect(summary).toContain('82 → 142');
    expect(summary).toContain('▲ +60');
    expect(text(w, 'compare-reading')).toContain('subió 0,53');
  });

  it('muestra el cambio por función y por subcategoría, con «solo cambios» por defecto', async () => {
    const w = mountView();
    await flushPromises();

    const functions = text(w, 'compare-functions');
    expect(functions).toContain('Gobernar');
    expect(functions).toContain('▲ +0,50');
    expect(functions).toContain('▼ −1,00');
    const table = text(w, 'compare-subcategories');
    expect(table).toContain('GV.OC-01');
    expect(table).toContain('▲ +2');
    expect(table).toContain('solo en B');
    expect(table).toContain('▼ −1');
    expect(table).not.toContain('GV.RM-02');
  });

  it('los filtros y el clic en una función acotan la tabla', async () => {
    const w = mountView();
    await flushPromises();

    await w.find('[data-testid="compare-filter-all"]').trigger('click');
    expect(text(w, 'compare-subcategories')).toContain('GV.RM-02');

    await w.find('[data-testid="compare-filter-down"]').trigger('click');
    expect(text(w, 'compare-subcategories')).not.toContain('GV.OC-01');

    await w.find('[data-testid="compare-filter-all"]').trigger('click');
    await w.find('[data-testid="compare-function-Responder"]').trigger('click');
    const table = text(w, 'compare-subcategories');
    expect(table).toContain('RS.MA-01');
    expect(table).not.toContain('GV.OC-01');
    expect(w.text()).toMatch(/Cambio por subcategoría\s+· Responder/);

    await w.find('[data-testid="compare-function-Responder"]').trigger('click');
    expect(text(w, 'compare-subcategories')).toContain('GV.OC-01');

    await w.find('[data-testid="compare-filter-up"]').trigger('click');
    await w.find('[data-testid="compare-function-Responder"]').trigger('click');
    expect(w.text()).toContain('No hay subcategorías para este filtro.');
  });

  it('intercambiar A y B vuelve a comparar', async () => {
    const w = mountView();
    await flushPromises();

    await w.find('[data-testid="compare-swap"]').trigger('click');
    await flushPromises();

    expect(comparisonService.compare).toHaveBeenLastCalledWith('e-2026', 'e-2025');
  });

  it('descarga el PDF de la comparación', async () => {
    const blob = new Blob(['%PDF']);
    comparisonService.downloadPdf.mockResolvedValue({ data: blob });
    const w = mountView();
    await flushPromises();

    await w.find('[data-testid="compare-pdf"]').trigger('click');
    await flushPromises();

    expect(comparisonService.downloadPdf).toHaveBeenCalledWith('e-2025', 'e-2026');
    expect(saveBlob).toHaveBeenCalledWith(blob, 'comparativo-evaluaciones.pdf');
  });

  it('si el PDF falla lo informa', async () => {
    comparisonService.downloadPdf.mockRejectedValue({});
    const w = mountView();
    await flushPromises();

    await w.find('[data-testid="compare-pdf"]').trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('No se pudo descargar el PDF');
  });

  it('con menos de dos evaluaciones finalizadas lo explica', async () => {
    comparisonService.candidates.mockResolvedValue({ data: [CANDIDATES[0]] });
    const w = mountView();
    await flushPromises();

    expect(text(w, 'compare-not-enough')).toContain('hoy tiene una');
    expect(comparisonService.compare).not.toHaveBeenCalled();
  });

  it('avisa si los perfiles son distintos', async () => {
    comparisonService.compare.mockResolvedValue({
      data: {
        ...COMPARISON,
        sameProfile: false,
        evaluationB: { ...COMPARISON.evaluationB, profileName: 'Básico' },
      },
    });
    const w = mountView();
    await flushPromises();

    expect(text(w, 'compare-profiles')).toContain('Estándar y Básico');
  });

  it('muestra el error del backend', async () => {
    comparisonService.compare.mockRejectedValue({
      response: { data: { message: 'Solo se comparan evaluaciones finalizadas' } },
    });
    const w = mountView();
    await flushPromises();

    expect(w.text()).toContain('Solo se comparan evaluaciones finalizadas');
  });

  it('respeta A y B que vienen en la dirección', async () => {
    route.query = { a: 'e-2026', b: 'e-2025' };
    mountView();
    await flushPromises();

    expect(comparisonService.compare).toHaveBeenCalledWith('e-2026', 'e-2025');
  });

  it('el administrador elige la organización', async () => {
    auth.isAdmin = true;
    const w = mountView();
    await flushPromises();

    expect(w.find('[data-testid="compare-organization"]').exists()).toBe(true);
    expect(comparisonService.candidates).toHaveBeenCalledWith('org-1');
  });

  it('el auditor sin organizaciones no consulta nada', async () => {
    auth.isAuditor = true;
    organizationsService.getAll.mockResolvedValue({ data: { data: [], total: 0 } });
    mountView();
    await flushPromises();

    expect(comparisonService.candidates).not.toHaveBeenCalled();
  });
});
