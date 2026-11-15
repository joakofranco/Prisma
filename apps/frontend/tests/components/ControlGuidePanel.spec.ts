import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const catalogService = vi.hoisted(() => ({
  getVersions: vi.fn(),
  getGuideItems: vi.fn(),
  exportGuide: vi.fn(),
  importGuide: vi.fn(),
  saveGuide: vi.fn(),
  deleteGuides: vi.fn(),
}));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
const files = vi.hoisted(() => ({ saveBlob: vi.fn(), blobErrorMessage: vi.fn() }));
vi.mock('@/services/resources', () => ({ catalogService }));
vi.mock('@/stores/notification', () => ({ useNotificationStore: () => notification }));
vi.mock('@/utils/files', () => files);

import ControlGuidePanel from '@/components/settings/ControlGuidePanel.vue';

const item = (code: string, description: string, withGuide = true) => ({
  id: `id-${code}`,
  code,
  description,
  targetLevel: 1,
  requirementCode: 'PS.1',
  interpretation: withGuide ? `Interpretación de ${code}` : null,
  example: withGuide ? `Ejemplo de ${code}` : null,
  evidence: withGuide ? `Evidencia de ${code}` : null,
});

const rows = () => [
  item('PS.1-1', 'Existe una política de seguridad aprobada'),
  item('PS.1-2', 'La política es difundida al personal'),
  item('GA.1-1', 'Se mantiene un inventario de activos', false),
];

async function mountPanel(data = rows()) {
  catalogService.getVersions.mockResolvedValue({ data: { versions: ['5.0'] } });
  catalogService.getGuideItems.mockResolvedValue({ data });
  const wrapper = mount(ControlGuidePanel, { attachTo: document.body });
  await flushPromises();
  return wrapper;
}

