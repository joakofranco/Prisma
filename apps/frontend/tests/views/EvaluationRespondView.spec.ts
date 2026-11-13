import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const evaluationsService = vi.hoisted(() => ({
  getById: vi.fn(),
  getResponses: vi.fn(),
  saveResponse: vi.fn(),
  getSubcategoryComments: vi.fn(),
  getFlags: vi.fn(),
  setFlag: vi.fn(),
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
                    { id: 'ctl-1', code: 'C1', description: 'control uno', targetLevel: 1 },
                    { id: 'ctl-2', code: 'C2', description: 'control dos', targetLevel: 1 },
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
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evaluationsService.setFlag.mockResolvedValue({});
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

  // ---- PRI-352: «marcar para revisar» ----

  const flagButton = (w: Awaited<ReturnType<typeof mountView>>) =>
    w.findAll('button').find((b) => b.text().includes('Marcar para revisar'))!;

  it('restaura las marcas guardadas y muestra el panel de marcadas con quién la puso', async () => {
    evaluationsService.getFlags.mockResolvedValue({
      data: [{ controlId: 'ctl-1', flaggedBy: 'u', flaggedByName: 'Ana Pérez', flaggedAt: null }],
    });
    const wrapper = await mountView();

    const items = wrapper.findAll('[data-testid="flagged-item"]');
    expect(items).toHaveLength(1);
    expect(items[0].text()).toContain('C1');
    expect(wrapper.find('[data-testid="flag-author"]').text()).toContain('Ana Pérez');
  });

  it('al marcar una pregunta la guarda en el backend y aparece en el panel', async () => {
    const wrapper = await mountView();
    expect(wrapper.find('[data-testid="flagged-panel"]').exists()).toBe(false);

    await flagButton(wrapper).trigger('click');
    await flushPromises();

    expect(evaluationsService.setFlag).toHaveBeenCalledWith('eval-1', 'ctl-1', true);
    expect(wrapper.findAll('[data-testid="flagged-item"]')).toHaveLength(1);
  });

  it('al desmarcar llama al backend y la saca del panel', async () => {
    evaluationsService.getFlags.mockResolvedValue({
      data: [{ controlId: 'ctl-1', flaggedBy: null, flaggedByName: null, flaggedAt: null }],
    });
    const wrapper = await mountView();

    await flagButton(wrapper).trigger('click');
    await flushPromises();

    expect(evaluationsService.setFlag).toHaveBeenCalledWith('eval-1', 'ctl-1', false);
    expect(wrapper.find('[data-testid="flagged-panel"]').exists()).toBe(false);
  });

  it('si el backend rechaza la marca, la revierte y avisa', async () => {
    evaluationsService.setFlag.mockRejectedValue(new Error('boom'));
    const wrapper = await mountView();

    await flagButton(wrapper).trigger('click');
    await flushPromises();

    expect(notification.error).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="flagged-panel"]').exists()).toBe(false);
  });

  it('desde el panel se salta directo a la pregunta marcada', async () => {
    evaluationsService.getFlags.mockResolvedValue({
      data: [{ controlId: 'ctl-2', flaggedBy: null, flaggedByName: null, flaggedAt: null }],
    });
    const wrapper = await mountView();
    expect(wrapper.text()).toContain('control uno');

    await wrapper.find('[data-testid="flagged-item"]').trigger('click');

    expect(wrapper.find('h3.text-lg').text()).toContain('control dos');
  });
});

describe('EvaluationRespondView: guía del control', () => {
  const guide = {
    interpretation: 'Qué se evalúa en lenguaje llano.',
    example: 'Una forma posible de cumplir.',
    evidence: 'Acta de aprobación.',
  };

  function catalogWithGuide() {
    const copy = structuredClone(catalog);
    copy.functions[0].categories[0].subcategories[0].requirements[0].controls[0] = {
      ...copy.functions[0].categories[0].subcategories[0].requirements[0].controls[0],
      guide,
    } as never;
    return copy;
  }

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    evaluationsService.getById.mockResolvedValue({
      data: { id: 'eval-1', name: 'Eval', catalogVersion: '5.0', communityProfileId: null },
    });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    evaluationsService.getSubcategoryComments.mockResolvedValue({ data: [] });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
  });

  it('muestra el botón de la guía solo cuando el control tiene guía', async () => {
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
    const without = await mountView();
    expect(without.find('[data-testid="open-control-guide"]').exists()).toBe(false);

    catalogService.getByVersion.mockResolvedValue({ data: catalogWithGuide() });
    const withGuide = await mountView();
    expect(withGuide.find('[data-testid="open-control-guide"]').exists()).toBe(true);
  });

  it('abre el modal con interpretación, ejemplo y evidencia del control', async () => {
    catalogService.getByVersion.mockResolvedValue({ data: catalogWithGuide() });
    const wrapper = await mountView();

    await wrapper.find('[data-testid="open-control-guide"]').trigger('click');
    await flushPromises();

    const text = document.body.textContent ?? '';
    expect(text).toContain('Guía del control C1');
    expect(text).toContain('Qué se evalúa en lenguaje llano.');
    expect(text).toContain('Una forma posible de cumplir.');
    expect(text).toContain('Acta de aprobación.');
  });
});
