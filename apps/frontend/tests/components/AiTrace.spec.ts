import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const authStore = vi.hoisted(() => ({
  isAdmin: true,
  isAuthenticated: true,
  user: { organizationIds: ['org-1'] } as { organizationIds: string[] } | null,
}));
const organizationsService = vi.hoisted(() => ({ getById: vi.fn() }));

vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStore }));
vi.mock('@/services/resources', () => ({ organizationsService }));

import AiTraceButton from '@/components/ai/AiTraceButton.vue';
import ResearchModeBanner from '@/components/layout/ResearchModeBanner.vue';

const guideTrace = {
  id: 't1',
  kind: 'GUIDE',
  seed: 4242,
  config: { model: 'llama3:8b', model_digest: 'abc123', num_ctx: 16384 },
  prompt: 'PROMPT-ENVIADO',
  response: 'RESPUESTA-CRUDA',
  thinking: 'RAZONAMIENTO',
  retrieval: [{ source: 'mcu.pdf', score: 0.4 }],
  metrics: {},
  createdAt: '2026-11-20T10:00:00Z',
};

const q = (testid: string) => document.body.querySelector(`[data-testid="${testid}"]`);

describe('AiTraceButton («Cómo razonó la IA»)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    authStore.isAdmin = true;
  });

  const mountButton = (load: () => Promise<{ data: unknown }>) =>
    mount(AiTraceButton, {
      props: { load: load as never },
      attachTo: document.body,
    });

  it('solo lo ve PRISMA_ADMIN', () => {
    authStore.isAdmin = false;
    const wrapper = mountButton(vi.fn());

    expect(wrapper.find('[data-testid="ai-trace-button"]').exists()).toBe(false);
  });

  it('muestra semilla, configuración, consulta, razonamiento, respuesta y fragmentos', async () => {
    const load = vi.fn().mockResolvedValue({ data: guideTrace });
    const wrapper = mountButton(load);

    await wrapper.find('[data-testid="ai-trace-button"]').trigger('click');
    await flushPromises();

    expect(load).toHaveBeenCalledTimes(1);
    expect(q('trace-kind')?.textContent).toContain('Guía de mejora');
    expect(q('trace-seed')?.textContent).toContain('4242');
    expect(document.body.textContent).toContain('abc123');
    expect(q('trace-prompt')?.textContent).toContain('PROMPT-ENVIADO');
    expect(q('trace-thinking')?.textContent).toContain('RAZONAMIENTO');
    expect(q('trace-response')?.textContent).toContain('RESPUESTA-CRUDA');
    expect(q('trace-retrieval')?.textContent).toContain('score: 0.4');
  });

  it('avisa que la semilla no reproduce el texto si la temperatura no estaba fija (3.7)', async () => {
    const load = vi.fn().mockResolvedValue({
      data: {
        ...guideTrace,
        config: {
          ...guideTrace.config,
          temperature: 0.8,
          temperature_source: 'ollama (por defecto)',
          temperature_fixed: false,
        },
      },
    });
    const wrapper = mountButton(load);

    await wrapper.find('[data-testid="ai-trace-button"]').trigger('click');
    await flushPromises();

    const warning = q('trace-temperature-warning');
    expect(warning?.textContent).toContain('la semilla no reproduce el mismo texto');
    expect(warning?.textContent).toContain('0.8');
    expect(document.body.textContent).toContain('temperature_source');
  });

  it('con la temperatura fija no muestra el aviso de la semilla', async () => {
    const load = vi.fn().mockResolvedValue({
      data: {
        ...guideTrace,
        config: { ...guideTrace.config, temperature: 0.1, temperature_fixed: true },
      },
    });
    const wrapper = mountButton(load);

    await wrapper.find('[data-testid="ai-trace-button"]').trigger('click');
    await flushPromises();

    expect(q('trace-temperature-warning')).toBeNull();
  });

  it('en tareas con evidencia no muestra consulta ni respuesta, solo ids y puntajes', async () => {
    const load = vi.fn().mockResolvedValue({
      data: {
        ...guideTrace,
        kind: 'CITATIONS',
        prompt: null,
        response: null,
        thinking: null,
        retrieval: [{ fragmentId: 'ev1::0', score: 0.71 }],
      },
    });
    const wrapper = mountButton(load);

    await wrapper.find('[data-testid="ai-trace-button"]').trigger('click');
    await flushPromises();

    expect(q('trace-prompt')).toBeNull();
    expect(q('trace-response')).toBeNull();
    expect(q('trace-retrieval')?.textContent).toContain('fragmentId: ev1::0');
  });

  it('si no hay traza (404) explica que el modo estaba apagado', async () => {
    const load = vi.fn().mockRejectedValue({ response: { status: 404 } });
    const wrapper = mountButton(load);

    await wrapper.find('[data-testid="ai-trace-button"]').trigger('click');
    await flushPromises();

    expect(q('trace-error')?.textContent).toContain('modo investigación estaba apagado');
  });

  it('ante otro error avisa que no se pudo cargar', async () => {
    const load = vi.fn().mockRejectedValue(new Error('boom'));
    const wrapper = mountButton(load);

    await wrapper.find('[data-testid="ai-trace-button"]').trigger('click');
    await flushPromises();

    expect(q('trace-error')?.textContent).toContain('No se pudo cargar la traza');
  });
});

describe('ResearchModeBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authStore.isAdmin = false;
    authStore.isAuthenticated = true;
    authStore.user = { organizationIds: ['org-1'] };
  });

  it('avisa mientras la organización tiene el modo investigación encendido', async () => {
    organizationsService.getById.mockResolvedValue({ data: { researchMode: true } });
    const wrapper = mount(ResearchModeBanner);
    await flushPromises();

    expect(organizationsService.getById).toHaveBeenCalledWith('org-1');
    expect(wrapper.find('[data-testid="research-mode-banner"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Modo investigación activo');
  });

  it('con el modo apagado no muestra nada', async () => {
    organizationsService.getById.mockResolvedValue({ data: { researchMode: false } });
    const wrapper = mount(ResearchModeBanner);
    await flushPromises();

    expect(wrapper.find('[data-testid="research-mode-banner"]').exists()).toBe(false);
  });

  it('un administrador (sin organización propia) no consulta ni muestra el aviso', async () => {
    authStore.isAdmin = true;
    authStore.user = { organizationIds: [] };
    const wrapper = mount(ResearchModeBanner);
    await flushPromises();

    expect(organizationsService.getById).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="research-mode-banner"]').exists()).toBe(false);
  });

  it('si no se puede consultar la organización no muestra el aviso ni falla', async () => {
    organizationsService.getById.mockRejectedValue(new Error('403'));
    const wrapper = mount(ResearchModeBanner);
    await flushPromises();

    expect(wrapper.find('[data-testid="research-mode-banner"]').exists()).toBe(false);
  });
});
