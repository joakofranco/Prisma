import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const accountService = vi.hoisted(() => ({
  changePassword: vi.fn(),
  updateProfile: vi.fn(),
}));
const authStore = vi.hoisted(() => ({
  user: { id: 'u', email: 'a@prisma.local', firstName: 'Ana', lastName: 'P' },
  roles: ['VIEWER'],
  logout: vi.fn(),
  login: vi.fn(),
  patchProfile: vi.fn(),
}));

vi.mock('@/services/resources', () => ({ accountService }));
vi.mock('@/stores/auth', () => ({ useAuthStore: () => authStore }));

import AccountView from '@/views/account/AccountView.vue';
import LoginView from '@/views/auth/LoginView.vue';
import { PASSWORD_CHANGED_KEY } from '@/utils/constants';

async function submitPasswordChange(current: string, next: string, confirm: string) {
  const wrapper = mount(AccountView);
  await flushPromises();
  const inputs = wrapper.findAll('input[type="password"]');
  await inputs[0].setValue(current);
  await inputs[1].setValue(next);
  await inputs[2].setValue(confirm);
  await wrapper.find('form[class*="space-y-4"]').trigger('submit');
  await flushPromises();
  return wrapper;
}

describe('cambio de contraseña → login con las credenciales nuevas (PRI-363)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    sessionStorage.clear();
    accountService.changePassword.mockResolvedValue({});
    authStore.logout.mockResolvedValue(undefined);
  });

  it('al cambiar la contraseña cierra la sesión (Keycloak vuelve al login)', async () => {
    await submitPasswordChange('Vieja123!', 'Nueva1234!', 'Nueva1234!');

    expect(accountService.changePassword).toHaveBeenCalledWith({
      currentPassword: 'Vieja123!',
      newPassword: 'Nueva1234!',
    });
    expect(authStore.logout).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(PASSWORD_CHANGED_KEY)).toBe('1');
  });

  it('si el cambio falla, no cierra la sesión ni deja la marca', async () => {
    accountService.changePassword.mockRejectedValue(
      new Error('La contraseña actual no es correcta'),
    );

    await submitPasswordChange('Mala', 'Nueva1234!', 'Nueva1234!');

    expect(authStore.logout).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PASSWORD_CHANGED_KEY)).toBeNull();
  });

  it('si la validación falla (no coinciden), no llama al backend ni cierra la sesión', async () => {
    await submitPasswordChange('Vieja123!', 'Nueva1234!', 'Otra12345!');

    expect(accountService.changePassword).not.toHaveBeenCalled();
    expect(authStore.logout).not.toHaveBeenCalled();
  });

  it('el login avisa que la contraseña se actualizó y lo muestra una sola vez', () => {
    sessionStorage.setItem(PASSWORD_CHANGED_KEY, '1');

    const first = mount(LoginView);
    expect(first.find('[data-testid="password-changed-notice"]').exists()).toBe(true);
    expect(sessionStorage.getItem(PASSWORD_CHANGED_KEY)).toBeNull();

    const second = mount(LoginView);
    expect(second.find('[data-testid="password-changed-notice"]').exists()).toBe(false);
  });

  it('un login normal no muestra el aviso', () => {
    expect(mount(LoginView).find('[data-testid="password-changed-notice"]').exists()).toBe(false);
  });
});
