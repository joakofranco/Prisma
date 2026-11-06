import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const emailSettingsService = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }));
vi.mock('@/services/resources', () => ({ emailSettingsService }));

import EmailSettingsPanel from '@/components/settings/EmailSettingsPanel.vue';

const settings = (configured: boolean) => ({
  host: configured ? 'smtp.prisma.local' : '',
  port: configured ? 587 : null,
  from: configured ? 'no-reply@prisma.local' : '',
  fromDisplayName: null,
  authEnabled: false,
  username: null,
  starttls: true,
  ssl: false,
  configured,
});

describe('EmailSettingsPanel: aviso de SMTP faltante (PRI-364)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('sin SMTP avisa que el «¿Olvidaste tu contraseña?» no envía ningún correo', async () => {
    emailSettingsService.get.mockResolvedValue({ data: settings(false) });
    const wrapper = mount(EmailSettingsPanel);
    await flushPromises();

    const warning = wrapper.find('[data-testid="smtp-missing-warning"]');
    expect(warning.exists()).toBe(true);
    expect(warning.text()).toContain('no envía ningún correo');
    expect(wrapper.text()).toContain('Sin configurar');
  });

  it('con SMTP configurado no muestra el aviso', async () => {
    emailSettingsService.get.mockResolvedValue({ data: settings(true) });
    const wrapper = mount(EmailSettingsPanel);
    await flushPromises();

    expect(wrapper.find('[data-testid="smtp-missing-warning"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('Configurado');
  });
});
