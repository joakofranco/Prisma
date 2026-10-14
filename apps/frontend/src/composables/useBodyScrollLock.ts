// Bloquea el scroll del <body> mientras haya al menos un modal abierto, para que la rueda / el
// gesto de desplazamiento no mueva la página DE FONDO mientras el usuario está en el panel (C19).
// Con contador porque pueden abrirse modales encima de otros: el scroll solo se libera cuando se
// cierra el último.

let locks = 0;
let previousOverflow = '';
let previousPaddingRight = '';

export function lockBodyScroll(): void {
  if (typeof document === 'undefined') return;
  if (locks === 0) {
    const body = document.body;
    previousOverflow = body.style.overflow;
    previousPaddingRight = body.style.paddingRight;
    // Al ocultar la barra de desplazamiento el contenido se ensancharía y "saltaría": se compensa
    // con un padding igual al ancho de esa barra.
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth;
    body.style.overflow = 'hidden';
    if (scrollbarWidth > 0) body.style.paddingRight = `${scrollbarWidth}px`;
  }
  locks += 1;
}

export function unlockBodyScroll(): void {
  if (typeof document === 'undefined' || locks === 0) return;
  locks -= 1;
  if (locks === 0) {
    document.body.style.overflow = previousOverflow;
    document.body.style.paddingRight = previousPaddingRight;
  }
}
