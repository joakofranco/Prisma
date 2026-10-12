/**
 * Cuántas organizaciones corresponden según los roles de un usuario -- misma regla que
 * UserService.validateOrganizationsForRoles en el backend (el backend es quien manda; esto solo
 * adelanta el error en el formulario):
 *
 * - none:       PRISMA_ADMIN es global, no necesita ninguna.
 * - single:     ORG_RESPONSIBLE / VIEWER pertenecen a exactamente una.
 * - atLeastOne: AUDITOR audita una o varias (mínimo una).
 * - any:        INTERNAL_EVALUATOR puede tener una o varias; sin ninguna es un evaluador
 *               "general" que accede a todas. También es el modo mientras no hay roles
 *               elegidos, para poder marcar la organización antes de los roles.
 */
export type OrgMode = 'none' | 'single' | 'atLeastOne' | 'any';

export function getOrgMode(roles: readonly string[]): OrgMode {
  if (roles.includes('PRISMA_ADMIN')) return 'none';
  if (roles.includes('ORG_RESPONSIBLE') || roles.includes('VIEWER')) return 'single';
  if (roles.includes('AUDITOR')) return 'atLeastOne';
  return 'any';
}

/** Mensaje de error si la cantidad de organizaciones no corresponde al modo; null si es válida. */
export function validateOrganizationCount(mode: OrgMode, count: number): string | null {
  if (mode === 'single' && count !== 1) {
    return 'El responsable de organización y el visualizador deben pertenecer a exactamente una organización';
  }
  if (mode === 'atLeastOne' && count === 0) {
    return 'Seleccione al menos una organización para el auditor';
  }
  return null;
}
