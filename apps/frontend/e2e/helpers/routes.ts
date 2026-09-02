/**
 * Rutas autenticadas "estáticas" (no requieren un :id de datos ya existentes en la base) que ve
 * un PRISMA_ADMIN -- ver apps/frontend/src/router/index.ts. Cubren todo el menú principal; las
 * rutas con :id/:evaluationId (OrganizationDetail, EvaluationDetail, Evidence, Audit,
 * ImprovementPlan) quedan fuera porque dependen de datos sembrados que no están garantizados en
 * un ambiente de test recién levantado.
 */
export const AUTHENTICATED_ROUTES = [
  { path: '/dashboard', name: 'Dashboard', heading: /panel/i },
  { path: '/organizations', name: 'Organizaciones', heading: /organizaciones/i },
  { path: '/users', name: 'Usuarios', heading: /usuarios/i },
  { path: '/evaluations', name: 'Evaluaciones', heading: /evaluaciones/i },
  { path: '/evaluations/new', name: 'Nueva evaluación', heading: /nueva evaluación/i },
  { path: '/catalog', name: 'Catálogo', heading: /catálogo/i },
  { path: '/community-profiles', name: 'Perfiles comunitarios', heading: /perfiles/i },
  { path: '/reports', name: 'Reportes', heading: /reportes/i },
  { path: '/settings', name: 'Configuración', heading: /configuración/i },
  { path: '/account', name: 'Mi cuenta', heading: /cuenta/i },
] as const;
