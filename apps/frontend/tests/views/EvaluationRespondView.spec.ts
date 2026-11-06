import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const evaluationsService = vi.hoisted(() => ({
  getById: vi.fn(),
  getResponses: vi.fn(),
  saveResponse: vi.fn(),
  getSubcategoryComments: vi.fn(),
  saveSubcategoryComment: vi.fn(),
  calculateMaturity: vi.fn(),
}));
const catalogService = vi.hoisted(() => ({ getByVersion: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));

vi.mock('@/services/resources', () => ({ evaluationsService, catalogService }));
vi.mock('@/composables/useUtils', () => ({ useNotification: () => notification }));
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'eval-1' } }),
  useRouter: () => ({ push: vi.fn() }),
}));

import EvaluationRespondView from '@/views/evaluations/EvaluationRespondView.vue';

const catalog = {
  id: 'cat',
  version: '5.0',
  functions: [
    {
      id: 'f1',
      name: 'Gobernar',
      description: '',
      categories: [
        {
          id: 'c1',
          name: 'Contexto',
          description: '',
          subcategories: [
            {
              id: 'sub-1',
              name: 'Misión',
              description: '',
              requirements: [
                {
                  id: 'r1',
                  code: 'R1',
                  description: 'req',
                  controls: [
                    { id: 'ctl-1', code: 'C1', description: 'control', targetLevel: 1 },
                  ],
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
  const wrapper = mount(EvaluationRespondView, { global: { stubs: { RouterLink: true } } });
  await flushPromises();
  return wrapper;
}

describe('EvaluationRespondView: comentario por subcategoría (RF-EVA-06)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    evaluationsService.getById.mockResolvedValue({
      data: { id: 'eval-1', name: 'Eval', catalogVersion: '5.0', communityProfileId: null },
    });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    evaluationsService.getSubcategoryComments.mockResolvedValue({ data: [] });
    evaluationsService.saveResponse.mockResolvedValue({ data: {} });
    evaluationsService.saveSubcategoryComment.mockResolvedValue({ data: {} });
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  it('carga el comentario guardado de la subcategoría al abrir la evaluación', async () => {
    evaluationsService.getSubcategoryComments.mockResolvedValue({
      data: [{ subcategoryId: 'sub-1', comment: 'ya comentado', updatedBy: null, updatedAt: null }],
    });
    const wrapper = await mountView();

    const textarea = wrapper.find('[data-testid="subcategory-comment"] textarea');
    expect((textarea.element as HTMLTextAreaElement).value).toBe('ya comentado');
  });

  it('al guardar el progreso envía el comentario editado de la subcategoría', async () => {
    const wrapper = await mountView();

    await wrapper.find('[data-testid="subcategory-comment"] textarea').setValue('nuevo texto');
    const save = wrapper.findAll('button').find((b) => b.text().includes('Guardar'));
    await save!.trigger('click');
    await flushPromises();

    expect(evaluationsService.saveSubcategoryComment).toHaveBeenCalledWith(
      'eval-1',
      'sub-1',
      'nuevo texto',
    );
  });

  it('no reenvía un comentario que no cambió', async () => {
    evaluationsService.getSubcategoryComments.mockResolvedValue({
      data: [{ subcategoryId: 'sub-1', comment: 'igual', updatedBy: null, updatedAt: null }],
    });
    const wrapper = await mountView();

    const save = wrapper.findAll('button').find((b) => b.text().includes('Guardar'));
    await save!.trigger('click');
    await flushPromises();

    expect(evaluationsService.saveSubcategoryComment).not.toHaveBeenCalled();
  });

});
