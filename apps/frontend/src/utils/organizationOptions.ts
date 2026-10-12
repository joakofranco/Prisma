import type { Organization } from '@/types';

export interface OrganizationOption {
  value: string;
  label: string;
  enabled: boolean;
}

const INACTIVE_SUFFIX = ' (inactiva)';

/**
 * Opciones para elegir una organización al CREAR algo (nueva evaluación): solo las activas. Una
 * organización inactiva conserva su historial pero no admite actividad nueva (el backend también lo
 * rechaza).
 */
export function activeOrganizationOptions(orgs: readonly Organization[]): OrganizationOption[] {
  return orgs.filter((o) => o.enabled).map((o) => ({ value: o.id, label: o.name, enabled: true }));
}

/**
 * Opciones para FILTRAR (lista de evaluaciones): las activas y, al final, las inactivas marcadas
 * "(inactiva)" -- sus evaluaciones siguen siendo historial consultable.
 */
export function filterOrganizationOptions(orgs: readonly Organization[]): OrganizationOption[] {
  const active = activeOrganizationOptions(orgs);
  const inactive = orgs
    .filter((o) => !o.enabled)
    .map((o) => ({ value: o.id, label: o.name + INACTIVE_SUFFIX, enabled: false }));
  return [...active, ...inactive];
}

/**
 * Opciones para ASIGNAR organizaciones a un usuario: las activas, más las inactivas que el usuario
 * YA tiene (marcadas "(inactiva)") para que no desaparezcan de su selección al editarlo ni se
 * pierdan al guardar. No se puede sumar una inactiva nueva.
 */
export function assignableOrganizationOptions(
  orgs: readonly Organization[],
  keepIds: readonly string[],
): OrganizationOption[] {
  const keep = new Set(keepIds);
  return orgs
    .filter((o) => o.enabled || keep.has(o.id))
    .map((o) => ({
      value: o.id,
      label: o.enabled ? o.name : o.name + INACTIVE_SUFFIX,
      enabled: o.enabled,
    }));
}
