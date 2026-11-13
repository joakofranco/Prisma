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

describe('EvaluationRespondView: acciones según el estado de la evaluación', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    evaluationsService.getSubcategoryComments.mockResolvedValue({ data: [] });
    evaluationsService.saveResponse.mockResolvedValue({ data: {} });
    evaluationsService.getFlags.mockResolvedValue({ data: [] });
    evaluationsService.setFlag.mockResolvedValue({});
    evaluationsService.saveSubcategoryComment.mockResolvedValue({ data: {} });
    evaluationsService.calculateMaturity.mockResolvedValue({});
    catalogService.getByVersion.mockResolvedValue({ data: catalog });
  });

  async function mountWith(status?: string) {
    evaluationsService.getById.mockResolvedValue({
      data: {
        id: 'eval-1',
        name: 'Eval',
        status,
        catalogVersion: '5.0',
        communityProfileId: null,
      },
    });
    return mountView();
  }

  const isDisabled = (el: { element: Element }) => (el.element as HTMLButtonElement).disabled;

  it.each(['DRAFT', 'IN_PROGRESS', 'RETURNED'])(
    '%s: se puede responder y guardar',
    async (status) => {
      const wrapper = await mountWith(status);

      expect(wrapper.find('[data-testid="respond-locked"]').exists()).toBe(false);
      expect(isDisabled(wrapper.find('[data-testid="save-progress"]'))).toBe(false);
      for (const radio of wrapper.findAll('[role="radio"]')) expect(isDisabled(radio)).toBe(false);
      expect(
        (
          wrapper.find('[data-testid="subcategory-comment"] textarea')
            .element as HTMLTextAreaElement
        ).disabled,
      ).toBe(false);
      expect(isDisabled(wrapper.find('[data-testid="flag-control"]'))).toBe(false);
    },
  );

  it.each(['READY_FOR_AUDIT', 'IN_AUDIT', 'APPROVED', 'ARCHIVED'])(
    '%s: respuestas, comentarios y guardado quedan de solo lectura',
    async (status) => {
      const wrapper = await mountWith(status);

      expect(wrapper.find('[data-testid="respond-locked"]').text()).toContain('solo lectura');
      expect(isDisabled(wrapper.find('[data-testid="save-progress"]'))).toBe(true);
      const radios = wrapper.findAll('[role="radio"]');
      expect(radios).toHaveLength(2);
      for (const radio of radios) expect(isDisabled(radio)).toBe(true);
      expect(
        (
          wrapper.find('[data-testid="subcategory-comment"] textarea')
            .element as HTMLTextAreaElement
        ).disabled,
      ).toBe(true);
      const addObservation = wrapper.findAll('button').find((b) => b.text().includes('Agregar'));
      expect(isDisabled(addObservation!)).toBe(true);
    },
  );

  it('«Marcar para revisar» también se puede durante la auditoría, pero no cerrada la evaluación', async () => {
    const inAudit = await mountWith('IN_AUDIT');
    expect(isDisabled(inAudit.find('[data-testid="flag-control"]'))).toBe(false);

    for (const status of ['READY_FOR_AUDIT', 'APPROVED', 'ARCHIVED']) {
      const wrapper = await mountWith(status);
      expect(isDisabled(wrapper.find('[data-testid="flag-control"]')), status).toBe(true);
    }
  });

  it('en solo lectura un clic sobre «Guardar progreso» no llama al servidor', async () => {
    const wrapper = await mountWith('APPROVED');

    await wrapper.find('[data-testid="save-progress"]').trigger('click');
    await flushPromises();

    expect(evaluationsService.saveResponse).not.toHaveBeenCalled();
    expect(evaluationsService.saveSubcategoryComment).not.toHaveBeenCalled();
  });

  it('en solo lectura se puede recorrer el catálogo y la última pregunta ofrece volver, sin guardar', async () => {
    const wrapper = await mountWith('APPROVED');
    const next = wrapper.find('[data-testid="next-or-finish"]');
    expect(next.text()).toBe('Siguiente');

    await next.trigger('click');
    expect(wrapper.find('[data-testid="next-or-finish"]').text()).toBe('Volver a la evaluación');
    await wrapper.find('[data-testid="next-or-finish"]').trigger('click');
    await flushPromises();

    expect(evaluationsService.saveResponse).not.toHaveBeenCalled();
    expect(evaluationsService.calculateMaturity).not.toHaveBeenCalled();
  });

  it('con el estado desconocido no se bloquea nada: decide el backend', async () => {
    const wrapper = await mountWith(undefined);

    expect(wrapper.find('[data-testid="respond-locked"]').exists()).toBe(false);
    expect(isDisabled(wrapper.find('[data-testid="save-progress"]'))).toBe(false);
  });
});
