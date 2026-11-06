import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const usersService = vi.hoisted(() => ({
  getAll: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  delete: vi.fn(),
}));
const organizationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
const auth = vi.hoisted(() => ({ isAdmin: true, userId: 'me' }));

vi.mock('@/services/resources', () => ({ usersService, organizationsService }));
vi.mock('@/composables/useUtils', async (orig) => ({
  ...(await orig<typeof import('@/composables/useUtils')>()),
  useNotification: () => notification,
}));
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    get isAdmin() {
      return auth.isAdmin;
    },
    get user() {
      return { id: auth.userId };
    },
    hasAnyRole: () => true,
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

import UsersView from '@/views/users/UsersView.vue';

const target = {
  id: 'u-1',
  firstName: 'Ana',
  lastName: 'Pérez',
  email: 'ana@prisma.local',
  roles: ['PRISMA_ADMIN'],
  organizationIds: [],
  organizationNames: [],
  enabled: true,
  canLogin: true,
};

const alertEl = () => document.body.querySelector('[data-testid="mfa-removal-warning"]');
const passwordInput = () =>
  document.body.querySelector<HTMLInputElement>('input[placeholder="••••••••"]')!;

async function openEdit() {
  const wrapper = mount(UsersView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  await wrapper.find('button[title="Editar usuario"]').trigger('click');
  await flushPromises();
  return wrapper;
}

async function typePassword(value: string) {
  const input = passwordInput();
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await flushPromises();
}

describe('UsersView: al cambiar la contraseña se avisa que se quita el MFA (PRI-365)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    auth.isAdmin = true;
    auth.userId = 'me';
    usersService.getAll.mockResolvedValue({ data: { data: [target], total: 1 } });
    organizationsService.getAll.mockResolvedValue({ data: { data: [], total: 0 } });
    usersService.update.mockResolvedValue({ data: target });
  });

  it('sin tocar la contraseña no hay alerta', async () => {
    await openEdit();

    expect(alertEl()).toBeNull();
  });

  it('un PRISMA_ADMIN que escribe una contraseña nueva ve la alerta explícita', async () => {
    await openEdit();
    await typePassword('NuevaClave1!');

    expect(alertEl()).not.toBeNull();
    expect(alertEl()!.textContent).toContain('se le va a quitar el MFA');
    expect(alertEl()!.textContent).toContain('registrar uno nuevo');
  });

  it('si borra la contraseña escrita, la alerta desaparece', async () => {
    await openEdit();
    await typePassword('NuevaClave1!');
    await typePassword('');

    expect(alertEl()).toBeNull();
  });

  it('quien no es PRISMA_ADMIN no ve la alerta (el backend tampoco le quita el MFA a nadie)', async () => {
    auth.isAdmin = false;
    await openEdit();
    await typePassword('NuevaClave1!');

    expect(alertEl()).toBeNull();
  });

  it('un administrador editando su propia cuenta no ve la alerta', async () => {
    auth.userId = 'u-1';
    await openEdit();
    await typePassword('NuevaClave1!');

    expect(alertEl()).toBeNull();
  });

  it('al guardar con contraseña nueva, el aviso de éxito aclara que se quitó el MFA', async () => {
    await openEdit();
    await typePassword('NuevaClave1!');

    const save = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes('Guardar Cambios'),
    )!;
    save.click();
    await flushPromises();

    expect(usersService.update).toHaveBeenCalledTimes(1);
    expect(usersService.update.mock.calls[0][1].password).toBe('NuevaClave1!');
    expect(notification.success).toHaveBeenCalledWith(
      expect.stringContaining('se le quitó el MFA'),
    );
  });

  it('al guardar sin contraseña nueva el aviso es el de siempre', async () => {
    await openEdit();

    const save = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes('Guardar Cambios'),
    )!;
    save.click();
    await flushPromises();

    expect(notification.success).toHaveBeenCalledWith('Usuario actualizado correctamente');
  });
});
