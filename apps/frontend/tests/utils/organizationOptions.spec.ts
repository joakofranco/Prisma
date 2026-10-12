import { describe, it, expect } from 'vitest';
import {
  activeOrganizationOptions,
  filterOrganizationOptions,
  assignableOrganizationOptions,
} from '../../src/utils/organizationOptions';
import type { Organization } from '../../src/types';

function org(id: string, enabled: boolean): Organization {
  return {
    id,
    name: `Org ${id}`,
    rut: null,
    sector: 'TECH',
    size: 'SMALL',
    enabled,
    createdAt: '2026-01-01T00:00:00Z',
  };
}

const orgs = [org('a', true), org('x', false), org('b', true), org('y', false)];

describe('activeOrganizationOptions (crear)', () => {
  it('solo ofrece las organizaciones activas', () => {
    expect(activeOrganizationOptions(orgs).map((o) => o.value)).toEqual(['a', 'b']);
  });

  it('sin organizaciones devuelve vacío', () => {
    expect(activeOrganizationOptions([])).toEqual([]);
  });
});

describe('filterOrganizationOptions (filtro de evaluaciones)', () => {
  it('lista primero las activas y al final las inactivas marcadas "(inactiva)"', () => {
    const result = filterOrganizationOptions(orgs);
    expect(result.map((o) => o.value)).toEqual(['a', 'b', 'x', 'y']);
    expect(result.map((o) => o.label)).toEqual([
      'Org a',
      'Org b',
      'Org x (inactiva)',
      'Org y (inactiva)',
    ]);
  });
});

describe('assignableOrganizationOptions (asignar a un usuario)', () => {
  it('sin selección previa solo ofrece las activas', () => {
    expect(assignableOrganizationOptions(orgs, []).map((o) => o.value)).toEqual(['a', 'b']);
  });

  it('conserva, marcadas, las inactivas que el usuario ya tenía', () => {
    const result = assignableOrganizationOptions(orgs, ['x']);
    expect(result.map((o) => o.value)).toEqual(['a', 'x', 'b']);
    expect(result.find((o) => o.value === 'x')).toMatchObject({
      label: 'Org x (inactiva)',
      enabled: false,
    });
  });

  it('no suma otras inactivas que el usuario no tenía', () => {
    expect(assignableOrganizationOptions(orgs, ['x']).some((o) => o.value === 'y')).toBe(false);
  });
});
