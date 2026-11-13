<template>
  <div
    class="min-h-screen bg-gradient-to-br from-slate-900 via-blue-900 to-slate-900 flex items-center justify-center p-4"
  >
    <div class="w-full max-w-md">
      <div class="text-center mb-8">
        <div
          class="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-blue-600/20 mb-4"
        >
          <ShieldCheckIcon class="w-10 h-10 text-blue-400" />
        </div>
        <h1 class="text-3xl font-bold text-white">PRISMA</h1>
        <p class="text-slate-400 mt-2">
          Plataforma de Revisión Integral de Seguridad y Marcos de Auditorías
        </p>
      </div>

      <div
        class="bg-white dark:bg-slate-800/10 backdrop-blur-lg rounded-2xl p-8 border border-white/20 shadow-2xl"
      >
        <p class="text-slate-600 dark:text-slate-300 text-sm text-center mb-6">
          El acceso se gestiona a través de Keycloak. Al continuar serás redirigido a la página de
          inicio de sesión segura.
        </p>

        <div
          v-if="passwordChanged"
          class="bg-emerald-500/20 border border-emerald-500/30 rounded-lg p-3 mb-5"
          data-testid="password-changed-notice"
        >
          <p class="text-emerald-700 dark:text-emerald-300 text-sm">
            Tu contraseña se actualizó correctamente. Iniciá sesión con tu nueva contraseña.
          </p>
        </div>

        <div v-if="error" class="bg-red-500/20 border border-red-500/30 rounded-lg p-3 mb-5">
          <p class="text-red-300 text-sm">
            {{ error }}
          </p>
        </div>

        <button
          type="button"
          :disabled="loading"
          class="w-full py-2.5 px-4 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:ring-offset-transparent disabled:opacity-50 disabled:cursor-not-allowed"
          @click="handleLogin"
        >
          <span v-if="loading" class="flex items-center justify-center gap-2">
            <svg class="animate-spin h-4 w-4" fill="none" viewBox="0 0 24 24">
              <circle
                class="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                stroke-width="4"
              />
              <path
                class="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
              />
            </svg>
            Redirigiendo...
          </span>
          <span v-else>Iniciar Sesión con Keycloak</span>
        </button>

        <div class="mt-6 pt-6 border-t border-white/10 text-center">
          <p class="text-xs text-slate-500 dark:text-slate-400">
            Autenticación gestionada por Keycloak
          </p>
        </div>
      </div>

      <p class="text-center text-xs text-slate-500 dark:text-slate-400 mt-6">
        Marco de Ciberseguridad de AGESIC v5.0
      </p>
      <p class="text-center text-xs text-slate-500 dark:text-slate-400 mt-1">
        PRISMA <AppVersion />
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref } from 'vue';
  import { useAuthStore } from '@/stores/auth';
  import AppVersion from '@/components/layout/AppVersion.vue';
  import { getErrorMessage } from '@/utils/helpers';
  import { PASSWORD_CHANGED_KEY } from '@/utils/constants';
  import { ShieldCheckIcon } from '@heroicons/vue/24/solid';

  const authStore = useAuthStore();

  // Viene de AccountView: se cambió la contraseña y se cerró la sesión. Se lee una sola vez.
  const passwordChanged = ref(false);
  try {
    passwordChanged.value = sessionStorage.getItem(PASSWORD_CHANGED_KEY) === '1';
    sessionStorage.removeItem(PASSWORD_CHANGED_KEY);
  } catch {
    // sin sessionStorage simplemente no se muestra el aviso
  }

  const loading = ref(false);
  const error = ref('');

  async function handleLogin() {
    loading.value = true;
    error.value = '';
    try {
      // authStore.login() redirige el navegador a Keycloak (Authorization
      // Code + PKCE). No hay navegación local a /dashboard aquí: el
      // regreso autenticado lo maneja el guard del router una vez que
      // Keycloak completa el intercambio del code al volver.
      await authStore.login();
    } catch (err) {
      error.value = getErrorMessage(err, 'Error al iniciar sesión');
      loading.value = false;
    }
  }
</script>
