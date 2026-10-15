import { describe, it, expect, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import BaseMultiSelect from '../../src/components/common/BaseMultiSelect.vue';

const options = [
  { value: 'a', label: 'Organización Demo' },
  { value: 'b', label: 'Prueba' },
  { value: 'c', label: 'Demo Video' },
];

function factory(props: Record<string, unknown> = {}) {
  return mount(BaseMultiSelect, {
    props: { modelValue: [], options, label: 'Organizaciones', ...props },
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
const REMOVE = 'button[aria-label^="Quitar"]';

describe('BaseMultiSelect (selector múltiple con buscador, C23)', () => {
  it('muestra una etiqueta por cada elemento elegido', () => {
    const w = factory({ modelValue: ['a', 'b'] });
    expect(w.text()).toContain('Organización Demo');
    expect(w.text()).toContain('Prueba');
    expect(w.findAll(REMOVE)).toHaveLength(2);
    w.unmount();
  });

  it('elegir una opción la agrega a la selección', async () => {
    const w = factory({ modelValue: ['a'] });
    await open(w);
    await pick(optionEls()[1]);
    await flushPromises();
    expect(w.emitted('update:modelValue')?.[0]?.[0]).toEqual(['a', 'b']);
    w.unmount();
  });

  it('la x de una etiqueta la quita', async () => {
    const w = factory({ modelValue: ['a', 'b'] });
    await w.find('button[aria-label="Quitar Prueba"]').trigger('click');
    expect(w.emitted('update:modelValue')?.[0]).toEqual([['a']]);
    w.unmount();
  });

  it('Backspace con la búsqueda vacía quita el último elemento', async () => {
    const w = factory({ modelValue: ['a', 'b'] });
    await w.find('input').trigger('keydown', { key: 'Backspace' });
    expect(w.emitted('update:modelValue')?.[0]).toEqual([['a']]);
    w.unmount();
  });

  it('Backspace con texto en la búsqueda NO quita elementos', async () => {
    const w = factory({ modelValue: ['a'] });
    await open(w);
    await w.find('input').setValue('dem');
    await w.find('input').trigger('keydown', { key: 'Backspace' });
    expect(w.emitted('update:modelValue')).toBeUndefined();
    w.unmount();
  });

  it('filtra sin importar tildes ni mayúsculas', async () => {
    const w = factory();
    await open(w);
    await w.find('input').setValue('ORGANIZACION');
    await flushPromises();
    expect(optionTexts()).toEqual(['Organización Demo']);
    w.unmount();
  });

  it('con max=1 elegir otra opción REEMPLAZA a la actual', async () => {
    const w = factory({ modelValue: ['a'], max: 1 });
    await open(w);
    await pick(optionEls()[1]);
    await flushPromises();
    expect(w.emitted('update:modelValue')?.[0]?.[0]).toEqual(['b']);
    w.unmount();
  });

  it('con max>1 al llegar al tope las demás opciones quedan deshabilitadas', async () => {
    const w = factory({ modelValue: ['a', 'b'], max: 2 });
    await open(w);
    const disabled = optionEls().map((o) => o.getAttribute('aria-disabled') === 'true');
    // a y b (elegidas) siguen habilitadas para poder quitarlas; c no.
    expect(disabled).toEqual([false, false, true]);
    w.unmount();
  });

  it('deshabilitado muestra las etiquetas sin botón para quitarlas', () => {
    const w = factory({ modelValue: ['a'], disabled: true });
    expect(w.text()).toContain('Organización Demo');
    expect(w.find(REMOVE).exists()).toBe(false);
    w.unmount();
  });

  it('un valor que ya no está entre las opciones se sigue mostrando', () => {
    const w = factory({ modelValue: ['zzz'] });
    expect(w.text()).toContain('zzz');
    w.unmount();
  });

  it('el campo tiene nombre accesible y vincula el error', async () => {
    const w = factory({ error: 'Seleccione al menos una' });
    await flushPromises();
    const input = w.find('input');
    const labelledby = input.attributes('aria-labelledby');
    expect(document.getElementById(labelledby!)?.textContent).toContain('Organizaciones');
    expect(input.attributes('aria-invalid')).toBe('true');
    w.unmount();
  });
});
