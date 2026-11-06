import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const improvementService = vi.hoisted(() => ({
  getByEvaluation: vi.fn(),
  getSuggestions: vi.fn(),
  getRemediationTips: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
}));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));

vi.mock('@/services/resources', () => ({ improvementService }));
vi.mock('@/composables/useUtils', () => ({ useNotification: () => notification }));
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { evaluationId: 'eval-1' } }) }));

import ImprovementView from '@/views/improvement/ImprovementView.vue';

const plan = (
  id: string,
  priority: string,
  status: string,
  extra: Record<string, unknown> = {},
) => ({
  id,
  evaluationId: 'eval-1',
  controlId: `c-${id}`,
  action: `Acción ${id}`,
  responsible: 'Ana',
  priority,
  status,
  dueDate: '2026-12-31',
  comments: null,
  createdAt: '2026-11-01T00:00:00Z',
  ...extra,
});

async function mountView() {
  const wrapper = mount(ImprovementView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  return wrapper;
}

const bar = (key: string) =>
  document.body.querySelector<HTMLElement>(`[data-test="progress-${key}"]`)!;
const valueOf = (key: string) =>
  bar(key).querySelector('[role="progressbar"]')!.getAttribute('aria-valuenow');

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  improvementService.getByEvaluation.mockResolvedValue({ data: [] });
  improvementService.update.mockResolvedValue({ data: {} });
  improvementService.create.mockResolvedValue({ data: {} });
});

describe('ImprovementView: avance de remediación (PRI-307)', () => {
  it('muestra el porcentaje global y por prioridad con «X de Y cerradas»', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [
        plan('1', 'HIGH', 'COMPLETED'),
        plan('2', 'HIGH', 'PENDING'),
        plan('3', 'MEDIUM', 'COMPLETED'),
        plan('4', 'LOW', 'IN_PROGRESS'),
      ],
    });
    await mountView();

    expect(valueOf('overall')).toBe('50');
    expect(valueOf('HIGH')).toBe('50');
    expect(valueOf('MEDIUM')).toBe('100');
    expect(valueOf('LOW')).toBe('0');
    expect(bar('HIGH').textContent).toContain('1 de 2 cerradas');
    expect(bar('overall').textContent).toContain('2 de 4 cerradas');
  });

  it('una prioridad sin acciones dice «Sin acciones»', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'COMPLETED')],
    });
    await mountView();

    expect(bar('MEDIUM').textContent).toContain('Sin acciones');
    expect(bar('LOW').textContent).toContain('Sin acciones');
    expect(bar('HIGH').textContent).toContain('100 %');
  });

  it('sin ninguna acción muestra un estado vacío coherente y ninguna barra', async () => {
    await mountView();

    expect(document.body.querySelector('[data-test="progress-empty"]')).not.toBeNull();
    expect(document.body.querySelector('[role="progressbar"]')).toBeNull();
    expect(document.body.textContent).toContain('No hay planes de mejora registrados');
  });

  it('una acción vencida no cuenta como cerrada', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'OVERDUE'), plan('2', 'HIGH', 'COMPLETED')],
    });
    await mountView();

    expect(valueOf('HIGH')).toBe('50');
  });

  it('completar una acción sube las barras sin recargar la página', async () => {
    const pending = [plan('1', 'HIGH', 'PENDING'), plan('2', 'LOW', 'PENDING')];
    const done = [plan('1', 'HIGH', 'COMPLETED'), plan('2', 'LOW', 'PENDING')];
    improvementService.getByEvaluation.mockResolvedValueOnce({ data: pending });
    improvementService.getByEvaluation.mockResolvedValueOnce({ data: done });
    const wrapper = await mountView();
    expect(valueOf('HIGH')).toBe('0');

    // El cambio de estado vuelve a pedir la lista (como lo hace el select de estado).
    const select = wrapper.findAllComponents({ name: 'BaseSelect' })[0];
    select.vm.$emit('update:modelValue', 'COMPLETED');
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { status: 'COMPLETED' });
    expect(valueOf('HIGH')).toBe('100');
    expect(valueOf('overall')).toBe('50');
  });

  it('las tarjetas de conteo por estado se mantienen', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();

    for (const title of ['Pendientes', 'En Progreso', 'Completadas', 'Vencidas']) {
      expect(document.body.textContent).toContain(title);
    }
  });

  it('el bloque tiene un título accesible', async () => {
    await mountView();

    const section = document.body.querySelector('section[aria-labelledby]')!;
    const title = document.getElementById(section.getAttribute('aria-labelledby')!)!;
    expect(title.textContent).toContain('Avance de remediación');
  });
});

