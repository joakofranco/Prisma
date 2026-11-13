import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const evaluationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const organizationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const reportsService = vi.hoisted(() => ({
  generatePDF: vi.fn(),
  generateExcel: vi.fn(),
  generateExecutivePDF: vi.fn(),
  generateTechnicalPDF: vi.fn(),
  generatePreliminaryPDF: vi.fn(),
  generatePreliminaryExcel: vi.fn(),
}));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
const saveBlob = vi.hoisted(() => vi.fn());

vi.mock('@/services/resources', () => ({
  evaluationsService,
  organizationsService,
  reportsService,
}));
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

const evaluation = (
  id: string,
  status: string,
  extra: Record<string, unknown> = {},
): Record<string, unknown> => ({
  id,
  name: `Evaluación ${id}`,
  organizationId: 'org-1',
  organizationName: 'Intendencia de Rivera',
  status,
  globalMaturity: 2,
  approvedAt: status === 'IN_AUDIT' ? null : '2026-10-15T10:00:00Z',
  createdAt: '2026-09-01T00:00:00Z',
  ...extra,
});

const ORGS = [
  {
    id: 'org-1',
    name: 'Intendencia de Rivera',
    identifier: '21-616658-001-4',
    identifierIsRut: true,
    sector: 'Público',
    size: 'Grande',
    responsibleName: 'Ana Pérez',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'org-2',
    name: 'Hospital Central',
    identifier: null,
    identifierIsRut: false,
    sector: 'Salud',
    size: 'Mediana',
    responsibleName: null,
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
  },
];

function mockEvaluations(byStatus: Record<string, unknown[]>) {
  evaluationsService.getAll.mockImplementation(async (params: { status: string }) => ({
    data: { data: byStatus[params.status] ?? [] },
  }));
}

async function mountView() {
  const wrapper = mount(ReportsView, { attachTo: document.body });
  await flushPromises();
  return wrapper;
}

const q = <T extends Element = HTMLElement>(sel: string) =>
  document.body.querySelector<T & HTMLElement>(sel);
const qa = (sel: string) => Array.from(document.body.querySelectorAll<HTMLElement>(sel));

async function openMenuOf(rowIndex = 0) {
  qa('[data-testid="download-button"]')[rowIndex].click();
  await flushPromises();
}

async function pick(format: 'executive' | 'pdf' | 'excel' | 'technical') {
  q(`[data-testid="format-${format}"]`)!.click();
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  mockEvaluations({ APPROVED: [evaluation('a1', 'APPROVED')] });
  organizationsService.getAll.mockResolvedValue({ data: { data: ORGS } });
  reportsService.generateExecutivePDF.mockResolvedValue({ data: new Blob(['%PDF-']) });
  reportsService.generatePDF.mockResolvedValue({ data: new Blob(['%PDF-']) });
  reportsService.generateExcel.mockResolvedValue({ data: new Blob(['PK']) });
  reportsService.generateTechnicalPDF.mockResolvedValue({ data: new Blob(['%PDF-']) });
});

describe('ReportsView: evaluaciones archivadas (PRI-367)', () => {
  it('lista las aprobadas y las archivadas que fueron aprobadas', async () => {
    mockEvaluations({
      APPROVED: [evaluation('a1', 'APPROVED')],
      ARCHIVED: [evaluation('x1', 'ARCHIVED')],
    });
    await mountView();

    const rows = qa('[data-testid="report-row"]');
    expect(rows).toHaveLength(2);
    expect(document.body.textContent).toContain('Evaluación x1');
    expect(document.body.textContent).toContain('Archivada');
  });

  it('no lista las archivadas que se descartaron sin aprobar', async () => {
    mockEvaluations({
      APPROVED: [],
      ARCHIVED: [evaluation('x1', 'ARCHIVED', { approvedAt: null })],
    });
    await mountView();

    expect(qa('[data-testid="report-row"]')).toHaveLength(0);
    expect(q('[data-testid="reports-empty"]')?.textContent).toContain(
      'No hay evaluaciones aprobadas ni archivadas',
    );
  });

  it('descarga el reporte de una archivada en el formato elegido', async () => {
    mockEvaluations({ APPROVED: [], ARCHIVED: [evaluation('x1', 'ARCHIVED')] });
    await mountView();

    await openMenuOf(0);
    await pick('pdf');

    expect(reportsService.generatePDF).toHaveBeenCalledWith('x1');
    expect(saveBlob.mock.calls[0][1]).toBe('reporte-prisma-x1.pdf');
    expect(notification.success).toHaveBeenCalledWith('Reporte PDF descargado');
  });

  it('los filtros separan aprobadas y archivadas', async () => {
    mockEvaluations({
      APPROVED: [evaluation('a1', 'APPROVED')],
      ARCHIVED: [evaluation('x1', 'ARCHIVED')],
    });
    await mountView();

    q('[data-testid="filter-ARCHIVED"]')!.click();
    await flushPromises();
    expect(qa('[data-testid="report-row"]')).toHaveLength(1);
    expect(document.body.textContent).toContain('Evaluación x1');
    expect(document.body.textContent).not.toContain('Evaluación a1');

    q('[data-testid="filter-APPROVED"]')!.click();
    await flushPromises();
    expect(document.body.textContent).toContain('Evaluación a1');
    expect(document.body.textContent).not.toContain('Evaluación x1');
  });
});

