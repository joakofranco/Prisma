import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import ControlGuideModal from '@/components/evaluations/ControlGuideModal.vue';

const guide = {
  interpretation: 'La organización tiene escrita su política.',
  example: 'El Directorio la aprueba por resolución.',
  evidence: 'Resolución o acta de aprobación.',
};

const byId = (testId: string) =>
  document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`);

function mountModal(modelValue = true) {
  return mount(ControlGuideModal, {
    attachTo: document.body,
    props: { modelValue, code: 'PS.1-1', description: 'Existe una política aprobada', guide },
  });
}

describe('ControlGuideModal', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('muestra el código, el texto del control y las tres secciones', () => {
    mountModal();

    expect(document.body.textContent).toContain('Guía del control PS.1-1');
    expect(byId('guide-control-text')?.textContent).toContain('Existe una política aprobada');
    expect(byId('guide-interpretation')?.textContent).toContain('Interpretación');
    expect(byId('guide-interpretation')?.textContent).toContain(guide.interpretation);
    expect(byId('guide-example')?.textContent).toContain('Ejemplo de aplicación');
    expect(byId('guide-example')?.textContent).toContain(guide.example);
    expect(byId('guide-evidence')?.textContent).toContain('Evidencia a considerar');
    expect(byId('guide-evidence')?.textContent).toContain(guide.evidence);
  });

  it('aclara que el ejemplo es ilustrativo y que no sustituye el texto oficial', () => {
    mountModal();

    expect(document.body.textContent).toContain('ilustrativo');
    expect(document.body.textContent).toContain('No sustituye el texto oficial');
  });

  it('no muestra nada si está cerrado', () => {
    mountModal(false);

    expect(byId('guide-interpretation')).toBeNull();
  });

  it('se cierra con el botón Cerrar', () => {
    const wrapper = mountModal();

    byId('close-control-guide')!.click();

    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([false]);
  });
});
