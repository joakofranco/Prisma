import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const organizationsService = vi.hoisted(() => ({
  getAll: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  reactivate: vi.fn(),
  delete: vi.fn(),
}));
const notification = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn() }));

vi.mock('@/services/resources', () => ({ organizationsService }));
vi.mock('@/composables/useUtils', () => ({ useNotification: () => notification }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

import OrganizationsView from '@/views/organizations/OrganizationsView.vue';

const org = (id: string, name: string, extra: Record<string, unknown> = {}) => ({
  id,
  name,
  identifier: null,
  identifierIsRut: false,
  sector: 'Público',
  size: 'Grande',
  enabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  ...extra,
});

const q = <T extends HTMLElement>(selector: string) => document.body.querySelector<T>(selector);

const mounted: Array<{ unmount: () => void }> = [];

async function mountView(orgs = [org('o1', 'Acme')]) {
  organizationsService.getAll.mockResolvedValue({ data: { data: orgs, total: orgs.length } });
  const wrapper = mount(OrganizationsView, {
    attachTo: document.body,
    global: { stubs: { RouterLink: true } },
  });
  mounted.push(wrapper);
  await flushPromises();
  return wrapper;
}

// El campo se identifica por su etiqueta visible, como lo haría una persona.
function inputByLabel(label: string): HTMLInputElement {
  const el = Array.from(document.body.querySelectorAll<HTMLLabelElement>('label')).find((l) =>
    l.textContent?.trim().startsWith(label),
  );
  return document.getElementById(el!.getAttribute('for')!) as HTMLInputElement;
}

async function type(input: HTMLInputElement, value: string) {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flushPromises();
}

async function choose(label: string, option: string) {
  const input = inputByLabel(label);
  const findOption = () =>
    Array.from(document.body.querySelectorAll<HTMLElement>('[role="option"]')).find((o) =>
      o.textContent?.trim().startsWith(option),
    );
  // El menú se dibuja en <body>; si otro desplegable todavía se está cerrando, el primer clic puede
  // no abrirlo: se reintenta.
  for (let attempt = 0; attempt < 6 && !findOption(); attempt++) {
    input.click();
    for (let i = 0; i < 3 && !findOption(); i++) await flushPromises();
  }
  const el = findOption()!;
  for (const type of ['mousedown', 'mouseup', 'click']) {
    el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true }));
  }
  await flushPromises();
}

const checkbox = () => q<HTMLInputElement>('input[type="checkbox"]')!;

async function toggleRut() {
  checkbox().click();
  await flushPromises();
}

async function openNewAndFill(identifier: string, isRut: boolean) {
  const wrapper = await mountView();
  await wrapper
    .findAll('button')
    .find((b) => b.text().includes('Nueva Organización'))!
    .trigger('click');
  await flushPromises();
  await type(inputByLabel('Nombre'), 'Nueva SA');
  await choose('Sector', 'Privado');
  await choose('Tamaño', 'Mediana');
  await type(inputByLabel('Identificador'), identifier);
  if (isRut) await toggleRut();
  return wrapper;
}

async function submit() {
  // El modal se dibuja en <body> y puede tardar unos ciclos en estar.
  for (let i = 0; i < 10 && !q('form'); i++) await flushPromises();
  q<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  await flushPromises();
}

