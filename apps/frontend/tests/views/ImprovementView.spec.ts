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

const reportsService = vi.hoisted(() => ({
  exportImprovementPlanPDF: vi.fn(),
  exportImprovementPlanExcel: vi.fn(),
}));
const saveBlob = vi.hoisted(() => vi.fn());

vi.mock('@/services/resources', () => ({ improvementService, reportsService }));
vi.mock('@/utils/files', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/utils/files')>()),
  saveBlob,
}));
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
  evaluationsService.getResults.mockResolvedValue({ data: [] });
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

if (!Blob.prototype.text) {
  Blob.prototype.text = function (this: Blob) {
    return new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => reject(reader.error);
      reader.readAsText(this);
    });
  };
}

describe('ImprovementView: exportación del plan (RF-PLN-05)', () => {
  const exportButton = () =>
    document.body.querySelector<HTMLButtonElement>('[data-test="export-button"]')!;
  const choose = async (format: 'pdf' | 'excel') => {
    exportButton().click();
    await flushPromises();
    document.body.querySelector<HTMLElement>(`[data-test="export-${format}"]`)!.click();
    await flushPromises();
  };

  beforeEach(() => {
    reportsService.exportImprovementPlanPDF.mockResolvedValue({ data: new ArrayBuffer(8) });
    reportsService.exportImprovementPlanExcel.mockResolvedValue({ data: new ArrayBuffer(8) });
  });

  it('con el plan vacío el botón está deshabilitado y se explica por qué', async () => {
    await mountView();

    expect(exportButton().disabled).toBe(true);
    expect(document.body.querySelector('[data-test="export-hint"]')).not.toBeNull();
  });

  it('con acciones el botón está habilitado y el menú ofrece PDF y Excel', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();

    expect(exportButton().disabled).toBe(false);
    expect(document.body.querySelector('[data-test="export-pdf"]')).toBeNull();
    exportButton().click();
    await flushPromises();
    expect(document.body.querySelector('[data-test="export-pdf"]')).not.toBeNull();
    expect(document.body.querySelector('[data-test="export-excel"]')).not.toBeNull();
  });

  it('exportar a PDF pide el archivo de la evaluación y lo descarga', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();

    await choose('pdf');

    expect(reportsService.exportImprovementPlanPDF).toHaveBeenCalledWith('eval-1');
    expect(saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'plan-mejora-eval-1.pdf');
    expect(notification.success).toHaveBeenCalled();
  });

  it('exportar a Excel pide el archivo de la evaluación y lo descarga', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();

    await choose('excel');

    expect(reportsService.exportImprovementPlanExcel).toHaveBeenCalledWith('eval-1');
    expect(saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'plan-mejora-eval-1.xlsx');
  });

  it('muestra el mensaje del backend cuando la exportación falla', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    reportsService.exportImprovementPlanPDF.mockRejectedValue({
      response: {
        data: new Blob([JSON.stringify({ message: 'El plan no tiene acciones' })], {
          type: 'application/json',
        }),
      },
    });
    await mountView();

    await choose('pdf');

    expect(saveBlob).not.toHaveBeenCalled();
    await vi.waitFor(() =>
      expect(notification.error).toHaveBeenCalledWith('El plan no tiene acciones'),
    );
  });

  it('usa un mensaje genérico si el error no trae mensaje', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    reportsService.exportImprovementPlanExcel.mockRejectedValue({});
    await mountView();

    await choose('excel');

    expect(notification.error).toHaveBeenCalledWith('No se pudo generar el archivo');
  });

  it('mientras genera el archivo el botón queda deshabilitado', async () => {
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    let release: (v: unknown) => void = () => {};
    reportsService.exportImprovementPlanPDF.mockReturnValue(
      new Promise((resolve) => {
        release = resolve;
      }),
    );
    await mountView();

    await choose('pdf');

    expect(exportButton().disabled).toBe(true);
    expect(exportButton().textContent).toContain('Generando archivo');
    release({ data: new ArrayBuffer(8) });
    await flushPromises();
    expect(exportButton().disabled).toBe(false);
const createButton = () =>
  Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find(
    (b) => b.textContent?.trim() === 'Crear',
  )!;

const gapResult = (extra: Record<string, unknown> = {}) => ({
  functionId: 'f1',
  functionName: 'Proteger',
  categoryId: 'c1',
  categoryName: 'Control de acceso',
  subcategoryId: 's1',
  subcategoryName: 'PR.AA-05',
  currentLevel: 1,
  targetLevel: 4,
  gap: 3,
  nextLevel: 2,
  missingControls: [
    {
      id: 'ctl-1',
      code: 'MCU-AA-11',
      description: 'Revisar privilegios administrativos',
      targetLevel: 2,
      status: 'NON_COMPLIANT',
    },
    {
      id: 'ctl-2',
      code: 'MCU-AA-14',
      description: 'Registrar excepciones de acceso',
      targetLevel: 2,
      status: 'UNANSWERED',
    },
  ],
  ...extra,
});

describe('ImprovementView: brechas por subcategoría (RF-CALC-07)', () => {
  it('lista nivel actual, próximo nivel y controles faltantes', async () => {
    evaluationsService.getResults.mockResolvedValue({ data: [gapResult()] });
    await mountView();

    const text = document.body.querySelector('[data-test="gap-section"]')!.textContent!;
    expect(text).toContain('PR.AA-05');
    expect(text).toContain('Nivel 1');
    expect(text).toContain('Nivel 2');
    expect(text).toContain('Faltan 2 controles para llegar al nivel 2');
    expect(text).toContain('No cumple');
    expect(text).toContain('Sin respuesta');
    expect(document.body.querySelectorAll('[data-test="gap-control"]')).toHaveLength(2);
  });

  it('los niveles usan el color de texto accesible de madurez (WCAG AA)', async () => {
    evaluationsService.getResults.mockResolvedValue({ data: [gapResult()] });
    await mountView();

    const levels = document.body.querySelectorAll('[data-test="gap-levels"] b');
    expect(levels).toHaveLength(2);
    levels.forEach((el) => {
      expect(el.classList.contains('maturity-text')).toBe(true);
      expect((el as HTMLElement).style.getPropertyValue('--maturity-fg')).not.toBe('');
    });
  });

  it('cuenta las subcategorías sin brecha y no las lista', async () => {
    evaluationsService.getResults.mockResolvedValue({
      data: [gapResult(), gapResult({ subcategoryId: 's2', nextLevel: null, missingControls: [] })],
    });
    await mountView();

    expect(document.body.querySelector('[data-test="gap-summary"]')!.textContent).toContain(
      '1 con brecha · 1 sin brecha',
    );
    expect(document.body.querySelectorAll('[data-test="gap-row"]')).toHaveLength(1);
  });

  it('muestra un mensaje cuando no hay resultados calculados', async () => {
    await mountView();

    expect(document.body.querySelector('[data-test="gap-empty"]')).not.toBeNull();
  });

  it('«Crear acción» abre el modal con el control precargado y lo envía al crear', async () => {
    evaluationsService.getResults.mockResolvedValue({ data: [gapResult()] });
    await mountView();

    (document.body.querySelector('[data-test="gap-create"]') as HTMLElement).click();
    await flushPromises();

    expect(document.body.textContent).toContain('Nueva Acción de Mejora');
    const action = document.body.querySelector<HTMLInputElement>(
      'input[placeholder="Describa la acción de mejora"]',
    )!;
    expect(action.value).toBe(
      'Implementar (PR.AA-05, nivel 2): Revisar privilegios administrativos',
    );

    const responsible = document.body.querySelector<HTMLInputElement>(
      'input[placeholder="Nombre del responsable"]',
    )!;
    responsible.value = 'Ana';
    responsible.dispatchEvent(new Event('input'));
    const due = document.body.querySelector<HTMLInputElement>('input[type="date"]')!;
    due.value = '2026-12-31';
    due.dispatchEvent(new Event('input'));
    createButton().click();
    await flushPromises();

    expect(improvementService.create).toHaveBeenCalledWith(
      expect.objectContaining({
        evaluationId: 'eval-1',
        controlId: 'ctl-1',
        priority: 'MEDIUM',
        status: 'PENDING',
      }),
    );
  });

  it('marca «Acción creada» en los controles que ya tienen plan', async () => {
    evaluationsService.getResults.mockResolvedValue({ data: [gapResult()] });
    improvementService.getByEvaluation.mockResolvedValue({
      data: [plan('1', 'MEDIUM', 'PENDING', { controlId: 'ctl-1' })],
    });
    await mountView();

    expect(document.body.querySelectorAll('[data-test="gap-planned"]')).toHaveLength(1);
    expect(document.body.querySelectorAll('[data-test="gap-create"]')).toHaveLength(1);
  });

  it('si falla la carga de resultados el resto de la pantalla sigue funcionando', async () => {
    evaluationsService.getResults.mockRejectedValue(new Error('boom'));
    improvementService.getByEvaluation.mockResolvedValue({ data: [plan('1', 'HIGH', 'PENDING')] });
    await mountView();

    expect(document.body.textContent).toContain('Acción 1');
    expect(document.body.querySelector('[data-test="gap-empty"]')).not.toBeNull();
  });
});
