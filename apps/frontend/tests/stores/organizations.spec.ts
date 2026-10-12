import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import type { Organization } from '../../src/types';

const service = vi.hoisted(() => ({
  getAll: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  delete: vi.fn(),
  reactivate: vi.fn(),
}));

vi.mock('../../src/services/resources', () => ({ organizationsService: service }));

import { useOrganizationsStore } from '../../src/stores/organizations';

function org(id: string, enabled = true): Organization {
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

describe('organizations store (C8/C9)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    service.getAll.mockResolvedValue({ data: { data: [org('a'), org('b')], total: 2 } });
  });

  it('deshabilitar NO saca la organización de la lista: queda Inactiva y el total no cambia', async () => {
    const store = useOrganizationsStore();
    await store.fetchOrganizations();
    service.delete.mockResolvedValue({});

    await store.deleteOrganization('a');

    expect(store.organizations.map((o) => o.id)).toEqual(['a', 'b']);
    expect(store.organizations.find((o) => o.id === 'a')?.enabled).toBe(false);
    expect(store.organizations.find((o) => o.id === 'b')?.enabled).toBe(true);
    expect(store.total).toBe(2);
  });

  it('si el backend rechaza deshabilitar, la lista queda intacta', async () => {
    const store = useOrganizationsStore();
    await store.fetchOrganizations();
    service.delete.mockRejectedValue(new Error('Keycloak caído'));

    await expect(store.deleteOrganization('a')).rejects.toThrow('Keycloak caído');

    expect(store.organizations.find((o) => o.id === 'a')?.enabled).toBe(true);
    expect(store.total).toBe(2);
  });

  it('reactivar vuelve a dejarla Activa y devuelve cuántos usuarios siguen deshabilitados', async () => {
    const store = useOrganizationsStore();
    service.getAll.mockResolvedValue({ data: { data: [org('a', false)], total: 1 } });
    await store.fetchOrganizations();
    service.reactivate.mockResolvedValue({
      data: { organization: org('a', true), disabledUsers: 2 },
    });

    const result = await store.reactivateOrganization('a');

    expect(result.disabledUsers).toBe(2);
    expect(store.organizations[0].enabled).toBe(true);
    expect(store.total).toBe(1);
  });

  it('tras deshabilitar y volver a pedir la lista, la organización sigue apareciendo inactiva', async () => {
    const store = useOrganizationsStore();
    service.getAll.mockResolvedValue({ data: { data: [org('a', false), org('b')], total: 2 } });

    await store.fetchOrganizations();

    expect(store.organizations).toHaveLength(2);
    expect(store.organizations[0].enabled).toBe(false);
  });
});
