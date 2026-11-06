import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const evidenceService = vi.hoisted(() => ({
  getByEvaluation: vi.fn(),
  upload: vi.fn(),
  reindex: vi.fn(),
  delete: vi.fn(),
}));
const evaluationsService = vi.hoisted(() => ({ getById: vi.fn(), getResponses: vi.fn() }));
const catalogService = vi.hoisted(() => ({ getByVersion: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }));

vi.mock('@/services/resources', () => ({ evidenceService, evaluationsService, catalogService }));
vi.mock('@/composables/useUtils', () => ({ useNotification: () => notification }));
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { evaluationId: 'eval-1' } }) }));

import EvidenceView from '@/views/evidence/EvidenceView.vue';

async function openUploadWithFile() {
  const wrapper = mount(EvidenceView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  const openBtn = wrapper.findAll('button').find((b) => b.text().includes('Subir'));
  await openBtn!.trigger('click');
  await flushPromises();

  const input = document.body.querySelector<HTMLInputElement>('input[type="file"]')!;
  const file = new File(['x'], 'acta.pdf', { type: 'application/pdf' });
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  return wrapper;
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

