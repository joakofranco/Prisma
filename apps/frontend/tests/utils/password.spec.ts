import { describe, it, expect } from 'vitest';
import { generatePassword, GENERATED_PASSWORD_LENGTH } from '../../src/utils/password';

// Política de complejidad de PRI-227: 12+ caracteres, mayúscula, minúscula, número y símbolo.
const meetsPolicy = (p: string) =>
  p.length >= 12 && /[A-Z]/.test(p) && /[a-z]/.test(p) && /[0-9]/.test(p) && /[^A-Za-z0-9]/.test(p);

describe('generatePassword', () => {
  it('genera el largo por defecto', () => {
    expect(generatePassword()).toHaveLength(GENERATED_PASSWORD_LENGTH);
  });

  it('cumple siempre la política de complejidad (mayúscula, minúscula, número, símbolo)', () => {
    for (let i = 0; i < 500; i++) {
      const p = generatePassword();
      expect(meetsPolicy(p), p).toBe(true);
    }
  });

  it('respeta un largo pedido y no baja del mínimo de 8', () => {
    expect(generatePassword(24)).toHaveLength(24);
    expect(generatePassword(8)).toHaveLength(8);
    expect(() => generatePassword(7)).toThrow();
  });

  it('no usa caracteres ambiguos (0, O, 1, l, I)', () => {
    for (let i = 0; i < 200; i++) {
      expect(generatePassword()).not.toMatch(/[0O1lI]/);
    }
  });

  it('es aleatoria: 200 contraseñas, todas distintas', () => {
    const set = new Set(Array.from({ length: 200 }, () => generatePassword()));
    expect(set.size).toBe(200);
  });

  it('usa crypto.getRandomValues y no Math.random', () => {
    const original = Math.random;
    Math.random = () => {
      throw new Error('Math.random no debe usarse');
    };
    try {
      expect(() => generatePassword()).not.toThrow();
    } finally {
      Math.random = original;
    }
  });
});
