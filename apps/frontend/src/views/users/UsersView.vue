<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Usuarios</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Gestión de usuarios del sistema</p>
      </div>
      <BaseButton variant="primary" @click="openCreateModal">
        <PlusIcon class="w-4 h-4" />
        Nuevo Usuario
      </BaseButton>
    </div>

    <div class="flex items-center gap-3">
      <div class="relative flex-1 max-w-md">
        <MagnifyingGlassIcon
          class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400"
        />
        <input
          v-model="searchTerm"
          type="text"
          placeholder="Buscar usuarios..."
          class="w-full pl-10 pr-4 py-2 rounded-lg border border-slate-300 dark:border-slate-600 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
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
      <template #cell-organizationName="{ value }">
        <span v-if="value" class="text-slate-700 dark:text-slate-200">{{ value }}</span>
        <span v-else class="text-slate-400">Global (sin organización)</span>
      </template>
      <template #cell-roles="{ value }">
        <div class="flex flex-wrap gap-1">
          <span
            v-for="role in value as UserRole[]"
            :key="role"
            class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700"
          >
            {{ ROLE_LABELS[role] || role }}
          </span>
        </div>
      </template>
      <template #cell-enabled="{ value }">
        <span
          :class="[
            'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
            value ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400',
          ]"
        >
          {{ value ? 'Activo' : 'Inactivo' }}
        </span>
      </template>
      <template #cell-canLogin="{ value }">
        <span
          v-if="value"
          class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-50 text-emerald-700"
        >
          Puede iniciar sesión
        </span>
        <span
          v-else
          class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-amber-50 text-amber-700"
          title="Todavía no tiene cuenta para loguearse: edite el usuario y fíjele una contraseña"
        >
          Sin acceso todavía
        </span>
      </template>
      <template #actions="{ row }">
        <button
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent"
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
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700"
          title="Editar usuario"
          @click="editUser(row)"
        >
          <PencilIcon class="w-4 h-4" />
        </button>
        <button
          class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 dark:text-slate-400 hover:text-red-600"
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
          class="rounded-lg bg-blue-50 border border-blue-200 px-3 py-2 text-xs text-blue-700"
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
        <BaseInput
          v-model="form.email"
          label="Email"
          type="email"
          placeholder="usuario@prisma.local"
          :error="errors.email"
          required
        />
        <div>
          <BaseInput
            v-model="form.password"
            label="Contraseña"
            type="password"
            placeholder="••••••••"
            :error="errors.password"
            :required="!editingUser"
          />
          <p class="mt-1 text-xs text-slate-400">
            {{
              editingUser
                ? 'Dejar en blanco para no cambiarla. Fijar una acá es lo que le habilita el acceso a un usuario que todavía no puede iniciar sesión.'
                : 'Sin esto el usuario no va a poder iniciar sesión.'
            }}
          </p>
        </div>
        <BaseSelect
          v-model="form.organizationId"
          label="Organización"
          :options="orgOptions"
          placeholder="Seleccione una organización"
          :disabled="!isPlatformAdmin"
        />
        <label
          class="flex items-center gap-2"
          :class="{ 'opacity-50': isEditingOwnAccount }"
          :title="isEditingOwnAccount ? 'No podés deshabilitar tu propia cuenta' : undefined"
        >
          <input
            v-model="form.enabled"
            type="checkbox"
            :disabled="isEditingOwnAccount"
            class="rounded border-slate-300 dark:border-slate-600 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed"
          />
          <span class="text-sm text-slate-700 dark:text-slate-200">Usuario habilitado</span>
        </label>
        <p class="text-xs text-slate-400 -mt-2">
          Un usuario deshabilitado no puede iniciar sesión, pero conserva su historial
          (evaluaciones, evidencia, auditoría) intacto -- a diferencia de eliminarlo.
        </p>
        <div>
          <label class="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">Roles</label>
          <p v-if="needsResponsibleFirst" class="text-xs text-amber-600 mb-2">
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
                class="rounded border-slate-300 dark:border-slate-600 text-blue-600 focus:ring-blue-500 disabled:cursor-not-allowed"
              />
              <span class="text-sm text-slate-700 dark:text-slate-200">{{ role.label }}</span>
            </label>
          </div>
          <p v-if="errors.roles" class="mt-1 text-xs text-red-600">
            {{ errors.roles }}
          </p>
        </div>
        <div v-if="form.roles.includes('AUDITOR')">
          <label class="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">
            Organizaciones a auditar
          </label>
          <p class="text-xs text-slate-400 mb-2">
            El auditor solo va a poder ver y auditar evaluaciones de las organizaciones que
            selecciones acá.
          </p>
          <div class="space-y-2 max-h-40 overflow-y-auto border border-slate-200 dark:border-slate-700 rounded-lg p-3">
            <p v-if="orgOptions.length === 0" class="text-xs text-slate-400">
              No hay organizaciones cargadas todavía.
            </p>
            <label v-for="org in orgOptions" :key="org.value" class="flex items-center gap-2">
              <input
                v-model="form.auditedOrganizationIds"
                type="checkbox"
                :value="org.value"
                class="rounded border-slate-300 dark:border-slate-600 text-blue-600 focus:ring-blue-500"
              />
              <span class="text-sm text-slate-700 dark:text-slate-200">{{ org.label }}</span>
            </label>
          </div>
          <p v-if="errors.auditedOrganizationIds" class="mt-1 text-xs text-red-600">
            {{ errors.auditedOrganizationIds }}
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
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, computed, watch, onMounted } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useRoute, useRouter } from 'vue-router';
  import { useUsersStore } from '@/stores/users';
  import { useOrganizationsStore } from '@/stores/organizations';
  import { useAuthStore } from '@/stores/auth';
  import { usersService } from '@/services/resources';
  import { useNotification } from '@/composables/useUtils';
  import { ROLE_LABELS } from '@/utils/constants';
  import { getErrorMessage } from '@/utils/helpers';
  import DataTable from '@/components/common/DataTable.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import {
    PlusIcon,
    MagnifyingGlassIcon,
    PencilIcon,
    TrashIcon,
    NoSymbolIcon,
    CheckCircleIcon,
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
    { key: 'organizationName', label: 'Organización' },
    { key: 'roles', label: 'Roles' },
    { key: 'enabled', label: 'Estado' },
    { key: 'canLogin', label: 'Acceso' },
  ];

  const form = reactive({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    organizationId: '',
    roles: [] as string[],
    auditedOrganizationIds: [] as string[],
    enabled: true,
  });

  // El backend rechaza deshabilitar la propia cuenta (dejaría a quien lo hace fuera del sistema
  // en el acto, sin nadie que pueda revertirlo -- ver UserService.update): se refleja acá para no
  // dejar tildar el checkbox y recién enterarse del error al guardar.
  const isEditingOwnAccount = computed(
    () => !!editingUser.value && editingUser.value.id === authStore.user?.id,
  );

  const errors = reactive({
    firstName: '',
    lastName: '',
    email: '',
    password: '',
    roles: '',
    auditedOrganizationIds: '',
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

  const orgOptions = ref<{ value: string; label: string }[]>([]);

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
          u.tenantId === orgId &&
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

  watch(
    () => form.organizationId,
    (orgId) => {
      if (orgId) refreshOrgHasResponsible(orgId);
      else orgHasResponsible.value = false;
    },
  );

  const needsResponsibleFirst = computed(
    () =>
      !!form.organizationId && !orgHasResponsible.value && !form.roles.includes('ORG_RESPONSIBLE'),
  );

  const RESPONSIBLE_GATED_ROLES = ['INTERNAL_EVALUATOR', 'AUDITOR', 'VIEWER'];

  function isRoleLocked(roleValue: string): boolean {
    if (!RESPONSIBLE_GATED_ROLES.includes(roleValue)) return false;
    return needsResponsibleFirst.value && !form.roles.includes(roleValue);
  }

  function openCreateModal() {
    editingUser.value = null;
    guidedNewOrgName.value = '';
    resetForm();
    if (!isPlatformAdmin.value && orgOptions.value.length > 0) {
      // Un ORG_RESPONSIBLE solo ve (y solo puede elegir) su propia organización -- se la
      // preseleccionamos ya que el campo queda deshabilitado para ese rol.
      form.organizationId = orgOptions.value[0].value;
    }
    showCreateModal.value = true;
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
    form.organizationId = user.tenantId || '';
    form.roles = [...user.roles];
    form.auditedOrganizationIds = [...(user.auditedOrganizationIds || [])];
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
        tenantId: user.tenantId || undefined,
        auditedOrganizationIds: user.auditedOrganizationIds || [],
      });
      notification.success(`Usuario ${user.enabled ? 'deshabilitado' : 'habilitado'} correctamente`);
    } catch (err) {
      notification.error(getErrorMessage(err, `Error al ${verb} el usuario`));
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
    if (!form.firstName) {
      errors.firstName = 'El nombre es requerido';
      return;
    }
    if (!form.lastName) {
      errors.lastName = 'El apellido es requerido';
      return;
    }
    if (!form.email) {
      errors.email = 'El email es requerido';
      return;
    }
    if (!editingUser.value && !form.password) {
      errors.password = 'La contraseña es requerida';
      return;
    }
    if (form.roles.length === 0) {
      errors.roles = 'Seleccione al menos un rol';
      return;
    }
    if (form.roles.includes('AUDITOR') && form.auditedOrganizationIds.length === 0) {
      errors.auditedOrganizationIds = 'Seleccione al menos una organización para el auditor';
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
        email: form.email,
        firstName: form.firstName,
        lastName: form.lastName,
        enabled: form.enabled,
        roles: form.roles as UserRole[],
        tenantId: form.organizationId || undefined,
        auditedOrganizationIds: form.auditedOrganizationIds,
      };
      if (form.password) payload.password = form.password;

      if (editingUser.value) {
        await store.updateUser(editingUser.value.id, payload);
        notification.success('Usuario actualizado correctamente');
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
    form.organizationId = '';
    form.roles = [];
    form.auditedOrganizationIds = [];
    form.enabled = true;
    guidedNewOrgName.value = '';
    orgHasResponsible.value = false;
    originalRoles.value = [];
  }

  onMounted(async () => {
    await Promise.all([store.fetchUsers(1, pageSize), orgStore.fetchOrganizations(1, 100)]);
    orgOptions.value = orgStore.organizations.map((o) => ({ value: o.id, label: o.name }));

    // Llegada guiada desde "Nueva Organización": pre-cargamos el formulario para crear
    // directamente el ORG_RESPONSIBLE de la organización recién creada.
    const newOrgId = route.query.newOrgId as string | undefined;
    if (newOrgId) {
      form.organizationId = newOrgId;
      form.roles = ['ORG_RESPONSIBLE'];
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
