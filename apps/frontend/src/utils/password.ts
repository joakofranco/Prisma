const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ'; // sin I ni O: se confunden con 1 y 0 al leerlas
const LOWER = 'abcdefghijkmnopqrstuvwxyz'; // sin l
const DIGITS = '23456789'; // sin 0 ni 1
const SYMBOLS = '!@#$%^&*()-_=+?';
const ALL = UPPER + LOWER + DIGITS + SYMBOLS;

/** Largo por defecto: cumple el mínimo actual (8) y el de la política de complejidad de PRI-227
 * (12 caracteres, mayúscula, minúscula, número y símbolo). */
export const GENERATED_PASSWORD_LENGTH = 16;

/** Entero uniforme en [0, max) con crypto.getRandomValues, descartando valores que sesgarían el
 * resultado (rejection sampling). */
function randomInt(max: number): number {
  const limit = Math.floor(0x100000000 / max) * max;
  const buf = new Uint32Array(1);
  do {
    crypto.getRandomValues(buf);
  } while (buf[0] >= limit);
  return buf[0] % max;
}

function pick(chars: string): string {
  return chars[randomInt(chars.length)];
}

/**
 * Genera una contraseña aleatoria con una fuente criptográficamente segura. Incluye siempre al
 * menos una mayúscula, una minúscula, un número y un símbolo, para cumplir la política de
 * complejidad desde el inicio.
 */
export function generatePassword(length = GENERATED_PASSWORD_LENGTH): string {
  if (length < 8) throw new Error('La contraseña generada debe tener al menos 8 caracteres');
  const chars = [pick(UPPER), pick(LOWER), pick(DIGITS), pick(SYMBOLS)];
  while (chars.length < length) chars.push(pick(ALL));
  // Fisher-Yates: que las posiciones de las cuatro clases obligatorias no sean predecibles.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomInt(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}
