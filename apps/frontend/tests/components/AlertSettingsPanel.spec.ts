import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const alertSettingsService = vi.hoisted(() => ({
  get: vi.fn(),
  update: vi.fn(),
  test: vi.fn(),
}));
vi.mock('@/services/resources', () => ({ alertSettingsService }));

import AlertSettingsPanel from '@/components/settings/AlertSettingsPanel.vue';

const settings = (over: Record<string, unknown> = {}) => ({
  recipients: 'admin@prisma.uy',
  smtpHost: 'mailpit',
  smtpPort: 1025,
  from: 'alertas@prisma.uy',
  authEnabled: false,
  username: null,
  passwordSet: false,
  requireTls: false,
  configured: false,
  updatedAt: '2026-11-14T12:00:00Z',
  warning: null,
  ...over,
});

async function mountPanel(data = settings()) {
  alertSettingsService.get.mockResolvedValue({ data });
  const wrapper = mount(AlertSettingsPanel);
  await flushPromises();
  return wrapper;
}

describe('AlertSettingsPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it('con los valores iniciales avisa que las alertas van a un buzón de pruebas', async () => {
    const wrapper = await mountPanel();
    expect(wrapper.find('[data-testid="alerts-mailpit-warning"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('Valores iniciales');
  });

  it('con un servidor real configurado no muestra el aviso', async () => {
    const wrapper = await mountPanel(
      settings({ smtpHost: 'smtp.proveedor.com', smtpPort: 587, configured: true }),
    );
    expect(wrapper.find('[data-testid="alerts-mailpit-warning"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('Configurado');
  });

  it('valida los campos antes de guardar', async () => {
    const wrapper = await mountPanel();
    await wrapper.find('#alert-recipients').setValue('malo');
    await wrapper.find('#alert-host').setValue('http://smtp.x.com');
    await wrapper.find('form').trigger('submit.prevent');
    await flushPromises();

    expect(alertSettingsService.update).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('dirección de correo inválida');
    expect(wrapper.text()).toContain('sin http:// ni puerto');
  });

  it('exige la contraseña la primera vez con autenticación', async () => {
    const wrapper = await mountPanel();
    await wrapper.find('#alert-auth').setValue(true);
    await wrapper.find('#alert-username').setValue('no-reply@prisma.uy');
    await wrapper.find('form').trigger('submit.prevent');
    await flushPromises();

    expect(alertSettingsService.update).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('La contraseña es obligatoria');
  });

  it('guarda la configuración y envía la contraseña solo si se escribió', async () => {
    alertSettingsService.update.mockResolvedValue({
      data: settings({
        recipients: 'equipo@prisma.uy',
        smtpHost: 'smtp.proveedor.com',
        smtpPort: 587,
        authEnabled: true,
        username: 'no-reply@prisma.uy',
        passwordSet: true,
        requireTls: true,
        configured: true,
      }),
    });
    const wrapper = await mountPanel();
    await wrapper.find('#alert-recipients').setValue('equipo@prisma.uy');
    await wrapper.find('#alert-host').setValue('smtp.proveedor.com');
    await wrapper.find('#alert-port').setValue('587');
    await wrapper.find('#alert-auth').setValue(true);
    await wrapper.find('#alert-tls').setValue(true);
    await wrapper.find('#alert-username').setValue('no-reply@prisma.uy');
    await wrapper.find('#alert-password').setValue('s3cret');
    await wrapper.find('form').trigger('submit.prevent');
    await flushPromises();

    expect(alertSettingsService.update).toHaveBeenCalledWith({
      recipients: 'equipo@prisma.uy',
      smtpHost: 'smtp.proveedor.com',
      smtpPort: 587,
      from: 'alertas@prisma.uy',
      authEnabled: true,
      username: 'no-reply@prisma.uy',
      password: 's3cret',
      requireTls: true,
    });
    expect(wrapper.text()).toContain('Configurado');
    expect((wrapper.find('#alert-password').element as HTMLInputElement).value).toBe('');
  });

  it('con contraseña ya guardada permite dejarla vacía y no la reenvía', async () => {
    alertSettingsService.update.mockResolvedValue({ data: settings({ configured: true }) });
    const wrapper = await mountPanel(
      settings({
        smtpHost: 'smtp.proveedor.com',
        authEnabled: true,
        username: 'u@prisma.uy',
        passwordSet: true,
        configured: true,
      }),
    );
    await wrapper.find('form').trigger('submit.prevent');
    await flushPromises();

    expect(alertSettingsService.update).toHaveBeenCalledTimes(1);
    expect(alertSettingsService.update.mock.calls[0][0].password).toBeNull();
  });

  it('muestra la advertencia si se guardó pero Alertmanager no recargó', async () => {
    alertSettingsService.update.mockResolvedValue({
      data: settings({ configured: true, warning: 'Alertmanager no respondió a la recarga.' }),
    });
    const wrapper = await mountPanel();
    await wrapper.find('form').trigger('submit.prevent');
    await flushPromises();

    expect(wrapper.find('[data-testid="alerts-save-warning"]').text()).toContain(
      'no respondió a la recarga',
    );
  });

  it('el botón de prueba muestra el resultado', async () => {
    alertSettingsService.test.mockResolvedValue({
      data: { smtpReachable: true, alertSent: true, message: 'Alerta de prueba enviada.' },
    });
    const wrapper = await mountPanel();
    await wrapper.find('[data-testid="alerts-test-button"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="alerts-test-result"]').text()).toContain(
      'Alerta de prueba enviada.',
    );
  });
});
