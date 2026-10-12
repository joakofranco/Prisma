import { describe, it, expect } from 'vitest';
import { getOrgMode, validateOrganizationCount } from '../../src/utils/organizationRules';

describe('getOrgMode', () => {
  it('PRISMA_ADMIN es global y gana sobre cualquier otro rol', () => {
    expect(getOrgMode(['PRISMA_ADMIN'])).toBe('none');
    expect(getOrgMode(['PRISMA_ADMIN', 'VIEWER', 'AUDITOR'])).toBe('none');
  });

  it('responsable y visualizador exigen exactamente una organización', () => {
    expect(getOrgMode(['ORG_RESPONSIBLE'])).toBe('single');
    expect(getOrgMode(['VIEWER'])).toBe('single');
    expect(getOrgMode(['ORG_RESPONSIBLE', 'INTERNAL_EVALUATOR'])).toBe('single');
    expect(getOrgMode(['VIEWER', 'AUDITOR'])).toBe('single');
  });

  it('el auditor necesita al menos una', () => {
    expect(getOrgMode(['AUDITOR'])).toBe('atLeastOne');
    expect(getOrgMode(['AUDITOR', 'INTERNAL_EVALUATOR'])).toBe('atLeastOne');
  });

  it('el evaluador interno puede tener cualquier cantidad (sin ninguna es general)', () => {
    expect(getOrgMode(['INTERNAL_EVALUATOR'])).toBe('any');
  });

  it('sin roles todavía deja marcar organizaciones libremente', () => {
    expect(getOrgMode([])).toBe('any');
  });
});

describe('validateOrganizationCount', () => {
  it('single: solo 1 es válido', () => {
    expect(validateOrganizationCount('single', 1)).toBeNull();
    expect(validateOrganizationCount('single', 0)).toContain('exactamente una');
    expect(validateOrganizationCount('single', 2)).toContain('exactamente una');
  });

  it('atLeastOne: 0 es inválido, 1 o más válido', () => {
    expect(validateOrganizationCount('atLeastOne', 0)).toContain('al menos una');
    expect(validateOrganizationCount('atLeastOne', 1)).toBeNull();
    expect(validateOrganizationCount('atLeastOne', 5)).toBeNull();
  });

  it('any y none aceptan cualquier cantidad, incluida cero', () => {
    for (const mode of ['any', 'none'] as const) {
      expect(validateOrganizationCount(mode, 0)).toBeNull();
      expect(validateOrganizationCount(mode, 3)).toBeNull();
    }
  });
});
