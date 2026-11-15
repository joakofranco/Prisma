import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const planDeadlinesService = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
vi.mock('@/services/resources', () => ({ planDeadlinesService }));
vi.mock('@/stores/notification', () => ({ useNotificationStore: () => notification }));

import PlanDeadlinesPanel from '@/components/settings/PlanDeadlinesPanel.vue';

const DEFAULTS = { highMonths: 3, mediumMonths: 6, lowMonths: 12 };

async function mountPanel(stored = DEFAULTS) {
  planDeadlinesService.get.mockResolvedValue({ data: stored });
  const wrapper = mount(PlanDeadlinesPanel, { attachTo: document.body });
  await flushPromises();
  return wrapper;
}

const input = (key: string) => document.body.querySelector<HTMLInputElement>(`#deadline-${key}`)!;

async function type(key: string, value: string) {
  const el = input(key);
  el.value = value;
  el.dispatchEvent(new Event('input'));
  await flushPromises();
}

const button = (text: string) =>
  Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
    b.textContent?.replace(/\s+/g, ' ').trim().startsWith(text),
  )!;

const preview = () => document.body.querySelector('[data-testid="deadlines-preview"]');

describe('PlanDeadlinesPanel: plazos por defecto (PRI-304, RF-PLN-03)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 9, 12, 0, 0));
    document.body.innerHTML = '';
    planDeadlinesService.update.mockImplementation(async (data) => ({ data }));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('carga los plazos guardados y muestra la vista previa de fechas', async () => {
    await mountPanel();

    expect(input('highMonths').value).toBe('3');
    expect(input('mediumMonths').value).toBe('6');
    expect(input('lowMonths').value).toBe('12');
    expect(preview()?.textContent).toContain('09/02/2027');
    expect(preview()?.textContent).toContain('09/05/2027');
    expect(preview()?.textContent).toContain('09/11/2027');
  });

  it('la vista previa se recalcula en vivo mientras se escribe', async () => {
    await mountPanel();

    await type('highMonths', '1');

    expect(preview()?.textContent).toContain('09/12/2026');
  });

  it('valida en el momento que Alta no supere a Media', async () => {
    await mountPanel();

    await type('highMonths', '8');

    expect(document.body.textContent).toContain('Alta no puede superar a Media (6 meses)');
    expect(preview()).toBeNull();
    expect(button('Guardar').disabled).toBe(true);
  });

  it('valida el rango de 1 a 60 meses', async () => {
    await mountPanel();

    await type('lowMonths', '72');
    expect(document.body.textContent).toContain('Debe estar entre 1 y 60 meses');

    await type('lowMonths', '0');
    expect(document.body.textContent).toContain('Debe estar entre 1 y 60 meses');
    expect(button('Guardar').disabled).toBe(true);
  });

  it('guarda los valores cambiados y avisa', async () => {
    await mountPanel();

    await type('highMonths', '1');
    button('Guardar').click();
    await flushPromises();

    expect(planDeadlinesService.update).toHaveBeenCalledWith({
      highMonths: 1,
      mediumMonths: 6,
      lowMonths: 12,
    });
    expect(notification.success).toHaveBeenCalledWith('Plazos por defecto guardados');
  });

  it('1 mes para todas las prioridades es válido', async () => {
    await mountPanel();

    await type('highMonths', '1');
    await type('mediumMonths', '1');
    await type('lowMonths', '1');

    expect(button('Guardar').disabled).toBe(false);
  });

  it('sin cambios no se puede guardar', async () => {
    await mountPanel();

    expect(button('Guardar').disabled).toBe(true);
  });

  it('«Restablecer» vuelve a 3 / 6 / 12', async () => {
    await mountPanel({ highMonths: 1, mediumMonths: 2, lowMonths: 4 });

    button('Restablecer').click();
    await flushPromises();

    expect(input('highMonths').value).toBe('3');
    expect(input('mediumMonths').value).toBe('6');
    expect(input('lowMonths').value).toBe('12');
  });

  it('si el backend rechaza el guardado muestra su mensaje', async () => {
    planDeadlinesService.update.mockRejectedValue({
      response: { data: { message: 'Solo el administrador puede cambiar los plazos' } },
    });
    await mountPanel();

    await type('highMonths', '2');
    button('Guardar').click();
    await flushPromises();

    expect(
      document.body.querySelector('[data-testid="deadlines-save-error"]')?.textContent,
    ).toContain('Solo el administrador puede cambiar los plazos');
  });
});
