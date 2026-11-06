import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const usersService = vi.hoisted(() => ({
  getAll: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  delete: vi.fn(),
  unlock: vi.fn(),
}));
const organizationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
const auth = vi.hoisted(() => ({ isAdmin: true }));

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
    user: { id: 'me' },
    hasAnyRole: () => true,
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

import UsersView from '@/views/users/UsersView.vue';

const user = (id: string, canLogin: boolean) => ({
  id,
  firstName: 'Ana',
  lastName: 'Pérez',
  email: `${id}@prisma.local`,
  roles: ['INTERNAL_EVALUATOR'],
  organizationIds: [],
  organizationNames: [],
  enabled: true,
  canLogin,
});

async function mountView(users = [user('u-1', true)]) {
  usersService.getAll.mockResolvedValue({ data: { data: users, total: users.length } });
  const wrapper = mount(UsersView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  return wrapper;
}

const unlockButtons = () =>
  Array.from(document.body.querySelectorAll<HTMLButtonElement>('[data-testid="unlock-user"]'));

describe('UsersView: desbloquear cuenta (PRI-233)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    auth.isAdmin = true;
    organizationsService.getAll.mockResolvedValue({ data: { data: [], total: 0 } });
    usersService.unlock.mockResolvedValue({ data: { wasLocked: true } });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('el administrador global ve el botón «Desbloquear cuenta» en cada fila con acceso', async () => {
    await mountView([user('u-1', true), user('u-2', true)]);

    expect(unlockButtons()).toHaveLength(2);
    expect(unlockButtons()[0].getAttribute('title')).toBe('Desbloquear cuenta');
  });

  it('quien no es PRISMA_ADMIN (por ejemplo ORG_RESPONSIBLE) no lo ve', async () => {
    auth.isAdmin = false;
    await mountView();

    expect(unlockButtons()).toHaveLength(0);
  });

  it('un usuario sin cuenta de acceso todavía no tiene nada que desbloquear', async () => {
    await mountView([user('u-1', false)]);

    expect(unlockButtons()).toHaveLength(0);
  });

  it('pide confirmación antes de desbloquear y no llama al servicio si se cancela', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    await mountView();

    unlockButtons()[0].click();
    await flushPromises();

    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(usersService.unlock).not.toHaveBeenCalled();
  });

  it('desbloquea y avisa del éxito', async () => {
    await mountView();

    unlockButtons()[0].click();
    await flushPromises();

    expect(usersService.unlock).toHaveBeenCalledWith('u-1');
    expect(notification.success).toHaveBeenCalledWith('Cuenta de Ana Pérez desbloqueada');
    expect(notification.error).not.toHaveBeenCalled();
  });

  it('si la cuenta no estaba bloqueada lo dice con un aviso, no como éxito', async () => {
    usersService.unlock.mockResolvedValue({ data: { wasLocked: false } });
    await mountView();

    unlockButtons()[0].click();
    await flushPromises();

    expect(notification.warning).toHaveBeenCalledWith('La cuenta de Ana Pérez no estaba bloqueada');
    expect(notification.success).not.toHaveBeenCalled();
  });

  it('si el servidor falla muestra su mensaje', async () => {
    usersService.unlock.mockRejectedValue({
      response: { data: { message: 'Acceso denegado' } },
    });
    await mountView();

    unlockButtons()[0].click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('Acceso denegado');
  });

  it('si el error no trae mensaje usa uno genérico', async () => {
    usersService.unlock.mockRejectedValue(new Error(''));
    await mountView();

    unlockButtons()[0].click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalledWith('No se pudo desbloquear la cuenta');
  });
});
