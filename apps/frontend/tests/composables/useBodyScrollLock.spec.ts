import { describe, it, expect, beforeEach } from 'vitest';
import { lockBodyScroll, unlockBodyScroll } from '../../src/composables/useBodyScrollLock';

describe('useBodyScrollLock (modales, C19)', () => {
  beforeEach(() => {
    // Deja el contador en 0 por si un test anterior falló a mitad.
    for (let i = 0; i < 5; i++) unlockBodyScroll();
    document.body.style.overflow = '';
    document.body.style.paddingRight = '';
  });

  it('bloquea el scroll del body al abrir y lo libera al cerrar', () => {
    lockBodyScroll();
    expect(document.body.style.overflow).toBe('hidden');
    unlockBodyScroll();
    expect(document.body.style.overflow).toBe('');
  });

  it('con modales anidados solo libera al cerrar el último', () => {
    lockBodyScroll();
    lockBodyScroll();
    unlockBodyScroll();
    expect(document.body.style.overflow).toBe('hidden');
    unlockBodyScroll();
    expect(document.body.style.overflow).toBe('');
  });

  it('restaura el overflow que tenía el body antes del primer bloqueo', () => {
    document.body.style.overflow = 'scroll';
    lockBodyScroll();
    unlockBodyScroll();
    expect(document.body.style.overflow).toBe('scroll');
  });

  it('un unlock de más no rompe nada', () => {
    unlockBodyScroll();
    unlockBodyScroll();
    lockBodyScroll();
    expect(document.body.style.overflow).toBe('hidden');
    unlockBodyScroll();
    expect(document.body.style.overflow).toBe('');
  });
});
