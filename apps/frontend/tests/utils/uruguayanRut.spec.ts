import { describe, it, expect } from 'vitest';
import {
  IDENTIFIER_TOO_LONG_MESSAGE,
  IDENTIFIER_TOO_SHORT_MESSAGE,
  INVALID_RUT_MESSAGE,
  MAX_IDENTIFIER_LENGTH,
  identifierError,
  isValidRut,
  normalizeRut,
  rutCheckDigit,
} from '@/utils/uruguayanRut';

// Casos compartidos con el backend (UruguayanRutTest.java): si se agrega o cambia uno, se cambia en
// los dos. Salen del algoritmo de python-stdnum (uy/rut.py) y del RUT que aportó Joaquín.
describe('uruguayanRut: validación del RUT', () => {
  it('acepta RUT válidos en cualquier formato', () => {
    for (const rut of ['211003420017', '211406340011', '216166580014']) {
      expect(isValidRut(rut), rut).toBe(true);
    }
    expect(isValidRut('21-100342-001-7')).toBe(true);
    expect(isValidRut('UY 21 140634 001 1')).toBe(true);
    expect(isValidRut(' uy21.616658.001-4 ')).toBe(true);
  });

  it('el dígito verificador sigue la fórmula de módulo 11', () => {
    // 21616658001: 2*4 + 1*3 + 6*2 + 1*9 + 6*8 + 6*7 + 5*6 + 8*5 + 0*4 + 0*3 + 1*2 = 194
    // 194 mod 11 = 7 → 11 - 7 = 4
    expect(rutCheckDigit('21616658001')).toBe(4);
    expect(rutCheckDigit('21100342001')).toBe(7);
    expect(rutCheckDigit('21140634001')).toBe(1);
  });

  it('el verificador 0 es válido y el 10 no tiene dígito que lo represente', () => {
    let zero: string | null = null;
    let ten: string | null = null;
    for (let n = 0; n < 1_000_000 && (zero === null || ten === null); n++) {
      const candidate = `21${String(n).padStart(6, '0')}001`;
      const digit = rutCheckDigit(candidate);
      if (digit === 0 && zero === null) zero = candidate;
      if (digit === 10 && ten === null) ten = candidate;
    }
    expect(isValidRut(`${zero}0`)).toBe(true);
    for (let d = 0; d <= 9; d++) expect(isValidRut(`${ten}${d}`), `${ten}${d}`).toBe(false);
  });

  it('rechaza un dígito verificador incorrecto', () => {
    expect(isValidRut('210303670014')).toBe(false); // el correcto sería 0
    expect(isValidRut('216166580015')).toBe(false);
  });

  it('rechaza largo incorrecto o caracteres que no son dígitos', () => {
    for (const bad of [null, undefined, '', '12345678', '21616658001', '2161665800144']) {
      expect(isValidRut(bad), String(bad)).toBe(false);
    }
    expect(isValidRut('21616658001A')).toBe(false);
  });

  it('rechaza un registro fuera de rango, un secuencial vacío u otro grupo fijo', () => {
    expect(isValidRut('006166580014')).toBe(false); // prefijo 00
    expect(isValidRut('236166580014')).toBe(false); // prefijo 23
    expect(isValidRut('210000000014')).toBe(false); // secuencial 000000
    expect(isValidRut('216166580024')).toBe(false); // grupo 002
  });

  it('normaliza a la forma de 15 caracteres', () => {
    expect(normalizeRut('216166580014')).toBe('21-616658-001-4');
    expect(normalizeRut('UY 21.616658.001-4')).toBe('21-616658-001-4');
    expect(normalizeRut('216166580014')).toHaveLength(15);
    expect(normalizeRut('210303670014')).toBeNull();
    expect(normalizeRut(null)).toBeNull();
  });
});

describe('uruguayanRut: identifierError (mismas reglas que el servidor)', () => {
  it('vacío es válido: el identificador es opcional', () => {
    expect(identifierError('', false)).toBeNull();
    expect(identifierError('   ', true)).toBeNull();
    expect(identifierError(undefined, undefined)).toBeNull();
  });

  it('con la casilla «es un RUT» exige un RUT válido', () => {
    expect(identifierError('21-616658-001-4', true)).toBeNull();
    expect(identifierError('210303670014', true)).toBe(INVALID_RUT_MESSAGE);
    expect(identifierError('NIT-12345678', true)).toBe(INVALID_RUT_MESSAGE);
  });

  it('sin la casilla acepta cualquier texto de al menos 8 caracteres', () => {
    expect(identifierError('210303670014', false)).toBeNull(); // inválido como RUT, válido libre
    expect(identifierError('12345678', false)).toBeNull();
    expect(identifierError('1234567', false)).toBe(IDENTIFIER_TOO_SHORT_MESSAGE);
    expect(identifierError('  1234567  ', false)).toBe(IDENTIFIER_TOO_SHORT_MESSAGE);
  });

  it('no deja pasar de la longitud de la columna', () => {
    expect(identifierError('X'.repeat(MAX_IDENTIFIER_LENGTH), false)).toBeNull();
    expect(identifierError('X'.repeat(MAX_IDENTIFIER_LENGTH + 1), false)).toBe(
      IDENTIFIER_TOO_LONG_MESSAGE,
    );
  });
});