describe('ReportsView: listado por organización (PRI-367)', () => {
  it('agrupa por organización y muestra sus datos relevantes', async () => {
    mockEvaluations({
      APPROVED: [
        evaluation('a1', 'APPROVED'),
        evaluation('b1', 'APPROVED', {
          organizationId: 'org-2',
          organizationName: 'Hospital Central',
        }),
      ],
    });
    await mountView();

    const groups = qa('[data-testid="org-group"]');
    expect(groups).toHaveLength(2);
    // Orden alfabético: Hospital Central antes que Intendencia de Rivera.
    expect(groups[0].textContent).toContain('Hospital Central');
    expect(groups[0].textContent).toContain('Salud · Mediana');
    expect(groups[0].textContent).not.toContain('RUT');
    expect(groups[1].querySelector('[data-testid="org-details"]')?.textContent).toContain(
      'RUT 21-616658-001-4 · Público · Grande · Responsable: Ana Pérez',
    );
  });

  it('un identificador que no es RUT se rotula «Identificador»', async () => {
    organizationsService.getAll.mockResolvedValue({
      data: {
        data: [{ ...ORGS[0], identifier: 'NIT-0009', identifierIsRut: false }, ORGS[1]],
      },
    });
    mockEvaluations({ APPROVED: [evaluation('a1', 'APPROVED')] });
    await mountView();

    const details = qa('[data-testid="org-details"]')[0].textContent;
    expect(details).toContain('Identificador NIT-0009 · Público');
    expect(details).not.toContain('RUT');
  });

  it('cada evaluación muestra la fecha de aprobación y la madurez', async () => {
    await mountView();

    const row = q('[data-testid="report-row"]')!;
    expect(row.textContent).toContain('Aprobada el 15/10/2026');
    expect(row.textContent).toContain('Nivel 2');
  });

  it('el buscador filtra por organización o por evaluación', async () => {
    mockEvaluations({
      APPROVED: [
        evaluation('a1', 'APPROVED'),
        evaluation('b1', 'APPROVED', {
          organizationId: 'org-2',
          organizationName: 'Hospital Central',
        }),
      ],
    });
    await mountView();

    const input = q<HTMLInputElement>('[data-testid="reports-search"]')! as HTMLInputElement;
    input.value = 'hospital';
    input.dispatchEvent(new Event('input'));
    await flushPromises();

    expect(qa('[data-testid="org-group"]')).toHaveLength(1);
    expect(document.body.textContent).toContain('Hospital Central');

    input.value = 'zzz';
    input.dispatchEvent(new Event('input'));
    await flushPromises();
    expect(q('[data-testid="reports-empty"]')?.textContent).toContain('Ninguna evaluación');
  });

  it('si no se pueden leer las organizaciones igual lista con el nombre', async () => {
    organizationsService.getAll.mockRejectedValue(new Error('403'));
    await mountView();

    expect(qa('[data-testid="org-group"]')[0].textContent).toContain('Intendencia de Rivera');
    expect(q('[data-testid="org-details"]')).toBeNull();
  });
});

describe('ReportsView: menú «Descargar» con formatos', () => {
  it('ofrece los tres formatos y se cierra al elegir uno', async () => {
    await mountView();

    await openMenuOf(0);
    const menu = q('[data-testid="formats-menu"]')!;
    expect(menu.getAttribute('role')).toBe('menu');
    expect(menu.textContent).toContain('Reporte ejecutivo');
    expect(menu.textContent).toContain('Planilla Excel');
    expect(menu.textContent).toContain('Descargar no modifica la evaluación');

    await pick('excel');
    expect(q('[data-testid="formats-menu"]')).toBeNull();
    expect(reportsService.generateExcel).toHaveBeenCalledWith('a1');
    expect(saveBlob.mock.calls[0][1]).toBe('reporte-prisma-a1.xlsx');
  });

  it('el ejecutivo se guarda como PDF', async () => {
    await mountView();

    await openMenuOf(0);
    await pick('executive');

    expect(reportsService.generateExecutivePDF).toHaveBeenCalledWith('a1');
    expect(saveBlob.mock.calls[0][1]).toBe('reporte-ejecutivo-a1.pdf');
    expect(notification.success).toHaveBeenCalledWith('Reporte ejecutivo descargado');
  });

  it('un clic afuera cierra el menú', async () => {
    await mountView();

    await openMenuOf(0);
    expect(q('[data-testid="formats-menu"]')).not.toBeNull();
    document.body.click();
    await flushPromises();

    expect(q('[data-testid="formats-menu"]')).toBeNull();
  });

  it('el botón indica si el menú está abierto', async () => {
    await mountView();
    const button = q('[data-testid="download-button"]')!;
    expect(button.getAttribute('aria-expanded')).toBe('false');

    await openMenuOf(0);

    expect(button.getAttribute('aria-expanded')).toBe('true');
  });
});

