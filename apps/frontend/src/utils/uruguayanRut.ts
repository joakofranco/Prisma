/**
 * Validación del RUT uruguayo (Registro Único Tributario) de personas jurídicas.
 *
 * Tiene 12 dígitos con la forma `AA-BBBBBB-001-D`:
 *  - `AA`: número de registro, de 01 a 22;
 *  - `BBBBBB`: secuencial, distinto de 000000;
 *  - `001`: grupo fijo;
 *  - `D`: dígito verificador, módulo 11 con los pesos 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2 aplicados a
 *    los 11 primeros dígitos. Un resultado de 10 no tiene dígito: ese RUT no existe.
 *
 * El algoritmo sale de la librería python-stdnum (`stdnum/uy/rut.py`); la DGI no publica el suyo.
 * Es la misma lógica y los mismos casos de prueba que el backend (UruguayanRut.java,
 * UruguayanRutTest): si se cambia una, se cambia la otra. El servidor es el que decide; esto solo
 * avisa antes de enviar el formulario.
 */

const WEIGHTS = [4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
const LENGTH = 12;

/** Largo mínimo de un identificador que no es RUT, y máximo (el de la columna). */
export const MIN_IDENTIFIER_LENGTH = 8;
export const MAX_IDENTIFIER_LENGTH = 20;

export const INVALID_RUT_MESSAGE =
  'El RUT no es válido: debe tener 12 dígitos (AA-BBBBBB-001-D) y un dígito verificador correcto';
export const IDENTIFIER_TOO_SHORT_MESSAGE = `El identificador debe tener al menos ${MIN_IDENTIFIER_LENGTH} caracteres`;
export const IDENTIFIER_TOO_LONG_MESSAGE = `El identificador no puede superar los ${MAX_IDENTIFIER_LENGTH} caracteres`;

/** Quita espacios, puntos y guiones, pasa a mayúsculas y descarta un prefijo «UY». */
export function compactRut(raw: string | null | undefined): string {
  const compact = (raw ?? '').replace(/[.\-\s]/g, '').toUpperCase();
  return compact.startsWith('UY') ? compact.slice(2) : compact;
}

/** Dígito verificador esperado para los 11 primeros dígitos (0 a 10; 10 = no existe). */
export function rutCheckDigit(first11Digits: string): number {
  let sum = 0;
  for (let i = 0; i < WEIGHTS.length; i++) {
    sum += Number(first11Digits[i]) * WEIGHTS[i];
  }
  return (11 - (sum % 11)) % 11;
}

export function isValidRut(raw: string | null | undefined): boolean {
  const rut = compactRut(raw);
  if (rut.length !== LENGTH || !/^\d+$/.test(rut)) return false;
  const registry = Number(rut.slice(0, 2));
  if (registry < 1 || registry > 22) return false;
  if (rut.slice(2, 8) === '000000' || rut.slice(8, 11) !== '001') return false;
  return rutCheckDigit(rut) === Number(rut[LENGTH - 1]);
}

/** Forma normalizada `AA-BBBBBB-001-D` (15 caracteres), o `null` si no es un RUT válido. */
export function normalizeRut(raw: string | null | undefined): string | null {
  if (!isValidRut(raw)) return null;
  const rut = compactRut(raw);
  return `${rut.slice(0, 2)}-${rut.slice(2, 8)}-${rut.slice(8, 11)}-${rut.slice(11)}`;
}

/**
 * Mensaje de error de un identificador de organización, o `null` si es válido. Vacío es válido
 * (es opcional). Con la casilla «es un RUT» se exige un RUT válido; sin ella, un largo mínimo.
 * Mismas reglas y mismos textos que OrganizationService en el backend.
 */
export function identifierError(
  identifier: string | null | undefined,
  isRut: boolean | null | undefined,
): string | null {
  const value = (identifier ?? '').trim();
  if (!value) return null;
  if (isRut) return isValidRut(value) ? null : INVALID_RUT_MESSAGE;
  if (value.length < MIN_IDENTIFIER_LENGTH) return IDENTIFIER_TOO_SHORT_MESSAGE;
  if (value.length > MAX_IDENTIFIER_LENGTH) return IDENTIFIER_TOO_LONG_MESSAGE;
  return null;
}
