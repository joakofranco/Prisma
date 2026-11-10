import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';

const improvementService = vi.hoisted(() => ({
  getByEvaluation: vi.fn(),
  getSuggestions: vi.fn(),
  getRemediationTips: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
}));
const evaluationsService = vi.hoisted(() => ({ getById: vi.fn() }));
const planDeadlinesService = vi.hoisted(() => ({ get: vi.fn() }));
const auth = vi.hoisted(() => ({ hasAnyRole: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));

vi.mock('@/services/resources', () => ({
  improvementService,
  evaluationsService,
  planDeadlinesService,
}));
vi.mock('@/stores/auth', () => ({ useAuthStore: () => auth }));
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
  // «Hoy» fijo (solo Date, los timers siguen reales para flushPromises): 09/11/2026.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 10, 9, 12, 0, 0));
  document.body.innerHTML = '';
  auth.hasAnyRole.mockReturnValue(true);
  evaluationsService.getById.mockResolvedValue({ data: { status: 'IN_PROGRESS' } });
  planDeadlinesService.get.mockResolvedValue({
    data: { highMonths: 3, mediumMonths: 6, lowMonths: 12 },
  });
  improvementService.getByEvaluation.mockResolvedValue({ data: [] });
  improvementService.update.mockResolvedValue({ data: {} });
  improvementService.create.mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.useRealTimers();
});

const buttonWithText = (text: string) =>
  Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find(
    (b) => b.textContent?.trim() === text,
  );

async function openEditorFor(index = 0) {
  document.body.querySelectorAll<HTMLElement>('button[title="Editar acción"]')[index].click();
  await flushPromises();
}

function setValue(el: HTMLInputElement | HTMLTextAreaElement, value: string) {
  el.value = value;
  el.dispatchEvent(new Event('input'));
}

const editCommentsBox = () =>
  Array.from(document.body.querySelectorAll<HTMLTextAreaElement>('textarea')).find((t) =>
    t.placeholder.includes('Vacío para borrar'),
  )!;
const editResponsible = () =>
  document.body.querySelector<HTMLInputElement>('input[placeholder="Nombre del responsable"]')!;
const editDate = () => document.body.querySelector<HTMLInputElement>('input[type="date"]')!;
const selectLabeled = (wrapper: VueWrapper, label: string) =>
  wrapper.findAllComponents({ name: 'BaseSelect' }).find((s) => s.props('label') === label)!;

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

    for (const title of ['Pendientes', 'En curso', 'Realizadas', 'Vencidas']) {
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

  // Desde PRI-305 los comentarios se editan en el modal «Editar acción».
  it('editar el comentario de un ítem existente lo guarda y recarga la lista', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'PENDING', { comments: 'viejo' })],
    });
    await mountView();
    await openEditorFor(0);

    expect(editCommentsBox().value).toBe('viejo');
    setValue(editCommentsBox(), 'nuevo');
    modalButton('Guardar cambios').click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { comments: 'nuevo' });
    expect(notification.success).toHaveBeenCalledWith('Acción actualizada');
    expect(improvementService.getByEvaluation).toHaveBeenCalledTimes(2);
  });

  it('dejar el campo vacío borra el comentario (viaja como cadena vacía, no como null)', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'PENDING', { comments: 'borrar' })],
    });
    await mountView();
    await openEditorFor(0);

    setValue(editCommentsBox(), '');
    modalButton('Guardar cambios').click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { comments: '' });
  });

  it('un ítem creado antes de la migración (sin comentario) se puede editar', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'PENDING', { comments: undefined })],
    });
    await mountView();
    await openEditorFor(0);

    expect(editCommentsBox().value).toBe('');
    setValue(editCommentsBox(), 'primer comentario');
    modalButton('Guardar cambios').click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { comments: 'primer comentario' });
  });

  it('un comentario demasiado largo al editar muestra el error y no llama al servicio', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();
    await openEditorFor(0);

    setValue(editCommentsBox(), 'x'.repeat(MAX + 1));
    modalButton('Guardar cambios').click();
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
    await openEditorFor(0);
    setValue(editCommentsBox(), 'algo');
    modalButton('Guardar cambios').click();
    await flushPromises();

    expect(document.body.textContent).toContain('no pueden superar los 2000 caracteres');
  });
});

