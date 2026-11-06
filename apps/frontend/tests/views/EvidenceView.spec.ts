import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const evidenceService = vi.hoisted(() => ({
  getAllowedFormats: vi.fn(),
  getByEvaluation: vi.fn(),
  download: vi.fn(),
  upload: vi.fn(),
  reindex: vi.fn(),
  delete: vi.fn(),
}));
const evaluationsService = vi.hoisted(() => ({ getById: vi.fn(), getResponses: vi.fn() }));
const catalogService = vi.hoisted(() => ({ getByVersion: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));
const authState = vi.hoisted(() => ({ roles: ['PRISMA_ADMIN'] as string[] }));

vi.mock('@/services/resources', () => ({ evidenceService, evaluationsService, catalogService }));
vi.mock('@/composables/useUtils', () => ({ useNotification: () => notification }));
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    hasAnyRole: (...r: string[]) => r.some((x) => authState.roles.includes(x)),
  }),
}));
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { evaluationId: 'eval-1' } }) }));

import EvidenceView from '@/views/evidence/EvidenceView.vue';

const MB = 1024 * 1024;
const FORMATS = { data: { allowedExtensions: ['pdf', 'zip', 'png'], maxSizeBytes: 50 * MB } };

async function openUploadWithFile(fileName = 'acta.pdf', sizeBytes = 1) {
  const wrapper = mount(EvidenceView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  const openBtn = wrapper.findAll('button').find((b) => b.text().includes('Subir'));
  await openBtn!.trigger('click');
  await flushPromises();

  const input = document.body.querySelector<HTMLInputElement>('input[type="file"]')!;
  const file = new File(['x'], fileName, { type: 'application/octet-stream' });
  // No se crea un buffer real de decenas de MB: alcanza con que `size` diga el peso.
  Object.defineProperty(file, 'size', { value: sizeBytes });
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  return { wrapper, input };
}

function confirmButton() {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find(
    (b) => b.textContent?.trim() === 'Subir',
  )!;
}

describe('EvidenceView: carga de evidencia sin control (RF-EVI-01)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['PRISMA_ADMIN'];
    evidenceService.getAllowedFormats.mockResolvedValue(FORMATS);
    evidenceService.getByEvaluation.mockResolvedValue({ data: [] });
    evidenceService.upload.mockResolvedValue({ data: {} });
    evaluationsService.getById.mockResolvedValue({ data: { catalogVersion: '5.0' } });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
  });

  it('permite subir sin elegir un control y no envía controlId', async () => {
    await openUploadWithFile();
    confirmButton().click();
    await flushPromises();

    expect(evidenceService.upload).toHaveBeenCalledTimes(1);
    const form = evidenceService.upload.mock.calls[0][0] as FormData;
    expect(form.has('controlId')).toBe(false);
    expect(form.get('evaluationId')).toBe('eval-1');
    expect(notification.error).not.toHaveBeenCalled();
  });
});

