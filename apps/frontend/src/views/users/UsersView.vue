<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Usuarios</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Gestión de usuarios del sistema
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <BaseButton variant="secondary" @click="openCreateModal">
          <PlusIcon class="w-4 h-4" />
          Nuevo Usuario
        </BaseButton>
        <!-- RF-USR-05 (PRI-240): alta por invitación, sin contraseña que pasar a mano. -->
        <BaseButton variant="primary" data-testid="open-invite" @click="showInviteModal = true">
          <EnvelopeIcon class="w-4 h-4" />
          Invitar evaluador
        </BaseButton>
      </div>
    </div>

    <BaseAlert
      v-if="inviteSentTo"
      variant="success"
      dismissible
      data-testid="invite-sent"
      @dismiss="inviteSentTo = ''"
    >
      <strong>Invitación enviada a {{ inviteSentTo }}.</strong> Le llega un correo para crear su
      contraseña; el enlace vence en 72 horas.
    </BaseAlert>

    <div class="flex items-center gap-3">
      <div class="relative flex-1 max-w-md">
        <MagnifyingGlassIcon
          class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400"
        />
        <input
          v-model="searchTerm"
          type="text"
          placeholder="Buscar usuarios..."
          aria-label="Buscar usuarios"
          class="w-full pl-10 pr-4 py-2 rounded-lg border border-slate-300 dark:border-slate-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder:text-slate-500 dark:placeholder:text-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          @input="debouncedSearch"
        />
      </div>
    </div>

    <DataTable
      :columns="columns"
      :data="users"
      :loading="loading"
      :show-pagination="true"
      :current-page="currentPage"
      :page-size="pageSize"
      :total-items="total"
      @page-change="handlePageChange"
    >
      <template #cell-organizationNames="{ value, row }">
        <span
          v-if="(value as string[]).length > 0"
          class="text-slate-700 dark:text-slate-200"
          :title="(value as string[]).join(', ')"
        >
          {{ (value as string[]).join(', ') }}
        </span>
        <span v-else class="text-slate-500 dark:text-slate-400">{{
          noOrganizationLabel(row as User)
        }}</span>
      </template>
      <template #cell-roles="{ value }">
        <div class="flex flex-wrap gap-1">
          <span
            v-for="role in value as UserRole[]"
            :key="role"
            class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300"
          >
            {{ ROLE_LABELS[role] || role }}
          </span>
        </div>
      </template>
      <template #cell-enabled="{ value }">
        <span
          :class="[
            'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
            value
              ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300'
              : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300',
          ]"
        >
          {{ value ? 'Activo' : 'Inactivo' }}
        </span>
      </template>
      <template #cell-canLogin="{ value, row }">
        <div v-if="isInvitationPending(row as User)" data-testid="invitation-pending">
          <span
            class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300"
          >
            Invitación pendiente
          </span>
          <span class="block mt-1 text-xs text-slate-500 dark:text-slate-400">
            Enviada el {{ formatDate((row as User).invitedAt as string) }}
          </span>
          <button
            type="button"
            class="mt-1 text-xs font-semibold text-amber-700 hover:text-amber-900 hover:underline disabled:opacity-50 dark:text-amber-300 dark:hover:text-amber-200"
            data-testid="resend-invitation"
            :disabled="resendingId === (row as User).id"
            @click="resendInvitation(row as User)"
          >
            {{ resendingId === (row as User).id ? 'Reenviando…' : 'Reenviar invitación' }}
          </button>
        </div>
        <span
          v-else-if="value && (row as User).locked !== true"
          class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300"
        >
          Puede iniciar sesión
        </span>
        <!-- RF-AUTH-08: bloqueada por intentos fallidos. Hasta que pase el tiempo o un administrador
        la desbloquee no puede iniciar sesión, aunque esté «Activa». -->
        <div v-else-if="(row as User).locked === true" data-testid="account-locked">
          <span
            class="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300"
          >
            <LockClosedIcon class="w-3 h-3" aria-hidden="true" />
            Cuenta bloqueada
          </span>
          <span
            v-if="(row as User).lockedUntil"
            class="block mt-1 text-xs text-slate-500 dark:text-slate-400"
            data-testid="locked-until"
          >
            Hasta las {{ formatTime((row as User).lockedUntil as string) }}
          </span>
        </div>
        <span
          v-if="(row as User).invitationAcceptedAt"
          class="block mt-1 text-xs text-slate-500 dark:text-slate-400"
        >
          Aceptó la invitación el {{ formatDate((row as User).invitationAcceptedAt as string) }}
        </span>
        <span
          v-else-if="!value"
          class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300"
          title="Todavía no tiene cuenta para loguearse: edite el usuario y fíjele una contraseña"
        >
          Sin acceso todavía
        </span>
      </template>
      <template #actions="{ row }">
        <button
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent dark:hover:text-slate-200"
          :title="
            (row as User).id === authStore.user?.id
              ? 'No podés deshabilitar tu propia cuenta'
              : (row as User).enabled
                ? 'Deshabilitar usuario'
                : 'Habilitar usuario'
          "
          :disabled="(row as User).enabled && (row as User).id === authStore.user?.id"
          @click="toggleEnabled(row as User)"
        >
          <NoSymbolIcon v-if="(row as User).enabled" class="w-4 h-4" />
          <CheckCircleIcon v-else class="w-4 h-4" />
        </button>
        <button
          v-if="isPlatformAdmin && (row as User).canLogin"
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
          :class="[
            (row as User).locked === true
              ? 'bg-red-50 text-red-600 hover:bg-red-100 dark:bg-red-950 dark:text-red-300 dark:hover:bg-red-900'
              : '',
            (row as User).locked === false ? 'opacity-40' : '',
          ]"
          :title="unlockTitle(row as User)"
          aria-label="Desbloquear cuenta"
          data-testid="unlock-user"
          :data-locked="String((row as User).locked ?? 'unknown')"
          @click="confirmUnlock(row as User)"
        >
          <LockOpenIcon class="w-4 h-4" />
        </button>
        <button
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
          title="Editar usuario"
          @click="editUser(row)"
        >
          <PencilIcon class="w-4 h-4" />
        </button>
        <button
          class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 dark:text-slate-400 hover:text-red-600 dark:hover:bg-red-950 dark:hover:text-red-300"
          title="Eliminar usuario"
          @click="confirmDelete(row)"
        >
          <TrashIcon class="w-4 h-4" />
        </button>
      </template>
    </DataTable>

    <BaseModal
      v-model="showCreateModal"
      :title="editingUser ? 'Editar Usuario' : 'Nuevo Usuario'"
      size="lg"
    >
      <form class="space-y-4" @submit.prevent="handleSubmit">
        <div
          v-if="guidedNewOrgName && !editingUser"
          class="rounded-lg bg-blue-50 border border-blue-200 px-3 py-2 text-xs text-blue-700 dark:bg-blue-950 dark:text-blue-300 dark:border-blue-800"
        >
          Estás asignando el responsable de <strong>{{ guidedNewOrgName }}</strong
          >. Una vez creado, vas a poder sumar evaluadores, auditores y visualizadores a esta
          organización.
        </div>
        <div class="grid grid-cols-2 gap-4">
          <BaseInput
            v-model="form.firstName"
            label="Nombre"
            placeholder="Nombre"
            :error="errors.firstName"
            required
          />
          <BaseInput
            v-model="form.lastName"
            label="Apellido"
            placeholder="Apellido"
            :error="errors.lastName"
            required
          />
        </div>
        <div class="grid grid-cols-1 xl:grid-cols-2 gap-4">
          <BaseInput
            v-model="form.email"
            label="Email"
            type="email"
            placeholder="usuario@prisma.local"
            :error="errors.email"
            required
          />
          <div>
            <!-- Alta: la contraseña se genera sola y se muestra en claro para poder copiarla antes
                 de crear el usuario. Edición: campo oculto, sin generador (la clave que se fija
                 ahí queda permanente, a diferencia de la del alta, que es temporal). -->
            <BaseInput
              v-model="form.password"
              :label="editingUser ? 'Contraseña' : 'Contraseña temporal'"
              :type="editingUser ? 'password' : 'text'"
              placeholder="••••••••"
              :error="errors.password"
              :required="!editingUser"
            />
            <div v-if="!editingUser" class="mt-2 flex flex-wrap items-center gap-2">
              <BaseButton
                variant="secondary"
                type="button"
                data-testid="copy-password"
                @click="copyPassword"
              >
                <ClipboardDocumentIcon class="w-4 h-4" /> Copiar
              </BaseButton>
              <BaseButton
                variant="outline"
                type="button"
                data-testid="regenerate-password"
                @click="form.password = generatePassword()"
              >
                <ArrowPathIcon class="w-4 h-4" /> Generar otra
              </BaseButton>
            </div>
            <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">
              {{
                editingUser
                  ? 'Dejar en blanco para no cambiarla. Fijar una acá es lo que le habilita el acceso a un usuario que todavía no puede iniciar sesión.'
                  : 'Es temporal: el usuario deberá cambiarla en su primer inicio de sesión. Copiela y compártasela antes de crear el usuario; después no se vuelve a mostrar.'
              }}
            </p>
            <!-- PRI-365: al cambiar la contraseña de otro usuario, el backend (solo para PRISMA_ADMIN)
                 le quita el MFA registrado. Se avisa ANTES de guardar. -->
            <BaseAlert
              v-if="willRemoveMfa"
              variant="warning"
              class="mt-2"
              data-testid="mfa-removal-warning"
            >
              <strong
                >Vas a cambiar la contraseña de este usuario y se le va a quitar el MFA.</strong
              >
              Si tenía un autenticador registrado, deja de valer: en su próximo inicio de sesión
              tendrá que registrar uno nuevo (si la MFA es obligatoria para su rol) y entrar con la
              contraseña que le pases. Usalo cuando haya perdido el acceso a su autenticador. Si
              solo olvidó la contraseña, que use «¿Olvidaste tu contraseña?» en el login: no
              necesita que se la restablezcas.
            </BaseAlert>
            <p
              v-if="!editingUser && mfaRoleSelected"
              class="mt-1 text-xs text-amber-700 dark:text-amber-400"
              data-testid="mfa-first-login-hint"
            >
              Este rol requiere MFA: si la MFA obligatoria está activa, en ese mismo primer inicio
              de sesión también se le pedirá registrar la aplicación autenticadora, además de
              cambiar la contraseña. Son dos pasos distintos; el MFA no se vuelve a pedir al cambiar
              la contraseña más adelante.
            </p>
          </div>
        </div>
        <div v-if="orgMode === 'none'">
          <label class="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1"
            >Organizaciones</label
          >
          <p class="text-xs text-slate-500 dark:text-slate-400">{{ organizationsHint }}</p>
        </div>
        <div v-else>
          <!-- Selector con buscador y etiquetas (escala con muchas organizaciones). Responsable /
               visualizador: exactamente una (elegir otra reemplaza a la actual). -->
          <BaseMultiSelect
            v-model="form.organizationIds"
            label="Organizaciones"
            placeholder="Buscar y elegir organizaciones…"
            :options="orgOptions"
            :max="orgMode === 'single' ? 1 : undefined"
            :disabled="!isPlatformAdmin"
            :error="errors.organizationIds"
          />
          <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ organizationsHint }}</p>
        </div>
        <label
          class="flex items-center gap-2"
          :class="{ 'opacity-50': isEditingOwnAccount }"
          :title="isEditingOwnAccount ? 'No podés deshabilitar tu propia cuenta' : undefined"
        >
          <input
            v-model="form.enabled"
            type="checkbox"
            :disabled="isEditingOwnAccount"
            class="rounded border-slate-300 dark:border-slate-500 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed dark:text-blue-400"
          />
          <span class="text-sm text-slate-700 dark:text-slate-200">Usuario habilitado</span>
        </label>
        <p class="text-xs text-slate-400 -mt-2">
          Un usuario deshabilitado no puede iniciar sesión, pero conserva su historial
          (evaluaciones, evidencia, auditoría) intacto -- a diferencia de eliminarlo.
        </p>
        <div>
          <label class="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2"
            >Roles</label
          >
          <p v-if="needsResponsibleFirst" class="text-xs text-amber-700 mb-2 dark:text-amber-400">
            Esta organización todavía no tiene un Responsable asignado. Elegí primero el rol
            "Responsable de Organización" -- después vas a poder sumar Evaluadores, Auditores y
            Visualizadores.
          </p>
          <div class="space-y-2">
            <label
              v-for="role in visibleRoleOptions"
              :key="role.value"
              class="flex items-center gap-2"
              :class="{ 'opacity-50': isRoleLocked(role.value) }"
            >
              <input
                v-model="form.roles"
                type="checkbox"
                :value="role.value"
                :disabled="isRoleLocked(role.value)"
                class="rounded border-slate-300 dark:border-slate-500 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed dark:text-blue-400"
              />
              <span class="text-sm text-slate-700 dark:text-slate-200">{{ role.label }}</span>
            </label>
          </div>
          <p v-if="errors.roles" class="mt-1 text-xs text-red-600 dark:text-red-400">
            {{ errors.roles }}
          </p>
        </div>
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showCreateModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="saving" @click="handleSubmit">
          {{ editingUser ? 'Guardar Cambios' : 'Crear Usuario' }}
        </BaseButton>
      </template>
    </BaseModal>

    <InviteEvaluatorModal
      v-model="showInviteModal"
      :is-platform-admin="isPlatformAdmin"
      :own-organization-name="ownOrganizationName"
      :organization-options="inviteOrganizationOptions"
      :email-configured="emailConfigured"
      @invited="onInvited"
    />
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, computed, watch, onMounted } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useRoute, useRouter } from 'vue-router';
  import { useUsersStore } from '@/stores/users';
  import { useOrganizationsStore } from '@/stores/organizations';
  import { useAuthStore } from '@/stores/auth';
  import { generatePassword } from '@/utils/password';
  import { usersService } from '@/services/resources';
  import { useNotification } from '@/composables/useUtils';
  import { ROLE_LABELS } from '@/utils/constants';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import { emailSchema, personNameSchema } from '@/utils/validators';
  import { getOrgMode, validateOrganizationCount } from '@/utils/organizationRules';
  import { assignableOrganizationOptions } from '@/utils/organizationOptions';
  import BaseMultiSelect from '@/components/common/BaseMultiSelect.vue';
  import DataTable from '@/components/common/DataTable.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import InviteEvaluatorModal from '@/components/users/InviteEvaluatorModal.vue';
  import {
    PlusIcon,
    MagnifyingGlassIcon,
    PencilIcon,
    TrashIcon,
    NoSymbolIcon,
    LockOpenIcon,
    LockClosedIcon,
    CheckCircleIcon,
    ClipboardDocumentIcon,
    ArrowPathIcon,
    EnvelopeIcon,
  } from '@heroicons/vue/24/outline';
  import type { User, UserRole } from '@/types';

  const store = useUsersStore();
  const orgStore = useOrganizationsStore();
  const authStore = useAuthStore();
  const notification = useNotification();
  const route = useRoute();
  const router = useRouter();

  // ORG_RESPONSIBLE llega a esta misma pantalla como "administrador" de su propia organización
  // (ver router/index.ts y AppSidebar.vue), pero solo puede gestionar usuarios DENTRO de ella --
  // UserService ya lo exige del lado del backend; acá se refleja en la UI para que no intente
  // algo que el backend igual va a rechazar (elegir otra organización, otorgar el rol global).
  const isPlatformAdmin = computed(() => authStore.isAdmin);

  // Ver el comentario en OrganizationsView.vue: hay que usar storeToRefs, no desestructurar el
  // store directamente, o la tabla deja de reaccionar a fetchUsers().
  const { users, total, loading } = storeToRefs(store);
  const currentPage = ref(1);
  const pageSize = 10;
  const searchTerm = ref('');
  const showCreateModal = ref(false);
  const editingUser = ref<User | null>(null);
  const saving = ref(false);

  const columns = [
    { key: 'firstName', label: 'Nombre' },
    { key: 'lastName', label: 'Apellido' },
    { key: 'email', label: 'Email' },
    { key: 'organizationNames', label: 'Organizaciones' },
    { key: 'roles', label: 'Roles' },
    { key: 'enabled', label: 'Estado' },
    { key: 'canLogin', label: 'Acceso' },
  ];

  const form = reactive({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    organizationIds: [] as string[],
    roles: [] as string[],
    enabled: true,
  });

  // El backend rechaza deshabilitar la propia cuenta (dejaría a quien lo hace fuera del sistema
  // en el acto, sin nadie que pueda revertirlo -- ver UserService.update): se refleja acá para no
  // dejar tildar el checkbox y recién enterarse del error al guardar.
  const isEditingOwnAccount = computed(
    () => !!editingUser.value && editingUser.value.id === authStore.user?.id,
  );
  // Roles para los que `make mfa-enable` exige MFA (ver scripts/keycloak/enable-mfa.sh).
  const MFA_ROLES = ['PRISMA_ADMIN', 'ORG_RESPONSIBLE'];
  // Solo un PRISMA_ADMIN que edita a OTRO usuario y le fija una contraseña: es el caso en que el
  // backend le quita el MFA (ver UserService.removeMfaIfAdminResetsPassword).
  const willRemoveMfa = computed(
    () =>
      !!editingUser.value &&
      isPlatformAdmin.value &&
      !isEditingOwnAccount.value &&
      form.password.trim().length > 0,
  );
  const mfaRoleSelected = computed(() => form.roles.some((r) => MFA_ROLES.includes(r)));

  const errors = reactive({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    roles: '',
    organizationIds: '',
  });

  const roleOptions = [
    { value: 'PRISMA_ADMIN', label: 'Administrador' },
    { value: 'ORG_RESPONSIBLE', label: 'Responsable de Organización' },
    { value: 'INTERNAL_EVALUATOR', label: 'Evaluador Interno' },
    { value: 'AUDITOR', label: 'Auditor' },
    { value: 'VIEWER', label: 'Visualizador' },
  ];

  // El rol global PRISMA_ADMIN no se ofrece cuando quien gestiona usuarios es un ORG_RESPONSIBLE:
  // el backend lo rechaza de todas formas (ver UserService.assertRolesAssignableByOrgAdmin), pero
  // mostrarlo igual invitaría a un intento que siempre termina en error.
  const visibleRoleOptions = computed(() =>
    isPlatformAdmin.value ? roleOptions : roleOptions.filter((r) => r.value !== 'PRISMA_ADMIN'),
  );

  // Activas + las inactivas que el usuario editado YA tiene (marcadas "(inactiva)"): no se puede
  // sumar una organización inactiva nueva (el backend también lo rechaza), pero tampoco deben
  // desaparecer de la selección de quien ya estaba en ella.
  const orgOptions = computed(() =>
    assignableOrganizationOptions(orgStore.organizations, editingUser.value?.organizationIds ?? []),
  );

  // Cuántas organizaciones corresponden según los roles elegidos (ver utils/organizationRules).
  const orgMode = computed(() => getOrgMode(form.roles));

  const organizationsHint = computed(() => {
    switch (orgMode.value) {
      case 'none':
        return 'El administrador tiene acceso global a la plataforma: no necesita organización.';
      case 'single':
        return 'Seleccione exactamente una organización.';
      case 'atLeastOne':
        return 'El auditor solo va a poder ver y auditar las organizaciones que seleccione (al menos una).';
      default:
        return form.roles.includes('INTERNAL_EVALUATOR')
          ? 'Puede elegir una o varias. Sin ninguna, el evaluador es general y accede a todas las organizaciones.'
          : 'Elija la organización (o varias, según el rol) y luego los roles.';
    }
  });

  // Qué mostrar en la grilla cuando el usuario no tiene organizaciones asignadas.
  function noOrganizationLabel(user: User): string {
    if (user.roles.includes('PRISMA_ADMIN')) return 'Global (todas)';
    if (user.roles.includes('INTERNAL_EVALUATOR')) return 'General (todas)';
    return 'Sin organización';
  }

  // Regla de UI: una organización debe tener su ORG_RESPONSIBLE antes de poder sumarle
  // Evaluadores, Auditores o Visualizadores. No es una restricción del modelo de datos --
  // el backend no la valida -- es solo una guía de orden en el formulario de creación.
  const guidedNewOrgName = ref('');
  const orgHasResponsible = ref(false);
  const orgResponsibleCache = new Map<string, boolean>();

  // Roles que el usuario YA tenía al abrir el formulario (vacío si es un alta nueva). Sin esto,
  // editar a un usuario que ya tenía p.ej. VIEWER en una organización que hoy no tiene
  // responsable (una asignada antes de esta regla, o cuyo responsable se borró después) quedaba
  // bloqueado para CUALQUIER cambio -- incluso corregirle el nombre sin tocar los roles -- porque
  // el chequeo de abajo no distinguía "ya lo tenía" de "se lo estoy por sumar ahora".
  const originalRoles = ref<string[]>([]);

  async function refreshOrgHasResponsible(orgId: string) {
    if (!orgId) {
      orgHasResponsible.value = false;
      return;
    }
    if (orgResponsibleCache.has(orgId)) {
      orgHasResponsible.value = orgResponsibleCache.get(orgId) as boolean;
      return;
    }
    try {
      // pageSize alto: alcanza para chequear si ya existe un responsable sin necesitar un
      // endpoint de filtrado dedicado en el backend (ver comentario arriba).
      const { data } = await usersService.getAll({ page: 1, pageSize: 200 });
      const hasResponsible = data.data.some(
        (u) =>
          u.organizationIds?.includes(orgId) &&
          u.roles.includes('ORG_RESPONSIBLE') &&
          (!editingUser.value || u.id !== editingUser.value.id),
      );
      orgResponsibleCache.set(orgId, hasResponsible);
      orgHasResponsible.value = hasResponsible;
    } catch (err) {
      console.error('Error checking organization responsible:', err);
      // Fail-open: un error de red acá no debería bloquear la creación de usuarios.
      orgHasResponsible.value = true;
    }
  }

  // Solo tiene sentido con exactamente UNA organización elegida (una con varias no tiene "su"
  // responsable): se consulta cuándo cambia esa selección única.
  const singleSelectedOrgId = computed(() =>
    form.organizationIds.length === 1 ? form.organizationIds[0] : '',
  );

  watch(singleSelectedOrgId, (orgId) => {
    if (orgId) refreshOrgHasResponsible(orgId);
    else orgHasResponsible.value = false;
  });

  const needsResponsibleFirst = computed(
    () =>
      !!singleSelectedOrgId.value &&
      !orgHasResponsible.value &&
      !form.roles.includes('ORG_RESPONSIBLE'),
  );

  const RESPONSIBLE_GATED_ROLES = ['INTERNAL_EVALUATOR', 'AUDITOR', 'VIEWER'];

  function isRoleLocked(roleValue: string): boolean {
    if (!RESPONSIBLE_GATED_ROLES.includes(roleValue)) return false;
    return needsResponsibleFirst.value && !form.roles.includes(roleValue);
  }

  // ---- RF-USR-05 (PRI-240): invitación por correo ----
  const showInviteModal = ref(false);
  const inviteSentTo = ref('');
  const resendingId = ref('');
  // null = no se pudo consultar: se deja intentar y el backend responde si falta el correo.
  const emailConfigured = ref<boolean | null>(null);
  // El responsable solo ve (y solo puede invitar a) su propia organización.
  const ownOrganizationName = computed(() => orgOptions.value[0]?.label ?? '');
  const inviteOrganizationOptions = computed(() =>
    assignableOrganizationOptions(orgStore.organizations, []),
  );

  function isInvitationPending(user: User): boolean {
    return !!user.invitedAt && !user.invitationAcceptedAt;
  }

  async function loadInvitationAvailability() {
    try {
      const { data } = await usersService.invitationAvailability();
      emailConfigured.value = data.emailConfigured;
    } catch {
      emailConfigured.value = null;
    }
  }

  async function onInvited(user: User) {
    inviteSentTo.value = user.email;
    currentPage.value = 1;
    await store.fetchUsers(1, pageSize, searchTerm.value);
  }

  async function resendInvitation(user: User) {
    if (
      !confirm(
        `¿Reenviar la invitación a ${user.email}? Le llega un correo nuevo con un enlace que vence en 72 horas.`,
      )
    ) {
      return;
    }
    resendingId.value = user.id;
    try {
      const { data } = await usersService.resendInvitation(user.id);
      const index = users.value.findIndex((u) => u.id === user.id);
      if (index !== -1) users.value[index] = data;
      notification.success(`Invitación reenviada a ${user.email}`);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo reenviar la invitación'));
    } finally {
      resendingId.value = '';
    }
  }

  function openCreateModal() {
    editingUser.value = null;
    guidedNewOrgName.value = '';
    resetForm();
    if (!isPlatformAdmin.value && orgOptions.value.length > 0) {
      // Un ORG_RESPONSIBLE solo ve (y solo puede elegir) su propia organización -- se la
      // preseleccionamos ya que el campo queda deshabilitado para ese rol.
      form.organizationIds = [orgOptions.value[0].value];
    }
    form.password = generatePassword();
    showCreateModal.value = true;
  }

  // Copia la contraseña generada al portapapeles. Si el navegador no lo permite (contexto no
  // seguro, permiso denegado), no se falla en silencio: se avisa y se la deja seleccionable para
  // copiarla a mano.
  async function copyPassword() {
    try {
      await navigator.clipboard.writeText(form.password);
      notification.success('Contraseña copiada al portapapeles');
    } catch {
      notification.warning('No se pudo copiar automáticamente: selecciónela y cópiela a mano');
    }
  }

  let searchTimeout: ReturnType<typeof setTimeout>;
  function debouncedSearch() {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
      currentPage.value = 1;
      store.fetchUsers(1, pageSize, searchTerm.value);
    }, 300);
  }

  function handlePageChange(page: number) {
    currentPage.value = page;
    store.fetchUsers(page, pageSize, searchTerm.value);
  }

  function editUser(user: User) {
    editingUser.value = user;
    guidedNewOrgName.value = '';
    form.firstName = user.firstName;
    form.lastName = user.lastName;
    form.email = user.email;
    form.password = '';
    form.organizationIds = [...(user.organizationIds || [])];
    form.roles = [...user.roles];
    form.enabled = user.enabled;
    originalRoles.value = [...user.roles];
    showCreateModal.value = true;
  }

  // Acción rápida desde la grilla (sin abrir el modal de edición completo) -- manda los mismos
  // campos requeridos que ya trae la fila (email/nombre/roles/etc, sin tocarlos) con "enabled"
  // invertido. UserService.update() sólo pisa lo que efectivamente cambia (ver el comentario ahí
  // sobre los campos que llegan null), así que esto no afecta nada más del usuario.
  async function toggleEnabled(user: User) {
    const verb = user.enabled ? 'deshabilitar' : 'habilitar';
    if (!confirm(`¿Está seguro que desea ${verb} a ${user.firstName} ${user.lastName}?`)) return;
    try {
      await store.updateUser(user.id, {
        email: user.email,
        firstName: user.firstName,
        lastName: user.lastName,
        enabled: !user.enabled,
        roles: user.roles,
        organizationIds: user.organizationIds || [],
      });
      notification.success(
        `Usuario ${user.enabled ? 'deshabilitado' : 'habilitado'} correctamente`,
      );
    } catch (err) {
      notification.error(getErrorMessage(err, `Error al ${verb} el usuario`));
    }
  }

  // RF-AUTH-08: Keycloak bloquea la cuenta tras 10 intentos fallidos seguidos en 15 minutos. El
  // backend informa el estado real de cada usuario de la página (`locked`, `lockedUntil`): la
  // columna «Acceso» lo muestra y el candado se resalta solo en las cuentas bloqueadas. El
  // administrador global puede levantar el bloqueo sin esperar; el botón sigue disponible aunque la
  // fila no figure bloqueada (el estado puede haber cambiado desde que se cargó la lista) y el
  // backend dice si realmente había algo que limpiar.
  function formatTime(iso: string): string {
    return new Date(iso).toLocaleTimeString('es-UY', { hour: '2-digit', minute: '2-digit' });
  }

  function unlockTitle(user: User): string {
    if (user.locked === true) {
      return user.lockedUntil
        ? `Desbloquear cuenta (bloqueada hasta las ${formatTime(user.lockedUntil)})`
        : 'Desbloquear cuenta (bloqueada)';
    }
    if (user.locked === false) return 'Desbloquear cuenta (no está bloqueada)';
    return 'Desbloquear cuenta';
  }

  async function confirmUnlock(user: User) {
    if (
      !confirm(
        `¿Desbloquear la cuenta de ${user.firstName} ${user.lastName}? Podrá volver a iniciar sesión de inmediato.`,
      )
    ) {
      return;
    }
    try {
      const { data } = await usersService.unlock(user.id);
      if (data.wasLocked) {
        notification.success(`Cuenta de ${user.firstName} ${user.lastName} desbloqueada`);
      } else {
        notification.warning(`La cuenta de ${user.firstName} ${user.lastName} no estaba bloqueada`);
      }
      // Se vuelve a pedir la página para que la fila refleje el estado real.
      await store.fetchUsers(currentPage.value, pageSize, searchTerm.value);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo desbloquear la cuenta'));
    }
  }

  function confirmDelete(user: User) {
    if (confirm(`¿Está seguro que desea eliminar al usuario ${user.firstName} ${user.lastName}?`)) {
      handleDeleteUser(user.id);
    }
  }

  async function handleDeleteUser(id: string) {
    try {
      await store.deleteUser(id);
      // El usuario eliminado podía ser el ORG_RESPONSIBLE de alguna organización: invalidamos
      // el caché para que la próxima vez que se seleccione esa organización se vuelva a chequear.
      orgResponsibleCache.clear();
      notification.success('Usuario eliminado correctamente');
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al eliminar el usuario'));
    }
  }

  async function handleSubmit() {
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    // Nombre y apellido se validan por separado y se muestran los dos errores a la vez.
    const first = personNameSchema('nombre').safeParse(form.firstName);
    const last = personNameSchema('apellido').safeParse(form.lastName);
    if (!first.success) errors.firstName = first.error.issues[0]!.message;
    if (!last.success) errors.lastName = last.error.issues[0]!.message;
    const email = emailSchema.safeParse(form.email);
    if (!email.success) errors.email = email.error.issues[0]!.message;
    if (!first.success || !last.success || !email.success) return;
    if (!editingUser.value && !form.password) {
      errors.password = 'La contraseña es requerida';
      return;
    }
    if (form.roles.length === 0) {
      errors.roles = 'Seleccione al menos un rol';
      return;
    }
    const orgError = validateOrganizationCount(orgMode.value, form.organizationIds.length);
    if (orgError) {
      errors.organizationIds = orgError;
      return;
    }
    // Sólo bloquea roles NUEVOS (que el usuario no tenía ya): un usuario existente que ya tenía
    // un rol acotado por esta regla (p.ej. VIEWER, asignado antes de que existiera, o en una
    // organización que se quedó sin responsable después) puede seguir editándose con normalidad
    // -- lo único que no se permite es SUMARLE un rol nuevo mientras la organización siga sin
    // responsable.
    const newlyAddedGatedRoles = form.roles.filter(
      (r) => RESPONSIBLE_GATED_ROLES.includes(r) && !originalRoles.value.includes(r),
    );
    if (needsResponsibleFirst.value && newlyAddedGatedRoles.length > 0) {
      errors.roles =
        'Esta organización todavía no tiene un responsable asignado. Seleccioná primero "Responsable de Organización".';
      return;
    }

    saving.value = true;
    try {
      const payload: Partial<User> & { password?: string } = {
        email: form.email.trim(),
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        enabled: form.enabled,
        roles: form.roles as UserRole[],
        // PRISMA_ADMIN es global: se manda vacío aunque haya quedado marcada alguna.
        organizationIds: orgMode.value === 'none' ? [] : form.organizationIds,
      };
      if (form.password) payload.password = form.password;

      if (editingUser.value) {
        const removesMfa = willRemoveMfa.value;
        await store.updateUser(editingUser.value.id, payload);
        notification.success(
          removesMfa
            ? 'Usuario actualizado. Se cambió su contraseña y se le quitó el MFA (si tenía uno).'
            : 'Usuario actualizado correctamente',
        );
      } else {
        await store.createUser(payload);
        notification.success('Usuario creado correctamente');
      }
      // El usuario guardado puede haber ganado o perdido el rol ORG_RESPONSIBLE: invalidamos el
      // caché para que la próxima vez que se seleccione esta organización se vuelva a chequear
      // (si no, un formulario abierto justo después seguiría viendo el estado viejo).
      orgResponsibleCache.clear();
      showCreateModal.value = false;
      editingUser.value = null;
      resetForm();
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al guardar el usuario'));
    } finally {
      saving.value = false;
    }
  }

  function resetForm() {
    form.firstName = '';
    form.lastName = '';
    form.email = '';
    form.password = '';
    form.organizationIds = [];
    form.roles = [];
    form.enabled = true;
    guidedNewOrgName.value = '';
    orgHasResponsible.value = false;
    originalRoles.value = [];
  }

  onMounted(async () => {
    loadInvitationAvailability();
    await Promise.all([store.fetchUsers(1, pageSize), orgStore.fetchOrganizations(1, 100)]);

    // Llegada guiada desde "Nueva Organización": pre-cargamos el formulario para crear
    // directamente el ORG_RESPONSIBLE de la organización recién creada.
    const newOrgId = route.query.newOrgId as string | undefined;
    if (newOrgId) {
      form.organizationIds = [newOrgId];
      form.roles = ['ORG_RESPONSIBLE'];
      form.password = generatePassword();
      guidedNewOrgName.value =
        (route.query.newOrgName as string) ||
        orgOptions.value.find((o) => o.value === newOrgId)?.label ||
        '';
      showCreateModal.value = true;
      await refreshOrgHasResponsible(newOrgId);
      router.replace({ query: {} });
    }
  });
</script>
