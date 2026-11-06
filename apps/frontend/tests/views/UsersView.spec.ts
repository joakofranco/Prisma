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

vi.mock('@/services/resources', () => ({ usersService, organizationsService }));
vi.mock('@/composables/useUtils', async (orig) => ({
  ...(await orig<typeof import('@/composables/useUtils')>()),
  useNotification: () => notification,
}));
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    isAdmin: true,
    user: { id: 'me' },
    hasAnyRole: () => true,
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

import UsersView from '@/views/users/UsersView.vue';

// El modal se dibuja en <body> (Teleport).
const passwordInput = () =>
  document.body.querySelector<HTMLInputElement>('input[placeholder="••••••••"]')!;
const btn = (testid: string) =>
  document.body.querySelector<HTMLButtonElement>(`[data-testid="${testid}"]`);

const complex = (p: string) =>
  p.length >= 12 && /[A-Z]/.test(p) && /[a-z]/.test(p) && /[0-9]/.test(p) && /[^A-Za-z0-9]/.test(p);

async function openCreate() {
  const wrapper = mount(UsersView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  await flushPromises();
  const openBtn = wrapper.findAll('button').find((b) => b.text().includes('Nuevo'));
  await openBtn!.trigger('click');
  await flushPromises();
  return wrapper;
}

describe('UsersView: contraseña generada en el alta (PRI-356)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    usersService.getAll.mockResolvedValue({ data: { data: [], total: 0 } });
    organizationsService.getAll.mockResolvedValue({ data: { data: [], total: 0 } });
  });

  it('al abrir el alta genera una contraseña que cumple la política y la muestra en claro', async () => {
    await openCreate();

    const input = passwordInput();
    expect(input.type).toBe('text');
    expect(complex(input.value)).toBe(true);
  });

  it('«Copiar» envía la contraseña mostrada al portapapeles y avisa', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await openCreate();
    const shown = passwordInput().value;

    btn('copy-password')!.click();
    await flushPromises();

    expect(writeText).toHaveBeenCalledWith(shown);
    expect(notification.success).toHaveBeenCalled();
  });

  it('si el navegador no permite copiar, avisa en vez de fallar en silencio', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    await openCreate();

    btn('copy-password')!.click();
    await flushPromises();

    expect(notification.warning).toHaveBeenCalled();
    expect(notification.success).not.toHaveBeenCalled();
  });

  it('«Generar otra» reemplaza la contraseña por una nueva que también cumple', async () => {
    await openCreate();
    const first = passwordInput().value;

    btn('regenerate-password')!.click();
    await flushPromises();

    const second = passwordInput().value;
    expect(second).not.toBe(first);
    expect(complex(second)).toBe(true);
  });

  it('al crear el usuario manda la contraseña generada', async () => {
    usersService.create.mockResolvedValue({
      data: {
        id: 'u1',
        firstName: 'Ana',
        lastName: 'Pérez',
        email: 'ana@prisma.local',
        roles: ['PRISMA_ADMIN'],
        organizationIds: [],
        organizationNames: [],
        enabled: true,
        canLogin: true,
      },
    });
    await openCreate();
    const shown = passwordInput().value;
    const setValue = (selector: string, value: string) => {
      const el = document.body.querySelector<HTMLInputElement>(selector)!;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('input[placeholder="Nombre"]', 'Ana');
    setValue('input[placeholder="Apellido"]', 'Pérez');
    setValue('input[placeholder="usuario@prisma.local"]', 'ana@prisma.local');
    // Rol: Visualizador (sin organización obligatoria no aplica; el backend valida el resto).
    const role = Array.from(document.body.querySelectorAll<HTMLInputElement>('input')).find(
      (i) => i.type === 'checkbox' && i.closest('label')?.textContent?.includes('Administrador'),
    )!;
    role.click();
    await flushPromises();

    const submit = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => b.textContent?.includes('Crear Usuario'),
    )!;
    submit.click();
    await flushPromises();

    expect(usersService.create).toHaveBeenCalledTimes(1);
    expect(usersService.create.mock.calls[0][0].password).toBe(shown);
  });

});
