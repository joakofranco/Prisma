import { describe, it, expect, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import BaseModal from '../../src/components/common/BaseModal.vue';

// El modal se dibuja en <body> (Teleport): se lee de ahí y se limpia entre pruebas.
afterEach(() => {
  document.body.innerHTML = '';
});

function mountModal(props: Record<string, unknown> = {}) {
  return mount(BaseModal, {
    props: { modelValue: true, title: 'Vista previa', ...props },
    slots: { default: '<p>contenido</p>' },
    attachTo: document.body,
  });
}

const panel = () => document.body.querySelector<HTMLElement>('.rounded-2xl')!;

describe('BaseModal: tamaño xl para visores (PRI-290)', () => {
  it('xl ocupa casi toda la ventana', () => {
    mountModal({ size: 'xl' });

    expect(panel().className).toContain('md:w-[95%]');
  });

  it('los demás tamaños no cambian', () => {
    mountModal({ size: 'lg' });
    expect(panel().className).toContain('md:w-[90%]');

    document.body.innerHTML = '';
    mountModal({ size: 'md' });
    expect(panel().className).toContain('md:w-[60%]');

    document.body.innerHTML = '';
    mountModal({ size: 'sm' });
    expect(panel().className).toContain('md:w-[40%]');
  });

  it('con xl sigue cerrando con Escape y con el botón de cerrar', async () => {
    const wrapper = mountModal({ size: 'xl' });

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await flushPromises();
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false]);

    const close = document.body.querySelector<HTMLButtonElement>('button[title="Cerrar"]')!;
    close.click();
    await flushPromises();
    expect(wrapper.emitted('update:modelValue')).toHaveLength(2);
  });

  it('con xl sigue bloqueando el scroll de la página mientras está abierto', async () => {
    const wrapper = mountModal({ size: 'xl', modelValue: false });
    const before = document.body.style.overflow;

    await wrapper.setProps({ modelValue: true });
    expect(document.body.style.overflow).toBe('hidden');

    await wrapper.setProps({ modelValue: false });
    expect(document.body.style.overflow).toBe(before);
  });

  it('un modal persistente no se cierra con Escape', async () => {
    const wrapper = mountModal({ size: 'xl', persistent: true });

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await flushPromises();

    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  });
});