const byId = (testId: string) =>
  document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`);

async function type(testId: string, value: string) {
  const input = byId(testId) as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await flushPromises();
}

async function click(testId: string) {
  byId(testId)!.click();
  await flushPromises();
}

async function chooseFile(name: string) {
  const input = byId('guide-file-input') as HTMLInputElement;
  Object.defineProperty(input, 'files', {
    value: [new File(['x'], name)],
    configurable: true,
  });
  input.dispatchEvent(new Event('change'));
  await flushPromises();
}

describe('ControlGuidePanel: guía de los controles', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
  });

  it('lista los controles y resume cuántos tienen guía', async () => {
    await mountPanel();

    expect(catalogService.getGuideItems).toHaveBeenCalledWith('5.0');
    expect(byId('guide-PS.1-1')).not.toBeNull();
    expect(byId('guide-summary')?.textContent).toContain('2 con guía · 1 sin guía');
  });

  it('filtra por código o texto, sin distinguir mayúsculas ni tildes', async () => {
    await mountPanel();

    await type('guide-search', 'INVENTARIO');
    expect(byId('guide-GA.1-1')).not.toBeNull();
    expect(byId('guide-PS.1-1')).toBeNull();

    await type('guide-search', 'ps.1-2');
    expect(byId('guide-PS.1-2')).not.toBeNull();
    expect(byId('guide-GA.1-1')).toBeNull();

    await type('guide-search', 'no existe');
    expect(byId('guide-empty')).not.toBeNull();
  });

  it('filtra por estado con los botones Con guía y Sin guía, que muestran su contador', async () => {
    await mountPanel();

    expect(byId('guide-status-all')?.textContent).toContain('3');
    expect(byId('guide-status-with')?.textContent).toContain('2');
    expect(byId('guide-status-without')?.textContent).toContain('1');

    await click('guide-status-without');
    expect(byId('guide-GA.1-1')).not.toBeNull();
    expect(byId('guide-PS.1-1')).toBeNull();

    await click('guide-status-with');
    expect(byId('guide-PS.1-1')).not.toBeNull();
    expect(byId('guide-GA.1-1')).toBeNull();

    await click('guide-status-all');
    expect(byId('guide-GA.1-1')).not.toBeNull();
    expect(byId('guide-PS.1-1')).not.toBeNull();
  });

  it('muestra la cobertura de la guía como barra de progreso', async () => {
    await mountPanel();

    const bar = document.body.querySelector('[role="progressbar"]') as HTMLElement;
    expect(bar.getAttribute('aria-valuenow')).toBe('67');
    expect(document.body.textContent).toContain('2 de 3 controles con guía');
  });

  it('carga un archivo, muestra el resultado y recarga el listado', async () => {
    await mountPanel();
    catalogService.importGuide.mockResolvedValue({
      data: {
        parsed: 3,
        updated: 2,
        notInCatalog: ['X-1'],
        incomplete: ['PS.1-2'],
        catalogWithoutGuide: 1,
      },
    });

    await chooseFile('guia.csv');

    expect((catalogService.importGuide.mock.calls[0][1] as File).name).toBe('guia.csv');
    expect(catalogService.importGuide.mock.calls[0][0]).toBe('5.0');
    expect(catalogService.getGuideItems).toHaveBeenCalledTimes(2);
    const result = byId('guide-import-result')?.textContent ?? '';
    expect(result).toContain('Se actualizaron 2 de 3');
    expect(result).toContain('X-1');
    expect(result).toContain('PS.1-2');
    expect(notification.success).toHaveBeenCalledWith('2 controles actualizados');
  });

  it('informa el motivo cuando el servidor rechaza el archivo', async () => {
    await mountPanel();
    catalogService.importGuide.mockRejectedValue({
      response: { data: { message: 'Formato no soportado.' } },
    });

    await chooseFile('guia.xlsx');

    expect(notification.error).toHaveBeenCalledWith('Formato no soportado.');
  });

  it('descarga el CSV de la versión', async () => {
    await mountPanel();
    catalogService.exportGuide.mockResolvedValue({ data: 'codigo' });

    await click('guide-export');

    expect(catalogService.exportGuide).toHaveBeenCalledWith('5.0');
    expect(files.saveBlob).toHaveBeenCalledWith(expect.any(Blob), 'guia-controles-5.0.csv');
  });

  it('edita la guía de un control y reemplaza la fila con la respuesta', async () => {
    await mountPanel();
    catalogService.saveGuide.mockResolvedValue({
      data: {
        ...item('PS.1-1', 'Existe una política de seguridad aprobada'),
        interpretation: 'Nueva',
      },
    });

    await click('guide-edit-PS.1-1');
    await type('guide-interpretation-PS.1-1', 'Nueva');
    await click('guide-save-PS.1-1');

    expect(catalogService.saveGuide).toHaveBeenCalledWith('id-PS.1-1', {
      interpretation: 'Nueva',
      example: 'Ejemplo de PS.1-1',
      evidence: 'Evidencia de PS.1-1',
    });
    expect(byId('guide-PS.1-1')?.textContent).toContain('Nueva');
    expect(byId('guide-save-PS.1-1')).toBeNull();
  });

  it('permite escribir la guía de un control que no tenía', async () => {
    await mountPanel();

    await click('guide-edit-GA.1-1');

    expect((byId('guide-interpretation-GA.1-1') as HTMLTextAreaElement).value).toBe('');
  });

  it('elimina la guía de un control tras confirmar', async () => {
    await mountPanel();
    catalogService.deleteGuides.mockResolvedValue({ data: { removed: 1 } });
    const confirm = vi.spyOn(window, 'confirm');

    confirm.mockReturnValueOnce(false);
    await click('guide-delete-PS.1-1');
    expect(catalogService.deleteGuides).not.toHaveBeenCalled();

    confirm.mockReturnValueOnce(true);
    await click('guide-delete-PS.1-1');
    expect(catalogService.deleteGuides).toHaveBeenCalledWith(['id-PS.1-1']);
  });

  it('elimina varias guías seleccionadas de una vez', async () => {
    await mountPanel();
    catalogService.deleteGuides.mockResolvedValue({ data: { removed: 2 } });
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    expect((byId('guide-delete-selected') as HTMLButtonElement).disabled).toBe(true);
    await click('guide-check-PS.1-1');
    await click('guide-check-PS.1-2');
    expect(byId('guide-delete-selected')?.textContent).toContain('(2)');
    await click('guide-delete-selected');

    expect(catalogService.deleteGuides).toHaveBeenCalledWith(['id-PS.1-1', 'id-PS.1-2']);
    expect(notification.success).toHaveBeenCalledWith('2 guías eliminadas');
  });

  it('selecciona todos los controles filtrados', async () => {
    await mountPanel();

    await type('guide-search', 'política');
    await click('guide-select-all');

    expect(byId('guide-delete-selected')?.textContent).toContain('(2)');
  });

  it('muestra el error si no se pueden cargar los controles', async () => {
    catalogService.getVersions.mockResolvedValue({ data: { versions: ['5.0'] } });
    catalogService.getGuideItems.mockRejectedValue({
      response: { data: { message: 'No se pudieron cargar los controles' } },
    });
    mount(ControlGuidePanel, { attachTo: document.body });
    await flushPromises();

    expect(document.body.textContent).toContain('No se pudieron cargar los controles');
  });
});