describe('ImprovementView: comentarios del ítem (PRI-303)', () => {
  const MAX = 2000;

  async function openCreateModal() {
    const wrapper = await mountView();
    const open = wrapper.findAll('button').find((b) => b.text().includes('Nueva Acción'))!;
    await open.trigger('click');
    await flushPromises();
    return wrapper;
  }

  function fillRequired() {
    const inputs = Array.from(document.body.querySelectorAll<HTMLInputElement>('input'));
    const set = (el: HTMLInputElement, value: string) => {
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    set(
      inputs.find((i) => i.placeholder.includes('Describa'))!,
      'Acción nueva',
    );
    set(
      inputs.find((i) => i.placeholder.includes('responsable'))!,
      'Juan',
    );
    set(
      inputs.find((i) => i.type === 'date')!,
      '2026-12-31',
    );
  }

  const commentsBox = () =>
    Array.from(document.body.querySelectorAll<HTMLTextAreaElement>('textarea')).find((t) =>
      t.placeholder.includes('Notas'),
    )!;

  const modalButton = (text: string) =>
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.trim() === text,
    )!;

  it('el formulario de alta tiene un campo de comentarios y los envía al crear', async () => {
    await openCreateModal();
    fillRequired();
    commentsBox().value = 'Esperar el presupuesto';
    commentsBox().dispatchEvent(new Event('input'));
    modalButton('Crear').click();
    await flushPromises();

    expect(improvementService.create).toHaveBeenCalledTimes(1);
    expect(improvementService.create.mock.calls[0][0]).toMatchObject({
      action: 'Acción nueva',
      comments: 'Esperar el presupuesto',
    });
  });

  it('crear sin comentarios sigue funcionando', async () => {
    await openCreateModal();
    fillRequired();
    modalButton('Crear').click();
    await flushPromises();

    expect(improvementService.create).toHaveBeenCalledTimes(1);
    expect(notification.success).toHaveBeenCalled();
  });

  it('un comentario de más de 2000 caracteres se rechaza antes de enviar', async () => {
    await openCreateModal();
    fillRequired();
    commentsBox().value = 'x'.repeat(MAX + 1);
    commentsBox().dispatchEvent(new Event('input'));
    modalButton('Crear').click();
    await flushPromises();

    expect(improvementService.create).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('no pueden superar los 2000 caracteres');
  });

  it('uno de exactamente 2000 caracteres se acepta', async () => {
    await openCreateModal();
    fillRequired();
    commentsBox().value = 'x'.repeat(MAX);
    commentsBox().dispatchEvent(new Event('input'));
    modalButton('Crear').click();
    await flushPromises();

    expect(improvementService.create).toHaveBeenCalledTimes(1);
  });

  it('si el backend rechaza la creación muestra su mensaje', async () => {
    improvementService.create.mockRejectedValue({
      response: { data: { message: 'Error de validación' } },
    });
    await openCreateModal();
    fillRequired();
    modalButton('Crear').click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('Error de validación');
  });

  it('cada ítem muestra su comentario cuando existe y no cuando falta', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [
        plan('1', 'HIGH', 'PENDING', { comments: 'Depende del área legal' }),
        plan('2', 'LOW', 'PENDING'),
      ],
    });
    await mountView();

    const shown = document.body.querySelectorAll('[data-test="plan-comments"]');
    expect(shown).toHaveLength(1);
    expect(shown[0].textContent).toContain('Depende del área legal');
  });

  it('el botón del ítem dice «Agregar» si no hay comentario y «Editar» si lo hay', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'PENDING', { comments: 'algo' }), plan('2', 'LOW', 'PENDING')],
    });
    await mountView();

    expect(document.body.querySelectorAll('button[title="Editar comentarios"]')).toHaveLength(1);
    expect(document.body.querySelectorAll('button[title="Agregar comentarios"]')).toHaveLength(1);
  });

  it('editar el comentario de un ítem existente lo guarda y recarga la lista', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'PENDING', { comments: 'viejo' })],
    });
    await mountView();
    (document.body.querySelector('button[title="Editar comentarios"]') as HTMLElement).click();
    await flushPromises();

    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    expect(box.value).toBe('viejo');
    box.value = 'nuevo';
    box.dispatchEvent(new Event('input'));
    modalButton('Guardar').click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { comments: 'nuevo' });
    expect(notification.success).toHaveBeenCalledWith('Comentarios guardados');
    expect(improvementService.getByEvaluation).toHaveBeenCalledTimes(2);
  });

  it('dejar el campo vacío borra el comentario (viaja como cadena vacía, no como null)', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'PENDING', { comments: 'borrar' })],
    });
    await mountView();
    (document.body.querySelector('button[title="Editar comentarios"]') as HTMLElement).click();
    await flushPromises();

    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = '';
    box.dispatchEvent(new Event('input'));
    modalButton('Guardar').click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { comments: '' });
    expect(notification.success).toHaveBeenCalledWith('Comentarios eliminados');
  });

  it('un ítem creado antes de la migración (sin comentario) se puede editar', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'PENDING', { comments: undefined })],
    });
    await mountView();
    (document.body.querySelector('button[title="Agregar comentarios"]') as HTMLElement).click();
    await flushPromises();

    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    expect(box.value).toBe('');
    box.value = 'primer comentario';
    box.dispatchEvent(new Event('input'));
    modalButton('Guardar').click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { comments: 'primer comentario' });
  });

  it('un comentario demasiado largo al editar muestra el error y no llama al servicio', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();
    (document.body.querySelector('button[title="Agregar comentarios"]') as HTMLElement).click();
    await flushPromises();

    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = 'x'.repeat(MAX + 1);
    box.dispatchEvent(new Event('input'));
    modalButton('Guardar').click();
    await flushPromises();

    expect(improvementService.update).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('no pueden superar los 2000 caracteres');
  });

  it('si el backend rechaza el guardado muestra su mensaje en el modal', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    improvementService.update.mockRejectedValue({
      response: { data: { message: 'Los comentarios no pueden superar los 2000 caracteres' } },
    });
    await mountView();
    (document.body.querySelector('button[title="Agregar comentarios"]') as HTMLElement).click();
    await flushPromises();
    const box = document.body.querySelector<HTMLTextAreaElement>('textarea')!;
    box.value = 'algo';
    box.dispatchEvent(new Event('input'));
    modalButton('Guardar').click();
    await flushPromises();

    expect(document.body.textContent).toContain('no pueden superar los 2000 caracteres');
  });
});