describe('EvidenceView: descarga autenticada (PRI-286)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    evidenceService.getAllowedFormats.mockResolvedValue(FORMATS);
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [
        {
          id: 'ev-1',
          evaluationId: 'eval-1',
          fileName: 'acta.pdf',
          fileSize: 1,
          fileType: 'application/pdf',
          uploadedBy: 'u',
          uploadedAt: '2026-11-05T00:00:00Z',
          aiIndexed: true,
        },
      ],
    });
    evaluationsService.getById.mockResolvedValue({ data: { catalogVersion: '5.0' } });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
  });

  it('descarga por la API (con JWT) y no abre ningún enlace directo', async () => {
    authState.roles = ['ORG_RESPONSIBLE'];
    evidenceService.download = vi.fn().mockResolvedValue({ data: new Blob(['x']) });
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const open = vi.spyOn(window, 'open');
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    const wrapper = mount(EvidenceView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.find('button[title="Descargar evidencia"]').trigger('click');
    await flushPromises();

    expect(evidenceService.download).toHaveBeenCalledWith('ev-1');
    expect(open).not.toHaveBeenCalled();
  });

  it('muestra el botón de descarga al auditor', async () => {
    authState.roles = ['AUDITOR'];
    const wrapper = mount(EvidenceView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();

    expect(wrapper.find('button[title="Descargar evidencia"]').exists()).toBe(true);
  });

  it('no muestra el botón de descarga a roles sin permiso', async () => {
    authState.roles = ['INTERNAL_EVALUATOR'];
    const wrapper = mount(EvidenceView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();

    expect(wrapper.find('button[title="Descargar evidencia"]').exists()).toBe(false);
  });
});

describe('EvidenceView: formatos permitidos (PRI-281)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['PRISMA_ADMIN'];
    evidenceService.getAllowedFormats.mockResolvedValue(FORMATS);
    evidenceService.getByEvaluation.mockResolvedValue({ data: [] });
    evidenceService.upload.mockResolvedValue({ data: {} });
    evaluationsService.getById.mockResolvedValue({ data: { catalogVersion: '5.0' } });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
  });

  it('el campo de archivo usa la lista del backend en lugar de una escrita a mano', async () => {
    const { input } = await openUploadWithFile();

    expect(input.getAttribute('accept')).toBe('.pdf,.zip,.png');
    expect(document.body.textContent).toContain('Formatos aceptados: .pdf, .zip, .png');
  });

  it('muestra el tamaño máximo junto al campo', async () => {
    await openUploadWithFile();

    expect(document.body.textContent).toContain('Tamaño máximo: 50 MB');
  });

  it('un .exe se rechaza antes de enviar, sin llamar al servicio', async () => {
    await openUploadWithFile('virus.exe');
    confirmButton().click();
    await flushPromises();

    expect(evidenceService.upload).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain(
      'Tipo de archivo no permitido. Formatos aceptados: .pdf, .zip, .png',
    );
  });

  it('avisa apenas se elige el archivo, sin esperar a enviar', async () => {
    const { input } = await openUploadWithFile('virus.exe');
    input.dispatchEvent(new Event('change'));
    await flushPromises();

    expect(document.body.textContent).toContain('Tipo de archivo no permitido');
    expect(evidenceService.upload).not.toHaveBeenCalled();
  });

  it('un ZIP y una imagen sí se envían', async () => {
    for (const name of ['respaldo.zip', 'foto.PNG']) {
      document.body.innerHTML = '';
      evidenceService.upload.mockClear();
      await openUploadWithFile(name);
      confirmButton().click();
      await flushPromises();
      expect(evidenceService.upload).toHaveBeenCalledTimes(1);
    }
  });

  it('si no se pudo cargar la lista no restringe el selector y decide el servidor', async () => {
    evidenceService.getAllowedFormats.mockRejectedValue(new Error('boom'));
    vi.spyOn(console, 'error').mockImplementation(() => {});

    const { input } = await openUploadWithFile('virus.exe');

    expect(input.hasAttribute('accept')).toBe(false);
    confirmButton().click();
    await flushPromises();
    expect(evidenceService.upload).toHaveBeenCalledTimes(1);
  });
});

describe('EvidenceView: tamaño máximo (PRI-283)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['PRISMA_ADMIN'];
    evidenceService.getAllowedFormats.mockResolvedValue(FORMATS);
    evidenceService.getByEvaluation.mockResolvedValue({ data: [] });
    evidenceService.upload.mockResolvedValue({ data: {} });
    evaluationsService.getById.mockResolvedValue({ data: { catalogVersion: '5.0' } });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
  });

  it('un archivo justo en el límite se envía', async () => {
    await openUploadWithFile('acta.pdf', 50 * MB);
    confirmButton().click();
    await flushPromises();

    expect(evidenceService.upload).toHaveBeenCalledTimes(1);
  });

  it('un archivo un byte por encima se rechaza antes de enviar, con el límite en el mensaje', async () => {
    await openUploadWithFile('acta.pdf', 50 * MB + 1);
    confirmButton().click();
    await flushPromises();

    expect(evidenceService.upload).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain(
      'El archivo supera el tamaño máximo permitido de 50 MB',
    );
  });

  it('avisa del tamaño apenas se elige el archivo', async () => {
    const { input } = await openUploadWithFile('acta.pdf', 60 * MB);
    input.dispatchEvent(new Event('change'));
    await flushPromises();

    expect(document.body.textContent).toContain('supera el tamaño máximo');
    expect(evidenceService.upload).not.toHaveBeenCalled();
  });

  it('PRI-355: un .exe de 60 MB dice que el tipo no está permitido, no que es grande', async () => {
    await openUploadWithFile('virus.exe', 60 * MB);
    confirmButton().click();
    await flushPromises();

    expect(evidenceService.upload).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Tipo de archivo no permitido');
    expect(document.body.textContent).not.toContain('supera el tamaño máximo');
  });

  it('PRI-355: un .exe de 1 KB también dice que el tipo no está permitido', async () => {
    await openUploadWithFile('virus.exe', 1024);
    confirmButton().click();
    await flushPromises();

    expect(evidenceService.upload).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Tipo de archivo no permitido');
  });

  it('un 413 sin cuerpo (lo corta Nginx) muestra el límite en lugar del mensaje genérico', async () => {
    evidenceService.upload.mockRejectedValue({ response: { status: 413, data: '<html>' } });

    await openUploadWithFile('acta.pdf', 1);
    confirmButton().click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'El archivo supera el tamaño máximo permitido de 50 MB',
    );
  });

  it('un 413 con el mensaje del backend muestra ese mensaje', async () => {
    evidenceService.upload.mockRejectedValue({
      response: {
        status: 413,
        data: { message: 'El archivo supera el tamaño máximo permitido de 5 MB' },
      },
    });

    await openUploadWithFile('acta.pdf', 1);
    confirmButton().click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith(
      'El archivo supera el tamaño máximo permitido de 5 MB',
    );
  });

  it('si la lista no cargó (sin límite conocido) decide el servidor', async () => {
    evidenceService.getAllowedFormats.mockRejectedValue(new Error('boom'));
    vi.spyOn(console, 'error').mockImplementation(() => {});

    await openUploadWithFile('acta.pdf', 500 * MB);
    confirmButton().click();
    await flushPromises();

    expect(evidenceService.upload).toHaveBeenCalledTimes(1);
  });
});

