import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import type { EvaluationOverview } from '@/types';

const evaluationsService = vi.hoisted(() => ({
  getOverview: vi.fn(),
  getById: vi.fn(),
}));

vi.mock('@/services/resources', () => ({ evaluationsService }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'eval-1' } }),
}));

import EvaluationOverviewView from '@/views/evaluations/EvaluationOverviewView.vue';

const overview: EvaluationOverview = {
  evaluationId: 'eval-1',
  evaluationName: 'Eva1_1',
  catalogVersion: '5.0',
  catalogLabel: 'MCU 5.0',
  communityProfileId: 'p1',
  communityProfileName: 'Perfil ULTRABASICO',
  subcategoryCount: 2,
  requirementCount: 1,
  controlCount: 3,
  priorityCounts: { alta: 1, media: 1, baja: 0, sinPrioridad: 0 },
  functions: [
    {
      id: 'f1',
      code: 'PR',
      name: 'Proteger',
      categories: [
        {
          id: 'c1',
          code: 'PR.PS',
          name: 'Seguridad de los datos',
          subcategories: [
            {
              id: 's1',
              code: 'PR.PS-06',
              name: 'Prácticas seguras de desarrollo',
              description: 'Se integran prácticas seguras.',
              priority: 'ALTA',
              requirements: [{ id: 'r1', code: 'AD.1', description: 'Metodología de desarrollo' }],
              controls: [
                {
                  id: 'k1',
                  code: 'AD.1-1',
                  description: 'Lineamientos generales',
                  targetLevel: 1,
                  requirementCode: 'AD.1',
                },
                {
                  id: 'k2',
                  code: 'AD.1-2',
                  description: 'Principios de desarrollo seguro',
                  targetLevel: 2,
                  requirementCode: 'AD.1',
                },
              ],
            },
            {
              id: 's2',
              code: 'PR.PS-07',
              name: 'Gestión de cambios',
              description: null,
              priority: 'MEDIA',
              requirements: [{ id: 'r1', code: 'AD.1', description: 'Metodología de desarrollo' }],
              controls: [
                {
                  id: 'k3',
                  code: 'AD.1-3',
                  description: 'Control de versiones',
                  targetLevel: 3,
                  requirementCode: 'AD.1',
                },
              ],
            },
          ],
        },
      ],
    },
  ],
};

async function mountView() {
  const wrapper = mount(EvaluationOverviewView, { global: { stubs: { RouterLink: true } } });
  await flushPromises();
  return wrapper;
}

describe('EvaluationOverviewView (RF-EVA-04)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    evaluationsService.getOverview.mockResolvedValue({ data: overview });
    evaluationsService.getById.mockResolvedValue({
      data: {
        id: 'eval-1',
        name: 'Eva1_1',
        organizationName: 'Organización 1',
        status: 'DRAFT',
      },
    });
  });

  it('muestra la evaluación, el perfil y los totales', async () => {
    const wrapper = await mountView();

    expect(evaluationsService.getOverview).toHaveBeenCalledWith('eval-1');
    expect(wrapper.text()).toContain('Eva1_1');
    expect(wrapper.text()).toContain('Organización 1');
    expect(wrapper.text()).toContain('MCU 5.0');
    expect(wrapper.text()).toContain('Perfil ULTRABASICO');
    expect(wrapper.text()).toContain('Subcategorías');
    expect(wrapper.text()).toContain('Controles');
  });

  it('lista las subcategorías con su ID, prioridad y distribución por nivel', async () => {
    const wrapper = await mountView();

    const text = wrapper.text();
    expect(text).toContain('PR.PS-06');
    expect(text).toContain('Prácticas seguras de desarrollo');
    expect(text).toContain('Prioridad Alta');
    expect(text).toContain('PR.PS-07');
    expect(text).toContain('Prioridad Media');
    expect(text).toContain('N1 · 1');
    expect(text).toContain('N2 · 1');
    expect(text).toContain('2 de 2 subcategorías');
  });

  it('abre la primera subcategoría con sus requisitos y los controles del nivel 1', async () => {
    const wrapper = await mountView();

    const text = wrapper.text();
    expect(text).toContain('Se integran prácticas seguras.');
    expect(text).toContain('Requisitos MCU relacionados');
    expect(text).toContain('Metodología de desarrollo');
    expect(text).toContain('AD.1-1');
    expect(text).not.toContain('AD.1-3');
  });

  it('expande y contrae una subcategoría al hacer clic en su encabezado', async () => {
    const wrapper = await mountView();

    const header = wrapper.find('[aria-controls="panel-s2"]');
    expect(header.attributes('aria-expanded')).toBe('false');
    await header.trigger('click');
    expect(header.attributes('aria-expanded')).toBe('true');
    expect(wrapper.find('#panel-s2').exists()).toBe(true);
    await header.trigger('click');
    expect(wrapper.find('#panel-s2').exists()).toBe(false);
  });

  it('Expandir todo abre todos los niveles y Contraer todo los cierra', async () => {
    const wrapper = await mountView();

    const expand = wrapper.findAll('button').find((b) => b.text() === 'Expandir todo')!;
    await expand.trigger('click');
    expect(wrapper.text()).toContain('AD.1-2');
    expect(wrapper.text()).toContain('AD.1-3');

    const collapse = wrapper.findAll('button').find((b) => b.text() === 'Contraer todo')!;
    await collapse.trigger('click');
    expect(wrapper.find('#panel-s1').exists()).toBe(false);
    expect(wrapper.find('#panel-s2').exists()).toBe(false);
  });

  it('filtra por prioridad', async () => {
    const wrapper = await mountView();

    const media = wrapper.findAll('button').find((b) => b.text() === 'Media')!;
    await media.trigger('click');

    expect(wrapper.text()).toContain('PR.PS-07');
    expect(wrapper.find('#sub-s1').exists()).toBe(false);
    expect(wrapper.text()).toContain('1 de 2 subcategorías');
  });

  it('filtra por texto y avisa cuando no hay coincidencias', async () => {
    const wrapper = await mountView();

    const search = wrapper.find('#overview-search');
    await search.setValue('control de versiones');
    expect(wrapper.find('#sub-s2').exists()).toBe(true);
    expect(wrapper.find('#sub-s1').exists()).toBe(false);

    await search.setValue('zzz-sin-resultados');
    expect(wrapper.text()).toContain('Ninguna subcategoría coincide');
  });

  it('muestra un mensaje cuando el perfil no tiene subcategorías', async () => {
    evaluationsService.getOverview.mockResolvedValue({
      data: { ...overview, subcategoryCount: 0, controlCount: 0, functions: [] },
    });

    const wrapper = await mountView();

    expect(wrapper.text()).toContain('no tiene');
    expect(wrapper.text()).toContain('subcategorías con controles');
  });

  it('muestra el error cuando falla la carga', async () => {
    evaluationsService.getOverview.mockRejectedValue(new Error('boom'));

    const wrapper = await mountView();

    expect(wrapper.text()).toContain('boom');
  });
});