describe('ImprovementView: gestión operativa del plan (PRI-305, RF-PLN-04)', () => {
  it('editar plazo y responsable envía solo los campos que cambiaron', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();
    await openEditorFor(0);

    expect(editDate().min).toBe('2026-11-09');
    setValue(editResponsible(), 'Beto');
    setValue(editDate(), '2026-12-15');
    buttonWithText('Guardar cambios')!.click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', {
      responsible: 'Beto',
      dueDate: '2026-12-15',
    });
  });

  it('no deja guardar una fecha límite anterior a hoy', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();
    await openEditorFor(0);

    setValue(editDate(), '2026-11-01');
    buttonWithText('Guardar cambios')!.click();
    await flushPromises();

    expect(improvementService.update).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('La fecha límite no puede ser anterior a hoy');
  });

  it('una acción vencida a la que se le pone una fecha vigente pasa a Pendiente', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'OVERDUE', { dueDate: '2026-10-30' })],
    });
    await mountView();
    await openEditorFor(0);

    setValue(editDate(), '2026-11-30');
    await flushPromises();
    expect(document.body.querySelector('[data-test="overdue-reset"]')?.textContent).toContain(
      'Pendiente',
    );
    buttonWithText('Guardar cambios')!.click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', {
      status: 'PENDING',
      dueDate: '2026-11-30',
    });
  });

  it('en la fila, una acción vencida solo ofrece Realizado y muestra «Vencida»', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'OVERDUE', { dueDate: '2026-10-30' })],
    });
    const wrapper = await mountView();

    const options = wrapper.findAllComponents({ name: 'BaseSelect' })[0].props('options') as {
      value: string;
      label: string;
      disabled?: boolean;
    }[];
    const byValue = Object.fromEntries(options.map((o) => [o.value, o]));
    expect(byValue.PENDING.disabled).toBe(true);
    expect(byValue.IN_PROGRESS.disabled).toBe(true);
    expect(byValue.COMPLETED.disabled).toBeFalsy();
    expect(byValue.COMPLETED.label).toBe('Realizado');
    expect(byValue.OVERDUE).toMatchObject({ label: 'Vencida', disabled: true });
  });

  it('las etiquetas de estado son las del requerimiento', async () => {
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'HIGH', 'IN_PROGRESS'), plan('2', 'LOW', 'COMPLETED')],
    });
    await mountView();

    expect(document.body.textContent).toContain('En curso');
    expect(document.body.textContent).toContain('Realizado');
    expect(document.body.textContent).not.toContain('Completada');
    expect(document.body.textContent).not.toContain('En Progreso');
  });

  it('si el backend rechaza el cambio de estado lo avisa', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    improvementService.update.mockRejectedValue({
      response: { data: { message: 'La fecha límite ya venció' } },
    });
    const wrapper = await mountView();

    wrapper.findAllComponents({ name: 'BaseSelect' })[0].vm.$emit('update:modelValue', 'COMPLETED');
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('La fecha límite ya venció');
  });

  it('sin rol de gestión el plan es de solo lectura', async () => {
    auth.hasAnyRole.mockReturnValue(false);
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    const wrapper = await mountView();

    expect(buttonWithText('Nueva Acción')).toBeUndefined();
    expect(buttonWithText('Generar Sugerencias')).toBeUndefined();
    expect(document.body.querySelector('button[title="Editar acción"]')).toBeNull();
    expect(wrapper.findAllComponents({ name: 'BaseSelect' })).toHaveLength(0);
  });

  it('con la evaluación en borrador o archivada el plan no se edita', async () => {
    for (const status of ['DRAFT', 'ARCHIVED']) {
      document.body.innerHTML = '';
      evaluationsService.getById.mockResolvedValue({ data: { status } });
      improvementService.getByEvaluation.mockResolvedValue({
        data: [plan('1', 'HIGH', 'PENDING')],
      });
      await mountView();

      expect(document.body.querySelector('[data-test="closed-banner"]')).not.toBeNull();
      expect(document.body.querySelector('button[title="Editar acción"]')).toBeNull();
      expect(buttonWithText('Nueva Acción')!.disabled).toBe(true);
    }
  });

  it('con la evaluación aprobada solo se puede cambiar el estado', async () => {
    evaluationsService.getById.mockResolvedValue({ data: { status: 'APPROVED' } });
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    const wrapper = await mountView();

    expect(document.body.querySelector('[data-test="approved-banner"]')).not.toBeNull();
    expect(buttonWithText('Nueva Acción')!.disabled).toBe(true);
    expect(buttonWithText('Generar Sugerencias')!.disabled).toBe(true);

    await openEditorFor(0);
    expect(editResponsible().disabled).toBe(true);
    expect(editDate().disabled).toBe(true);
    expect(editCommentsBox().disabled).toBe(true);
    selectLabeled(wrapper, 'Estado').vm.$emit('update:modelValue', 'COMPLETED');
    await flushPromises();
    buttonWithText('Guardar estado')!.click();
    await flushPromises();

    expect(improvementService.update).toHaveBeenCalledWith('1', { status: 'COMPLETED' });
  });
});

describe('ImprovementView: fecha límite por defecto en «Nueva Acción» (PRI-304, RF-PLN-03)', () => {
  async function openCreate() {
    const wrapper = await mountView();
    buttonWithText('Nueva Acción')!.click();
    await flushPromises();
    return wrapper;
  }

  const createDate = () => document.body.querySelector<HTMLInputElement>('input[type="date"]')!;
  const hint = () => document.body.querySelector('[data-test="due-date-hint"]');

  it('precarga la fecha con el plazo de la prioridad (Media = 6 meses)', async () => {
    await openCreate();

    expect(createDate().value).toBe('2027-05-09');
    expect(hint()?.textContent).toContain('6 meses');
  });

  it('al cambiar la prioridad recalcula la fecha', async () => {
    const wrapper = await openCreate();

    selectLabeled(wrapper, 'Prioridad').vm.$emit('update:modelValue', 'HIGH');
    await flushPromises();

    expect(createDate().value).toBe('2027-02-09');
    expect(hint()?.textContent).toContain('3 meses');
  });

  it('si el usuario editó la fecha, cambiar la prioridad no la pisa', async () => {
    const wrapper = await openCreate();

    setValue(createDate(), '2026-12-01');
    await flushPromises();
    selectLabeled(wrapper, 'Prioridad').vm.$emit('update:modelValue', 'LOW');
    await flushPromises();

    expect(createDate().value).toBe('2026-12-01');
    expect(hint()).toBeNull();
  });

  it('usa los plazos configurados por el administrador', async () => {
    planDeadlinesService.get.mockResolvedValue({
      data: { highMonths: 1, mediumMonths: 2, lowMonths: 4 },
    });
    await openCreate();

    expect(createDate().value).toBe('2027-01-09');
  });

  it('si no se pudieron leer los plazos usa los del requerimiento', async () => {
    planDeadlinesService.get.mockRejectedValue(new Error('offline'));
    await openCreate();

    expect(createDate().value).toBe('2027-05-09');
  });
});