describe('EvidenceView: IA y formatos que no se analizan (PRI-281)', () => {
  const base = {
    evaluationId: 'eval-1',
    fileSize: 1,
    uploadedBy: 'u',
    uploadedAt: '2026-11-05T00:00:00Z',
    aiIndexed: false,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['PRISMA_ADMIN'];
    evidenceService.getAllowedFormats.mockResolvedValue(FORMATS);
    evaluationsService.getById.mockResolvedValue({ data: { catalogVersion: '5.0' } });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
  });

  it('no ofrece «Analizar con IA» para un ZIP ni una imagen, pero sí para un PDF', async () => {
    evidenceService.getByEvaluation.mockResolvedValue({
      data: [
        { ...base, id: 'a', fileName: 'respaldo.zip', fileType: 'application/zip' },
        { ...base, id: 'b', fileName: 'foto.png', fileType: 'image/png' },
        { ...base, id: 'c', fileName: 'acta.pdf', fileType: 'application/pdf' },
      ],
    });

    const wrapper = mount(EvidenceView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();

    const analyze = wrapper.findAll('button').filter((b) => b.text().includes('Analizar con IA'));
    expect(analyze).toHaveLength(1);
    expect(wrapper.text().match(/Sin análisis de IA/g)).toHaveLength(2);
  });
});

