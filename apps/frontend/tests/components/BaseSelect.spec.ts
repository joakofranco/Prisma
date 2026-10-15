import { describe, it, expect, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import BaseSelect from '../../src/components/common/BaseSelect.vue';

const options = [
  { value: 'a', label: 'Organización Demo' },
  { value: 'b', label: 'Prueba' },
  { value: 'c', label: 'Demo Video' },
];

function factory(props: Record<string, unknown> = {}) {
  return mount(BaseSelect, {
    props: { modelValue: '', options, label: 'Organización', ...props },
    attachTo: document.body,
  });
}

async function open(wrapper: ReturnType<typeof factory>) {
  await wrapper.find('input').trigger('click');
  await flushPromises();
}

// El menú se dibuja en <body> (Teleport): se lee de ahí.
const optionEls = () => Array.from(document.body.querySelectorAll<HTMLElement>('[role="option"]'));
const optionTexts = () => optionEls().map((o) => (o.textContent ?? '').trim());

// Un clic real es mousedown + mouseup + click: el combobox elige con esa secuencia.
async function pick(el: HTMLElement) {
  for (const type of ['mousedown', 'mouseup', 'click']) {
    el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true }));
  }
  await flushPromises();
}

// Los menús van en un Teleport a <body>: se limpia entre pruebas para que no se mezclen.
afterEach(() => {
  document.body.innerHTML = '';
});

const CLEAR = 'button[aria-label="Quitar selección"]';

describe('BaseSelect (desplegable con buscador, C23)', () => {
  it('muestra el texto de la opción elegida y es un combobox con nombre accesible', async () => {
    const w = factory({ modelValue: 'b' });
    await flushPromises();
    const input = w.find('input');
    expect((input.element as HTMLInputElement).value).toBe('Prueba');
    expect(input.attributes('role')).toBe('combobox');
    const labelledby = input.attributes('aria-labelledby');
    expect(labelledby).toBeTruthy();
    expect(document.getElementById(labelledby!)?.textContent).toContain('Organización');
    w.unmount();
  });

  it('muestra el placeholder cuando no hay selección', () => {
    const w = factory({ placeholder: 'Seleccione una organización' });
    expect(w.find('input').attributes('placeholder')).toBe('Seleccione una organización');
    w.unmount();
  });

  it('al abrir ofrece todas las opciones', async () => {
    const w = factory();
    await open(w);
    expect(optionTexts()).toEqual(['Organización Demo', 'Prueba', 'Demo Video']);
    w.unmount();
  });

  it('filtra al tipear, sin importar tildes ni mayúsculas', async () => {
    const w = factory();
    await open(w);
    await w.find('input').setValue('organizacion');
    await flushPromises();
    expect(optionTexts()).toEqual(['Organización Demo']);

    await w.find('input').setValue('DEMO');
    await flushPromises();
    expect(optionTexts()).toEqual(['Organización Demo', 'Demo Video']);
    w.unmount();
  });

  it('avisa cuando no hay resultados', async () => {
    const w = factory();
    await open(w);
    await w.find('input').setValue('zzz');
    await flushPromises();
    expect(optionTexts()).toEqual([]);
    expect(document.body.textContent).toContain('Sin resultados para "zzz"');
    w.unmount();
  });

  it('elegir una opción emite update:modelValue con su valor', async () => {
    const w = factory();
    await open(w);
    await pick(optionEls()[1]);
    await flushPromises();
    expect(w.emitted('update:modelValue')?.[0]).toEqual(['b']);
    w.unmount();
  });

  it('soporta valores numéricos (p.ej. nivel objetivo)', async () => {
    const w = factory({
      modelValue: 1,
      options: [
        { value: 1, label: 'Nivel 1' },
        { value: 2, label: 'Nivel 2' },
      ],
      searchable: false,
    });
    expect((w.find('input').element as HTMLInputElement).value).toBe('Nivel 1');
    await open(w);
    await pick(optionEls()[1]);
    await flushPromises();
    expect(w.emitted('update:modelValue')?.[0]).toEqual([2]);
    w.unmount();
  });

  it('una opción con valor vacío (p.ej. "Todas") es seleccionable y se muestra', () => {
    const w = factory({
      modelValue: '',
      options: [{ value: '', label: 'Todas las organizaciones' }, ...options],
    });
    expect((w.find('input').element as HTMLInputElement).value).toBe('Todas las organizaciones');
    w.unmount();
  });

  it('clearable: la x vuelve a "sin selección"', async () => {
    const w = factory({ modelValue: 'b', clearable: true });
    await w.find(CLEAR).trigger('click');
    expect(w.emitted('update:modelValue')?.[0]).toEqual(['']);
    w.unmount();
  });

  it('clearable no muestra la x sin selección o deshabilitado', () => {
    const empty = factory({ clearable: true });
    expect(empty.find(CLEAR).exists()).toBe(false);
    empty.unmount();
    const off = factory({ modelValue: 'b', clearable: true, disabled: true });
    expect(off.find(CLEAR).exists()).toBe(false);
    off.unmount();
  });

  it('searchable=false deja el campo de solo lectura (se comporta como un select)', () => {
    const w = factory({ searchable: false });
    expect(w.find('input').attributes('readonly')).toBeDefined();
    w.unmount();
  });

  it('deshabilitado no se abre', async () => {
    const w = factory({ disabled: true });
    await open(w);
    expect(optionTexts()).toEqual([]);
    w.unmount();
  });

  it('muestra el error y lo vincula al campo', () => {
    const w = factory({ error: 'Seleccione una organización' });
    const input = w.find('input');
    expect(input.attributes('aria-invalid')).toBe('true');
    const describedby = input.attributes('aria-describedby');
    expect(document.getElementById(describedby!)?.textContent).toContain(
      'Seleccione una organización',
    );
    w.unmount();
  });

  it('sin label usa aria-label como nombre accesible', async () => {
    const w = factory({ label: '', ariaLabel: 'Filtrar por acción' });
    await flushPromises();
    const labelledby = w.find('input').attributes('aria-labelledby');
    expect(document.getElementById(labelledby!)?.textContent).toContain('Filtrar por acción');
    w.unmount();
  });
});