describe('ReportsView: reporte técnico (PRI-310, RF-REP-02)', () => {
  it('el menú ofrece el reporte técnico con su descripción', async () => {
    await mountView();

    await openMenuOf(0);
    const item = q('[data-testid="format-technical"]')!;

    expect(item.getAttribute('role')).toBe('menuitem');
    expect(item.textContent).toContain('Reporte técnico');
    expect(item.textContent).toContain('cumplimiento, veredicto, observaciones y evidencias');
  });

  it('descarga el reporte técnico de la evaluación como PDF', async () => {
    await mountView();

    await openMenuOf(0);
    await pick('technical');

    expect(reportsService.generateTechnicalPDF).toHaveBeenCalledWith('a1');
    expect(saveBlob.mock.calls[0][1]).toBe('reporte-tecnico-a1.pdf');
    expect((saveBlob.mock.calls[0][0] as Blob).type).toBe('application/pdf');
    expect(notification.success).toHaveBeenCalledWith('Reporte técnico descargado');
  });

  it('también está para las archivadas que fueron aprobadas', async () => {
    mockEvaluations({ APPROVED: [], ARCHIVED: [evaluation('x1', 'ARCHIVED')] });
    await mountView();

    await openMenuOf(0);
    await pick('technical');

    expect(reportsService.generateTechnicalPDF).toHaveBeenCalledWith('x1');
  });

  it('si el servidor lo rechaza muestra su mensaje', async () => {
    const message = "El reporte técnico sólo puede exportarse con la evaluación 'Aprobada'";
    reportsService.generateTechnicalPDF.mockRejectedValue({
      response: { data: new Blob([JSON.stringify({ message })], { type: 'application/json' }) },
    });
    await mountView();

    await openMenuOf(0);
    await pick('technical');

    await vi.waitFor(() => expect(notification.error).toHaveBeenCalledWith(message));
    expect(saveBlob).not.toHaveBeenCalled();
  });
});

describe('ReportsView: errores al generar (PRI-309)', () => {
  it('si el servidor lo rechaza muestra su mensaje (el cuerpo de error llega como Blob)', async () => {
    const message =
      "El reporte final y el ejecutivo sólo pueden exportarse con la evaluación 'Aprobada'";
    reportsService.generateExecutivePDF.mockRejectedValue({
      response: { data: new Blob([JSON.stringify({ message })], { type: 'application/json' }) },
    });
    await mountView();

    await openMenuOf(0);
    await pick('executive');

    await vi.waitFor(() => expect(notification.error).toHaveBeenCalledWith(message));
    expect(saveBlob).not.toHaveBeenCalled();
  });

  it('si el error no trae un mensaje legible usa uno genérico', async () => {
    reportsService.generatePDF.mockRejectedValue({
      response: { data: new Blob(['<html>502</html>']) },
    });
    await mountView();

    await openMenuOf(0);
    await pick('pdf');

    await vi.waitFor(() =>
      expect(notification.error).toHaveBeenCalledWith('Error al generar el reporte PDF'),
    );
  });

  it('un fallo de red también usa el mensaje genérico', async () => {
    reportsService.generateExecutivePDF.mockRejectedValue(new Error(''));
    await mountView();

    await openMenuOf(0);
    await pick('executive');

    expect(notification.error).toHaveBeenCalledWith('Error al generar el reporte ejecutivo');
  });
});

describe('ReportsView: informe preliminar de auditoría', () => {
  it('sigue disponible para las evaluaciones en auditoría, sin ofrecerles el menú', async () => {
    mockEvaluations({ APPROVED: [], IN_AUDIT: [evaluation('p1', 'IN_AUDIT')] });
    await mountView();

    expect(document.body.textContent).toContain('Informe Preliminar de Auditoría');
    expect(qa('[data-testid="download-button"]')).toHaveLength(0);
  });
});