describe('EvidenceView: vista previa (PRI-290)', () => {
  const base = {
    evaluationId: 'eval-1',
    fileSize: 1,
    uploadedBy: 'u',
    uploadedAt: '2026-11-05T00:00:00Z',
    aiIndexed: true,
  };
  const files = [
    { ...base, id: 'pdf', fileName: 'acta.pdf', fileType: 'application/pdf' },
    { ...base, id: 'png', fileName: 'foto.png', fileType: 'image/png' },
    { ...base, id: 'jpg', fileName: 'foto.jpg', fileType: 'image/jpeg' },
    {
      ...base,
      id: 'docx',
      fileName: 'a.docx',
      fileType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    },
    { ...base, id: 'txt', fileName: 'a.txt', fileType: 'text/plain' },
    { ...base, id: 'md', fileName: 'a.md', fileType: 'text/markdown' },
    { ...base, id: 'zip', fileName: 'a.zip', fileType: 'application/zip' },
  ];

  let urlCounter = 0;

  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    authState.roles = ['ORG_RESPONSIBLE'];
    urlCounter = 0;
    // jsdom no implementa createObjectURL: se simula con una URL distinta por llamada.
    URL.createObjectURL = vi.fn(() => `blob:preview-${++urlCounter}`);
    URL.revokeObjectURL = vi.fn();
    evidenceService.getAllowedFormats.mockResolvedValue(FORMATS);
    evidenceService.getByEvaluation.mockResolvedValue({ data: files });
    evidenceService.download.mockResolvedValue({ data: new Blob(['x']) });
    evaluationsService.getById.mockResolvedValue({ data: { catalogVersion: '5.0' } });
    evaluationsService.getResponses.mockResolvedValue({ data: [] });
    catalogService.getByVersion.mockResolvedValue({ data: { functions: [] } });
  });

  async function mountView() {
    const wrapper = mount(EvidenceView, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } },
    });
    await flushPromises();
    return wrapper;
  }

  const viewButtons = () =>
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button[title="Ver evidencia"]'));

  async function openPreviewOf(index: number) {
    viewButtons()[index].click();
    await flushPromises();
  }

  it('el botón «Ver» aparece para PDF e imágenes y no para DOCX, TXT, MD ni ZIP', async () => {
    await mountView();

    // Solo los tres primeros (pdf, png, jpg) tienen vista previa.
    expect(viewButtons()).toHaveLength(3);
  });

  it('no aparece para un rol sin permiso de descarga', async () => {
    authState.roles = ['INTERNAL_EVALUATOR'];
    await mountView();

    expect(viewButtons()).toHaveLength(0);
  });

  it('aparece para el auditor y el administrador (como la descarga)', async () => {
    for (const role of ['AUDITOR', 'PRISMA_ADMIN']) {
      document.body.innerHTML = '';
      authState.roles = [role];
      await mountView();
      expect(viewButtons()).toHaveLength(3);
    }
  });

  it('un PDF se pide con el endpoint de descarga y se muestra en un iframe, sin descargarlo', async () => {
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await mountView();
    await openPreviewOf(0);

    expect(evidenceService.download).toHaveBeenCalledWith('pdf');
    const frame = document.body.querySelector<HTMLIFrameElement>('iframe')!;
    expect(frame.getAttribute('src')).toBe('blob:preview-1');
    expect(frame.getAttribute('title')).toContain('acta.pdf');
    expect(document.body.querySelector('img')).toBeNull();
    // Ver no guarda ningún archivo: no se crea un <a download>.
    expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled();
  });

  it('el visor usa el tipo conocido de la evidencia, no la cabecera de la respuesta', async () => {
    await mountView();
    await openPreviewOf(0);

    const blob = (URL.createObjectURL as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0][0] as Blob;
    expect(blob.type).toBe('application/pdf');
  });

  it('una imagen se muestra en un <img> con su texto alternativo', async () => {
    await mountView();
    await openPreviewOf(1);

    const img = document.body.querySelector<HTMLImageElement>('img')!;
    expect(img.getAttribute('src')).toBe('blob:preview-1');
    expect(img.getAttribute('alt')).toContain('foto.png');
    expect(document.body.querySelector('iframe')).toBeNull();
  });

  it('el título del modal es el nombre del archivo y ofrece descargarlo', async () => {
    evidenceService.download.mockResolvedValue({ data: new Blob(['x']) });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await mountView();
    await openPreviewOf(0);

    expect(document.body.textContent).toContain('acta.pdf');
    const downloadBtn = Array.from(
      document.body.querySelectorAll<HTMLButtonElement>('button'),
    ).find((b) => b.textContent?.trim() === 'Descargar')!;
    downloadBtn.click();
    await flushPromises();

    // Una vez para la vista previa y otra para la descarga.
    expect(evidenceService.download).toHaveBeenCalledTimes(2);
  });

  it('al cerrar el modal se libera la URL del archivo', async () => {
    await mountView();
    await openPreviewOf(0);
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();

    const close = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.trim() === 'Cerrar',
    )!;
    close.click();
    await flushPromises();

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-1');
    expect(document.body.querySelector('iframe')).toBeNull();
  });

  it('al abrir otra evidencia se libera la URL de la anterior', async () => {
    await mountView();
    await openPreviewOf(0);
    // El modal cubre la lista: se abre la otra directamente desde el botón de la vista.
    await openPreviewOf(1);

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-1');
    expect(document.body.querySelector<HTMLImageElement>('img')!.getAttribute('src')).toBe(
      'blob:preview-2',
    );
  });

  it('si el archivo no se puede obtener muestra el error y no deja un visor vacío', async () => {
    evidenceService.download.mockRejectedValue({
      response: { data: { message: 'Su rol no puede descargar evidencias' } },
    });
    await mountView();
    await openPreviewOf(0);

    expect(document.body.textContent).toContain('Su rol no puede descargar evidencias');
    expect(document.body.querySelector('iframe')).toBeNull();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('si el error no trae mensaje usa uno genérico', async () => {
    evidenceService.download.mockRejectedValue(new Error(''));
    await mountView();
    await openPreviewOf(0);

    expect(document.body.textContent).toContain('No se pudo cargar la vista previa');
  });

  it('si se cierra el modal mientras baja, la URL creada se libera y no se muestra', async () => {
    let resolveDownload: (v: { data: Blob }) => void = () => {};
    evidenceService.download.mockReturnValue(
      new Promise((resolve) => {
        resolveDownload = resolve;
      }),
    );
    await mountView();
    viewButtons()[0].click();
    await flushPromises();

    const close = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.trim() === 'Cerrar',
    )!;
    close.click();
    await flushPromises();
    resolveDownload({ data: new Blob(['x']) });
    await flushPromises();

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-1');
    expect(document.body.querySelector('iframe')).toBeNull();
  });
});
