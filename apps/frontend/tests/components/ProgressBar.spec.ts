import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import ProgressBar from '../../src/components/common/ProgressBar.vue';

function factory(props: Record<string, unknown>) {
  return mount(ProgressBar, { props: { label: 'Prioridad Alta', ...props } });
}

describe('ProgressBar', () => {
  it('expone el valor con los atributos de accesibilidad de una barra de progreso', () => {
    const bar = factory({ value: 40, detail: '2 de 5 cerradas' }).find('[role="progressbar"]');

    expect(bar.attributes('aria-valuenow')).toBe('40');
    expect(bar.attributes('aria-valuemin')).toBe('0');
    expect(bar.attributes('aria-valuemax')).toBe('100');
    expect(bar.attributes('aria-valuetext')).toBe('40 %, 2 de 5 cerradas');
  });

  it('la barra tiene una etiqueta accesible que es el texto visible', () => {
    const wrapper = factory({ value: 40 });
    const bar = wrapper.find('[role="progressbar"]');
    const labelId = bar.attributes('aria-labelledby')!;

    expect(wrapper.find(`[id="${labelId}"]`).text()).toBe('Prioridad Alta');
  });

  it('el ancho de la barra llena es el porcentaje', () => {
    const wrapper = factory({ value: 75 });

    expect(wrapper.find('[role="progressbar"] > div').attributes('style')).toContain('width: 75%');
    expect(wrapper.text()).toContain('75 %');
  });

  it('con null dice «Sin acciones», no 0 %, y no dibuja la parte llena', () => {
    const wrapper = factory({ value: null });
    const bar = wrapper.find('[role="progressbar"]');

    expect(wrapper.text()).toContain('Sin acciones');
    expect(wrapper.text()).not.toContain('0 %');
    expect(bar.attributes('aria-valuenow')).toBeUndefined();
    expect(bar.attributes('aria-valuetext')).toBe('Sin acciones');
    expect(bar.find('div').exists()).toBe(false);
  });

  it('0 % es un valor válido distinto de «Sin acciones»', () => {
    const wrapper = factory({ value: 0, detail: '0 de 3 cerradas' });

    expect(wrapper.text()).toContain('0 %');
    expect(wrapper.text()).not.toContain('Sin acciones');
    expect(wrapper.find('[role="progressbar"]').attributes('aria-valuenow')).toBe('0');
  });

  it('un valor fuera de rango se acota a la barra', () => {
    expect(
      factory({ value: 140 }).find('[role="progressbar"] > div').attributes('style'),
    ).toContain('width: 100%');
  });

  it('usa el color indicado, con su variante para modo oscuro', () => {
    const wrapper = factory({ value: 10, barClass: 'bg-red-600 dark:bg-red-500' });

    const fill = wrapper.find('[role="progressbar"] > div');
    expect(fill.classes()).toContain('bg-red-600');
    expect(fill.classes()).toContain('dark:bg-red-500');
  });
});
