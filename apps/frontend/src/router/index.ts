import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import type { UserRole } from '@/types';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { requiresAuth: false, layout: 'blank' },
  },
  {
    path: '/',
    redirect: '/dashboard',
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/dashboard/DashboardView.vue'),
    meta: { requiresAuth: true, title: 'Panel' },
  },
  {
    path: '/organizations',
    name: 'Organizations',
    component: () => import('@/views/organizations/OrganizationsView.vue'),
    meta: {
      requiresAuth: true,
      title: 'Organizaciones',
      roles: ['PRISMA_ADMIN', 'ORG_RESPONSIBLE'],
    },
  },
  {
    path: '/organizations/:id',
    name: 'OrganizationDetail',
    component: () => import('@/views/organizations/OrganizationDetailView.vue'),
    meta: { requiresAuth: true, title: 'Detalle Organización' },
  },
  {
    path: '/users',
    name: 'Users',
    component: () => import('@/views/users/UsersView.vue'),
    meta: { requiresAuth: true, title: 'Usuarios', roles: ['PRISMA_ADMIN', 'ORG_RESPONSIBLE'] },
  },
  {
    path: '/evaluations',
    name: 'Evaluations',
    component: () => import('@/views/evaluations/EvaluationsView.vue'),
    meta: { requiresAuth: true, title: 'Evaluaciones' },
  },
  {
    path: '/evaluations/new',
    name: 'NewEvaluation',
    component: () => import('@/views/evaluations/NewEvaluationView.vue'),
    meta: {
      requiresAuth: true,
      title: 'Nueva Evaluación',
      roles: ['PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'INTERNAL_EVALUATOR'],
    },
  },
  {
    path: '/evaluations/:id',
    name: 'EvaluationDetail',
    component: () => import('@/views/evaluations/EvaluationDetailView.vue'),
    meta: { requiresAuth: true, title: 'Detalle Evaluación' },
  },
  {
    path: '/evaluations/:id/respond',
    name: 'EvaluationRespond',
    component: () => import('@/views/evaluations/EvaluationRespondView.vue'),
    meta: {
      requiresAuth: true,
      title: 'Responder Evaluación',
      roles: ['PRISMA_ADMIN', 'INTERNAL_EVALUATOR'],
    },
  },
  {
    path: '/catalog',
    name: 'Catalog',
    component: () => import('@/views/catalog/CatalogView.vue'),
    meta: { requiresAuth: true, title: 'Catálogos' },
  },
  {
    path: '/community-profiles',
    name: 'CommunityProfiles',
    component: () => import('@/views/profiles/CommunityProfilesView.vue'),
    meta: { requiresAuth: true, title: 'Perfiles Comunitarios', roles: ['PRISMA_ADMIN'] },
  },
  {
    path: '/evidence/:evaluationId',
    name: 'Evidence',
    component: () => import('@/views/evidence/EvidenceView.vue'),
    meta: { requiresAuth: true, title: 'Evidencias' },
  },
  {
    path: '/audit/:evaluationId',
    name: 'Audit',
    component: () => import('@/views/audit/AuditView.vue'),
    meta: { requiresAuth: true, title: 'Auditoría', roles: ['PRISMA_ADMIN', 'AUDITOR'] },
  },
  {
    path: '/activity',
    name: 'ActivityLog',
    component: () => import('@/views/activity/ActivityLogView.vue'),
    meta: {
      requiresAuth: true,
      title: 'Actividad del Sistema',
      roles: ['PRISMA_ADMIN', 'ORG_RESPONSIBLE'],
    },
  },
  {
    path: '/reports',
    name: 'Reports',
    component: () => import('@/views/reports/ReportsView.vue'),
    meta: { requiresAuth: true, title: 'Reportes' },
  },
  {
    path: '/improvement/:evaluationId',
    name: 'ImprovementPlan',
    component: () => import('@/views/improvement/ImprovementView.vue'),
    meta: { requiresAuth: true, title: 'Plan de Mejora' },
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/settings/SettingsView.vue'),
    meta: { requiresAuth: true, title: 'Configuración', roles: ['PRISMA_ADMIN'] },
  },
  {
    path: '/account',
    name: 'Account',
    component: () => import('@/views/account/AccountView.vue'),
    meta: { requiresAuth: true, title: 'Mi Cuenta' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/errors/NotFoundView.vue'),
    meta: { layout: 'blank' },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 };
  },
});

router.beforeEach(async (to, _from, next) => {
  const authStore = useAuthStore();

  if (!authStore.keycloakReady) {
    await authStore.init();
  }

  if (to.meta.requiresAuth !== false && !authStore.isAuthenticated) {
    next({ name: 'Login' });
    return;
  }

  if (to.name === 'Login' && authStore.isAuthenticated) {
    next({ name: 'Dashboard' });
    return;
  }

  const requiredRoles = to.meta.roles as UserRole[] | undefined;
  if (requiredRoles && requiredRoles.length > 0) {
    const hasAccess = authStore.hasAnyRole(...requiredRoles);
    if (!hasAccess) {
      next({ name: 'Dashboard' });
      return;
    }
  }

  next();
});

export default router;
