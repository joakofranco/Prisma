import { describe, it, expect, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { ReportVerification } from '@/types';

const reportsService = vi.hoisted(() => ({ verify: vi.fn() }));
vi.mock('@/services/resources', () => ({ reportsService }));

import ReportVerifier from '@/components/reports/ReportVerifier.vue';

const verification = (extra: Partial<ReportVerification> = {}): ReportVerification => ({
  sealingAvailable: true,
  signed: true,
  sealedByPrisma: true,
  signedByUser: true,
  intact: true,
  registered: true,
  registeredAt: '2026-11-19T20:00:00Z',
  registeredUserId: 'u-1',
  userMatchesRegistry: true,
  valid: true,
  sha256: 'abc123',
  message: 'Documento íntegro: sellado por PRISMA y firmado por el usuario que lo generó.',
  signatures: [
    {
      role: 'PRISMA',
      signerName: 'PRISMA - Sellado de reportes',
      signerEmail: null,
      signerUserId: null,
      signedAt: '2026-11-19T20:00:00Z',
      integrityValid: true,
      trusted: true,
      coversWholeDocument: false,
    },
    {
      role: 'USER',
      signerName: 'Ana Pérez',
      signerEmail: 'ana@org.uy',
      signerUserId: 'u-1',
      signedAt: '2026-11-19T20:00:01Z',
      integrityValid: true,
      trusted: true,
      coversWholeDocument: true,
    },
  ],
  ...extra,
});

async function pickAndRun(wrapper: ReturnType<typeof mount>, file = new File(['%PDF'], 'r.pdf')) {
  const input = wrapper.find('[data-test="verifier-file"]');
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true });
  await input.trigger('change');
  await wrapper.find('[data-test="verifier-run"]').trigger('click');
  await flushPromises();
}

describe('ReportVerifier (RF-REP-06)', () => {
  beforeEach(() => {
    reportsService.verify.mockReset();
  });

  it('no deja verificar sin elegir un PDF', () => {
    const wrapper = mount(ReportVerifier);

    expect(wrapper.find('[data-test="verifier-run"]').attributes('disabled')).toBeDefined();
    expect(wrapper.find('[data-test="verifier-result"]').exists()).toBe(false);
  });

  it('un documento válido muestra el veredicto y las cuatro comprobaciones en verde', async () => {
    reportsService.verify.mockResolvedValue({ data: verification() });
    const wrapper = mount(ReportVerifier);

    await pickAndRun(wrapper);

    expect(reportsService.verify).toHaveBeenCalledOnce();
    expect(wrapper.find('[data-test="verifier-verdict"]').text()).toContain('Documento válido.');
    for (const key of ['prisma', 'user', 'intact', 'registered']) {
      expect(wrapper.find(`[data-test="check-${key}"]`).attributes('data-ok')).toBe('true');
    }
    expect(wrapper.find('[data-test="check-user"]').text()).toContain('Ana Pérez (ana@org.uy)');
    expect(wrapper.findAll('[data-test="verifier-signature"]')).toHaveLength(2);
    expect(wrapper.find('[data-test="verifier-hash"]').text()).toBe('abc123');
  });

  it('un documento modificado marca la integridad como fallida', async () => {
    reportsService.verify.mockResolvedValue({
      data: verification({
        valid: false,
        intact: false,
        message: 'El documento fue modificado después de firmarse.',
      }),
    });
    const wrapper = mount(ReportVerifier);

    await pickAndRun(wrapper);

    expect(wrapper.find('[data-test="verifier-verdict"]').text()).toContain('Documento no válido.');
    expect(wrapper.find('[data-test="check-intact"]').attributes('data-ok')).toBe('false');
    expect(wrapper.find('[data-test="check-prisma"]').attributes('data-ok')).toBe('true');
  });

  it('un PDF sin firmas marca todo como no cumplido', async () => {
    reportsService.verify.mockResolvedValue({
      data: verification({
        signed: false,
        sealedByPrisma: false,
        signedByUser: false,
        intact: false,
        registered: false,
        registeredAt: null,
        valid: false,
        message: 'El PDF no tiene firmas: no fue sellado por PRISMA.',
        signatures: [],
      }),
    });
    const wrapper = mount(ReportVerifier);

    await pickAndRun(wrapper);

    expect(wrapper.findAll('[data-ok="false"]')).toHaveLength(4);
    expect(wrapper.find('[data-test="verifier-signatures"]').exists()).toBe(false);
  });

  it('muestra el error del servidor si la verificación falla', async () => {
    reportsService.verify.mockRejectedValue({
      response: { data: { message: 'El archivo no es un PDF válido' } },
    });
    const wrapper = mount(ReportVerifier);

    await pickAndRun(wrapper);

    expect(wrapper.find('[data-test="verifier-error"]').text()).toContain('no es un PDF válido');
    expect(wrapper.find('[data-test="verifier-result"]').exists()).toBe(false);
  });

  it('un PDF de más de 50 MB ni se envía', async () => {
    const wrapper = mount(ReportVerifier);
    const big = new File(['x'], 'grande.pdf');
    Object.defineProperty(big, 'size', { value: 51 * 1024 * 1024 });

    await pickAndRun(wrapper, big);

    expect(reportsService.verify).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="verifier-error"]').text()).toContain('50 MB');
  });
});
