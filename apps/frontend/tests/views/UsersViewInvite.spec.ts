import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

// RF-USR-05 (PRI-240): invitación por correo de evaluadores internos desde Usuarios.
const usersService = vi.hoisted(() => ({
  getAll: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  delete: vi.fn(),
  unlock: vi.fn(),
  invite: vi.fn(),
  resendInvitation: vi.fn(),
  invitationAvailability: vi.fn(),
}));
const organizationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
const auth = vi.hoisted(() => ({ isAdmin: false }));

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

const ORG = { id: 'org-1', name: 'Intendencia de Rivera', enabled: true };

const user = (id: string, extra: Record<string, unknown> = {}) => ({
  id,
  firstName: 'Lucía',
  lastName: 'Fernández',
  email: `${id}@ejemplo.uy`,
  roles: ['INTERNAL_EVALUATOR'],
  organizationIds: ['org-1'],
  organizationNames: ['Intendencia de Rivera'],
  enabled: true,
  canLogin: true,
  ...extra,
});

async function mountView(users = [user('u-1')], emailConfigured = true) {
  usersService.getAll.mockResolvedValue({ data: { data: users, total: users.length } });
  usersService.invitationAvailability.mockResolvedValue({ data: { emailConfigured } });
  const wrapper = mount(UsersView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  return wrapper;
}

const q = <T extends Element = HTMLElement>(sel: string) => document.body.querySelector<T>(sel);

function fill(label: string, value: string) {
  const input = Array.from(
    document.body.querySelectorAll<HTMLInputElement>('[data-testid="invite-form"] input'),
  ).find((i) => i.closest('div')?.textContent?.includes(label));
  if (!input) throw new Error(`No input ${label}`);
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

describe('UsersView · invitar evaluador (PRI-240)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    auth.isAdmin = false;
    organizationsService.getAll.mockResolvedValue({ data: { data: [ORG], total: 1 } });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('el responsable invita a su organización: rol fijo, sin elegir organización', async () => {
    const wrapper = await mountView();
    usersService.invite.mockResolvedValue({ data: user('nueva', { invitedAt: '2026-11-10' }) });

    q<HTMLButtonElement>('[data-testid="open-invite"]')!.click();
    await flushPromises();
    expect(q('[data-testid="invite-own-organization"]')?.textContent).toContain(
      'Intendencia de Rivera',
    );
    fill('Email', 'lucia@ejemplo.uy');
    fill('Nombre', 'Lucía');
    fill('Apellido', 'Fernández');
    await flushPromises();
    q<HTMLButtonElement>('[data-testid="invite-submit"]')!.click();
    await flushPromises();

    expect(usersService.invite).toHaveBeenCalledWith({
      email: 'lucia@ejemplo.uy',
      firstName: 'Lucía',
      lastName: 'Fernández',
    });
    expect(q('[data-testid="invite-sent"]')?.textContent).toContain('nueva@ejemplo.uy');
    wrapper.unmount();
  });

  it('valida los datos antes de enviar', async () => {
    const wrapper = await mountView();
    q<HTMLButtonElement>('[data-testid="open-invite"]')!.click();
    await flushPromises();
    q<HTMLButtonElement>('[data-testid="invite-submit"]')!.click();
    await flushPromises();

    expect(usersService.invite).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('el administrador tiene que elegir la organización', async () => {
    auth.isAdmin = true;
    const wrapper = await mountView();
    q<HTMLButtonElement>('[data-testid="open-invite"]')!.click();
    await flushPromises();
    expect(q('[data-testid="invite-own-organization"]')).toBeNull();
    fill('Email', 'lucia@ejemplo.uy');
    fill('Nombre', 'Lucía');
    fill('Apellido', 'Fernández');
    await flushPromises();
    q<HTMLButtonElement>('[data-testid="invite-submit"]')!.click();
    await flushPromises();

    expect(usersService.invite).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Elegí la organización a la que se invita');
    wrapper.unmount();
  });

  it('sin servidor de correo avisa y no deja enviar', async () => {
    const wrapper = await mountView([user('u-1')], false);
    q<HTMLButtonElement>('[data-testid="open-invite"]')!.click();
    await flushPromises();

    expect(q('[data-testid="invite-no-email"]')?.textContent).toContain('servidor de correo');
    expect(q<HTMLButtonElement>('[data-testid="invite-submit"]')!.disabled).toBe(true);
    wrapper.unmount();
  });

  it('si el backend rechaza la invitación lo informa y deja el modal abierto', async () => {
    const wrapper = await mountView();
    usersService.invite.mockRejectedValue({
      response: { data: { message: 'Ya existe un usuario con ese email' } },
    });
    q<HTMLButtonElement>('[data-testid="open-invite"]')!.click();
    await flushPromises();
    fill('Email', 'lucia@ejemplo.uy');
    fill('Nombre', 'Lucía');
    fill('Apellido', 'Fernández');
    await flushPromises();
    q<HTMLButtonElement>('[data-testid="invite-submit"]')!.click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalled();
    expect(q('[data-testid="invite-form"]')).not.toBeNull();
    expect(q('[data-testid="invite-sent"]')).toBeNull();
    wrapper.unmount();
  });

  it('muestra la invitación pendiente y permite reenviarla', async () => {
    const pending = user('u-1', { invitedAt: '2026-11-10T10:00:00Z', invitationAcceptedAt: null });
    const wrapper = await mountView([pending]);
    usersService.resendInvitation.mockResolvedValue({
      data: { ...pending, invitedAt: '2026-11-11T10:00:00Z' },
    });

    expect(q('[data-testid="invitation-pending"]')?.textContent).toContain('Invitación pendiente');
    expect(q('[data-testid="invitation-pending"]')?.textContent).toContain('10/11/2026');
    q<HTMLButtonElement>('[data-testid="resend-invitation"]')!.click();
    await flushPromises();

    expect(usersService.resendInvitation).toHaveBeenCalledWith('u-1');
    expect(notification.success).toHaveBeenCalledWith('Invitación reenviada a u-1@ejemplo.uy');
    wrapper.unmount();
  });

  it('no reenvía si se cancela la confirmación', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const wrapper = await mountView([user('u-1', { invitedAt: '2026-11-10T10:00:00Z' })]);
    q<HTMLButtonElement>('[data-testid="resend-invitation"]')!.click();
    await flushPromises();

    expect(usersService.resendInvitation).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('informa si el reenvío falla', async () => {
    const wrapper = await mountView([user('u-1', { invitedAt: '2026-11-10T10:00:00Z' })]);
    usersService.resendInvitation.mockRejectedValue(new Error('smtp'));
    q<HTMLButtonElement>('[data-testid="resend-invitation"]')!.click();
    await flushPromises();

    expect(notification.error).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('quien aceptó la invitación figura con acceso y la fecha', async () => {
    const wrapper = await mountView([
      user('u-1', {
        invitedAt: '2026-11-01T10:00:00Z',
        invitationAcceptedAt: '2026-11-04T10:00:00Z',
      }),
    ]);

    expect(q('[data-testid="invitation-pending"]')).toBeNull();
    expect(document.body.textContent).toContain('Aceptó la invitación el 04/11/2026');
    wrapper.unmount();
  });

  it('si no se puede consultar el correo deja intentar igual', async () => {
    usersService.getAll.mockResolvedValue({ data: { data: [], total: 0 } });
    usersService.invitationAvailability.mockRejectedValue(new Error('403'));
    const wrapper = mount(UsersView, {
      attachTo: document.body,
      global: { stubs: { RouterLink: true } },
    });
    await flushPromises();
    q<HTMLButtonElement>('[data-testid="open-invite"]')!.click();
    await flushPromises();

    expect(q('[data-testid="invite-no-email"]')).toBeNull();
    expect(q<HTMLButtonElement>('[data-testid="invite-submit"]')!.disabled).toBe(false);
    wrapper.unmount();
  });
});
