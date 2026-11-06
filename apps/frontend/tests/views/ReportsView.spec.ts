import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const evaluationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const reportsService = vi.hoisted(() => ({
  generatePDF: vi.fn(),
  generateExcel: vi.fn(),
  generateExecutivePDF: vi.fn(),
  generatePreliminaryPDF: vi.fn(),
  generatePreliminaryExcel: vi.fn(),
}));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
const saveBlob = vi.hoisted(() => vi.fn());

vi.mock('@/services/resources', () => ({ evaluationsService, reportsService }));
vi.mock('@/composables/useUtils', () => ({ useNotification: () => notification }));
vi.mock('@/utils/files', () => ({ saveBlob }));

import ReportsView from '@/views/reports/ReportsView.vue';

// jsdom no implementa Blob.text() (los navegadores sí): se simula con FileReader para poder probar
// la lectura del mensaje de error que llega como Blob.
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

const evaluation = (id: string, status: string) => ({
  id,
  name: `Evaluación ${id}`,
  organizationName: 'Acme',
  status,
  createdAt: '2026-11-01T00:00:00Z',
});

function mockEvaluations(approved: unknown[], inAudit: unknown[] = []) {
  evaluationsService.getAll.mockImplementation(async (params: { status: string }) => ({
    data: { data: params.status === 'APPROVED' ? approved : inAudit },
  }));
}

async function mountView() {
  const wrapper = mount(ReportsView, { attachTo: document.body });
  await flushPromises();
  return wrapper;
}

const buttonsWith = (text: string) =>
  Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).filter((b) =>
    b.textContent?.includes(text),
  );

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  mockEvaluations([evaluation('a1', 'APPROVED')]);
  reportsService.generateExecutivePDF.mockResolvedValue({ data: new Blob(['%PDF-']) });
  reportsService.generatePDF.mockResolvedValue({ data: new Blob(['%PDF-']) });
});

describe('ReportsView: reporte ejecutivo (PRI-309)', () => {
  it('cada evaluación aprobada de la lista tiene su botón «Ejecutivo»', async () => {
    mockEvaluations([evaluation('a1', 'APPROVED'), evaluation('a2', 'APPROVED')]);
    await mountView();

    expect(document.body.querySelectorAll('[data-testid="executive-report-row"]')).toHaveLength(2);
  });

  it('el botón de la lista pide el ejecutivo de esa evaluación y lo guarda como PDF', async () => {
    mockEvaluations([evaluation('a1', 'APPROVED'), evaluation('a2', 'APPROVED')]);
    await mountView();

    document.body
      .querySelectorAll<HTMLButtonElement>('[data-testid="executive-report-row"]')[1]
      .click();
    await flushPromises();

    expect(reportsService.generateExecutivePDF).toHaveBeenCalledWith('a2');
    expect(saveBlob).toHaveBeenCalledTimes(1);
    expect(saveBlob.mock.calls[0][1]).toBe('reporte-ejecutivo-a2.pdf');
    expect(notification.success).toHaveBeenCalledWith('Reporte ejecutivo descargado');
  });

  it('al elegir una evaluación en el selector aparece el botón «Reporte ejecutivo»', async () => {
    const wrapper = await mountView();
    expect(document.body.querySelector('[data-testid="executive-report"]')).toBeNull();

    (wrapper.vm as unknown as { selectedEvaluation: string }).selectedEvaluation = 'a1';
    await flushPromises();
    const button = document.body.querySelector<HTMLButtonElement>(
      '[data-testid="executive-report"]',
    )!;
    expect(button.textContent).toContain('Reporte ejecutivo');
    button.click();
    await flushPromises();

    expect(reportsService.generateExecutivePDF).toHaveBeenCalledWith('a1');
  });

  it('no hay botones de reporte ejecutivo si no hay evaluaciones aprobadas', async () => {
    mockEvaluations([]);
    await mountView();

    expect(document.body.querySelector('[data-testid="executive-report-row"]')).toBeNull();
    expect(document.body.textContent).toContain('No hay evaluaciones aprobadas');
  });

  it('el ejecutivo no se ofrece para las evaluaciones en auditoría (solo el preliminar)', async () => {
    mockEvaluations([], [evaluation('p1', 'IN_AUDIT')]);
    await mountView();

    expect(document.body.querySelector('[data-testid="executive-report-row"]')).toBeNull();
    expect(document.body.textContent).toContain('Informe Preliminar de Auditoría');
  });

  it('si el servidor lo rechaza muestra su mensaje (el cuerpo de error llega como Blob)', async () => {
    const message =
      "El reporte final y el ejecutivo sólo pueden exportarse con la evaluación en estado 'Aprobada'";
    reportsService.generateExecutivePDF.mockRejectedValue({
      response: { data: new Blob([JSON.stringify({ message })], { type: 'application/json' }) },
    });
    await mountView();

    document.body.querySelector<HTMLButtonElement>('[data-testid="executive-report-row"]')!.click();
    await flushPromises();

    await vi.waitFor(() => expect(notification.error).toHaveBeenCalledWith(message));
    expect(saveBlob).not.toHaveBeenCalled();
  });

  it('si el error no trae un mensaje legible usa uno genérico', async () => {
    reportsService.generateExecutivePDF.mockRejectedValue({
      response: { data: new Blob(['<html>502</html>']) },
    });
    await mountView();

    document.body.querySelector<HTMLButtonElement>('[data-testid="executive-report-row"]')!.click();
    await flushPromises();

    await vi.waitFor(() =>
      expect(notification.error).toHaveBeenCalledWith('Error al generar el reporte ejecutivo'),
    );
  });

  it('un fallo de red también usa el mensaje genérico', async () => {
    reportsService.generateExecutivePDF.mockRejectedValue(new Error(''));
    await mountView();

    document.body.querySelector<HTMLButtonElement>('[data-testid="executive-report-row"]')!.click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('Error al generar el reporte ejecutivo');
  });
});

describe('ReportsView: textos que distinguen cada reporte (PRI-309)', () => {
  it('distingue el informe preliminar, el reporte final y el ejecutivo', async () => {
    mockEvaluations([evaluation('a1', 'APPROVED')], [evaluation('p1', 'IN_AUDIT')]);
    await mountView();
    const text = document.body.textContent ?? '';

    expect(text).toContain('Informe Preliminar de Auditoría');
    expect(buttonsWith('Final PDF')).toHaveLength(1);
    expect(buttonsWith('Final Excel')).toHaveLength(1);
    expect(buttonsWith('Ejecutivo')).toHaveLength(1);
    // Los botones del preliminar siguen siendo solo «PDF» y «Excel», dentro de su propia sección.
    expect(text).toContain('Reportes de Evaluaciones Aprobadas');
  });

  it('los reportes finales siguen funcionando', async () => {
    await mountView();

    buttonsWith('Final PDF')[0].click();
    await flushPromises();

    expect(reportsService.generatePDF).toHaveBeenCalledWith('a1');
  });
});
