import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { User, UserRole } from '@/types';
import {
  initKeycloak,
  loginKeycloak,
  logoutKeycloak,
  getKeycloak,
  updateToken,
} from '@/services/auth';
import { accountService } from '@/services/resources';
import { identifyLogRocketUser } from '@/plugins/logrocket';

// Marca en sessionStorage (no localStorage: es a propósito por pestaña, no por navegador) para
// no mandar un "LOGIN" a la bitácora en cada recarga de página -- Keycloak con onLoad:
// 'check-sso' re-autentica en silencio en cada init(), así que sin esta guarda cada F5 hubiera
// quedado registrado como un login nuevo.
const SESSION_LOGGED_KEY = 'prisma:session-login-logged';

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null);
  const isAuthenticated = ref(false);
  const loading = ref(false);
  const keycloakReady = ref(false);

  const roles = computed<UserRole[]>(() => {
    if (!keycloakReady.value) return [];
    const kc = getKeycloak();
    const realmRoles = kc.realmAccess?.roles || [];
    return realmRoles.filter((r): r is UserRole =>
      ['PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'INTERNAL_EVALUATOR', 'AUDITOR', 'VIEWER'].includes(r),
    );
  });

  const isAdmin = computed(() => roles.value.includes('PRISMA_ADMIN'));
  const isAuditor = computed(() => roles.value.includes('AUDITOR'));
  const isEvaluator = computed(() => roles.value.includes('INTERNAL_EVALUATOR'));

  const accessToken = computed(() => {
    if (!keycloakReady.value) return null;
    return getKeycloak()?.token || null;
  });

  async function init() {
    loading.value = true;
    try {
      const authenticated = await initKeycloak();
      keycloakReady.value = true;
      isAuthenticated.value = authenticated;
      if (authenticated) {
        await loadUser();
        await recordLoginOnce();
      }
    } catch (err) {
      console.error('Auth init error:', err);
    } finally {
      loading.value = false;
    }
  }

  async function login() {
    await loginKeycloak();
  }

  // Sólo una vez por pestaña (ver SESSION_LOGGED_KEY): init() se vuelve a correr en cada recarga
  // de página y Keycloak restaura la sesión en silencio, no es un login nuevo cada vez.
  async function recordLoginOnce() {
    if (sessionStorage.getItem(SESSION_LOGGED_KEY)) return;
    try {
      await accountService.recordSessionEvent('LOGIN');
      sessionStorage.setItem(SESSION_LOGGED_KEY, '1');
    } catch (err) {
      // No bloquea el login de la app por un error de red al registrar la bitácora.
      console.error('Error recording login event:', err);
    }
  }

  async function logout() {
    try {
      // Con await: si no se espera, el redirect de logoutKeycloak() (navegación de página
      // completa) puede cortar la request antes de que llegue al backend.
      await accountService.recordSessionEvent('LOGOUT');
    } catch (err) {
      console.error('Error recording logout event:', err);
    }
    sessionStorage.removeItem(SESSION_LOGGED_KEY);
    user.value = null;
    isAuthenticated.value = false;
    await logoutKeycloak();
  }

  async function loadUser() {
    const kc = getKeycloak();
    if (kc.tokenParsed) {
      user.value = {
        id: kc.subject || '',
        email: kc.tokenParsed.email || '',
        firstName: kc.tokenParsed.given_name || '',
        lastName: kc.tokenParsed.family_name || '',
        roles: roles.value,
        tenantId: kc.tokenParsed.tenant_id || '',
        enabled: true,
        createdAt: new Date().toISOString(),
        // Si tiene un JWT válido acá, por definición ya pudo loguearse.
        canLogin: true,
      };
      identifyLogRocketUser(user.value);
    }
  }

  async function refreshToken(): Promise<boolean> {
    try {
      const refreshed = await updateToken(30);
      if (refreshed) {
        await loadUser();
      }
      return refreshed;
    } catch {
      return false;
    }
  }

  // El nombre/apellido vienen del JWT (given_name/family_name), y keycloak-js solo los renueva
  // cuando efectivamente emite un token nuevo -- updateToken() no garantiza eso si el actual
  // todavía no está por vencer. Para que "Mi Cuenta" refleje la corrección al toque (sin esperar
  // al próximo login) se actualiza el estado local optimistamente después de guardar en el
  // backend; el JWT se termina de poner al día solo en el siguiente refresh real.
  function patchProfile(firstName: string, lastName: string) {
    if (user.value) {
      user.value = { ...user.value, firstName, lastName };
    }
  }

  function hasRole(role: UserRole): boolean {
    return roles.value.includes(role);
  }

  function hasAnyRole(...checkRoles: UserRole[]): boolean {
    return checkRoles.some((r) => roles.value.includes(r));
  }

  return {
    user,
    isAuthenticated,
    loading,
    keycloakReady,
    roles,
    isAdmin,
    isAuditor,
    isEvaluator,
    accessToken,
    init,
    login,
    logout,
    loadUser,
    refreshToken,
    patchProfile,
    hasRole,
    hasAnyRole,
  };
});