describe('OrganizationsView: identificador y casilla «Es un RUT»', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    document.body.innerHTML = '';
    organizationsService.create.mockResolvedValue({ data: org('o2', 'Nueva SA') });
    organizationsService.update.mockResolvedValue({ data: org('o1', 'Acme') });
  });

  afterEach(() => {
    // Desmontar evita que un modal de una prueba siga en <body> durante la siguiente.
    while (mounted.length) mounted.pop()!.unmount();
  });

  it('la tabla muestra el identificador y marca «RUT» solo cuando lo es', async () => {
    const wrapper = await mountView([
      org('o1', 'Con RUT', { identifier: '21-616658-001-4', identifierIsRut: true }),
      org('o2', 'Con otro', { identifier: 'NIT-0009', identifierIsRut: false }),
      org('o3', 'Sin nada'),
    ]);

    const text = wrapper.text();
    expect(text).toContain('Identificador');
    expect(text).toContain('21-616658-001-4');
    expect(text).toContain('NIT-0009');
    expect(wrapper.findAll('[data-testid="rut-badge"]')).toHaveLength(1);
  });

  it('el formulario tiene el campo «Identificador» y la casilla «Es un RUT», sin marcar', async () => {
    await openNewAndFill('', false);

    expect(inputByLabel('Identificador')).toBeTruthy();
    expect(checkbox().checked).toBe(false);
    expect(document.body.textContent).toContain('Es un RUT');
  });

  it('con «Es un RUT» y un dígito verificador incorrecto muestra el error y no envía', async () => {
    await openNewAndFill('210303670014', true);
    await submit();

    expect(organizationsService.create).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('El RUT no es válido');
  });

  it('con «Es un RUT» y un RUT válido lo envía normalizado y con la marca', async () => {
    await openNewAndFill('216166580014', true);
    await submit();

    expect(organizationsService.create).toHaveBeenCalledTimes(1);
    expect(organizationsService.create.mock.calls[0][0]).toMatchObject({
      name: 'Nueva SA',
      identifier: '21-616658-001-4',
      identifierIsRut: true,
    });
  });

  it('el mismo texto sin la casilla se envía tal cual, como identificador libre', async () => {
    await openNewAndFill('210303670014', false);
    await submit();

    expect(organizationsService.create.mock.calls[0][0]).toMatchObject({
      identifier: '210303670014',
      identifierIsRut: false,
    });
  });

  it('un identificador libre de menos de 8 caracteres se rechaza en el campo', async () => {
    await openNewAndFill('1234567', false);
    await submit();

    expect(organizationsService.create).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('al menos 8 caracteres');
  });

  it('sin identificador se puede crear igual: es opcional', async () => {
    await openNewAndFill('', true);
    await submit();

    expect(organizationsService.create).toHaveBeenCalledTimes(1);
    expect(organizationsService.create.mock.calls[0][0].identifier).toBe('');
  });

  it('el error se limpia al corregir el campo o cambiar la casilla', async () => {
    await openNewAndFill('210303670014', true);
    await submit();
    expect(document.body.textContent).toContain('El RUT no es válido');

    await toggleRut();

    expect(document.body.textContent).not.toContain('El RUT no es válido');
  });

  it('un duplicado que informa el servidor se marca en el campo y el modal sigue abierto', async () => {
    organizationsService.create.mockRejectedValue({
      response: {
        status: 409,
        data: {
          message: 'Ya existe una organización con el identificador 21-616658-001-4',
          details: {
            identifier: [
              'Ya existe una organización con el identificador 21-616658-001-4 («Otra»)',
            ],
          },
        },
      },
    });
    await openNewAndFill('216166580014', true);
    await submit();

    expect(document.body.textContent).toContain('con el identificador 21-616658-001-4 («Otra»)');
    expect(notification.error).not.toHaveBeenCalled();
    expect(q('form')).not.toBeNull();
  });

  it('un identificador inválido que informa el servidor (400) también se marca en el campo', async () => {
    organizationsService.create.mockRejectedValue({
      response: {
        status: 400,
        data: {
          message: 'El RUT no es válido',
          details: { identifier: ['El RUT no es válido (servidor)'] },
        },
      },
    });
    await openNewAndFill('21-616658-001-4', true);
    await submit();

    expect(document.body.textContent).toContain('El RUT no es válido (servidor)');
    expect(notification.error).not.toHaveBeenCalled();
  });

  it('al editar se precargan el identificador y la casilla de la organización', async () => {
    const wrapper = await mountView([
      org('o1', 'Con RUT', { identifier: '21-616658-001-4', identifierIsRut: true }),
    ]);

    await wrapper.find('button[title="Editar organización"]').trigger('click');
    await flushPromises();

    expect(inputByLabel('Identificador').value).toBe('21-616658-001-4');
    expect(checkbox().checked).toBe(true);
  });
});
