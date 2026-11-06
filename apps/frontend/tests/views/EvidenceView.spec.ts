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
