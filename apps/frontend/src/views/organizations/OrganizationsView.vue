<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Organizaciones</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Gestión de organizaciones registradas en la plataforma
        </p>
      </div>
      <BaseButton variant="primary" @click="showCreateModal = true">
        <PlusIcon class="w-4 h-4" />
        Nueva Organización
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
          placeholder="Buscar organizaciones..."
          aria-label="Buscar organizaciones"
          class="w-full pl-10 pr-4 py-2 rounded-lg border border-slate-300 dark:border-slate-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder:text-slate-500 dark:placeholder:text-slate-300 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          @input="debouncedSearch"
        />
      </div>
    </div>

    <DataTable
      :columns="columns"
      :data="organizations"
      :loading="loading"
      :show-pagination="true"
      :current-page="currentPage"
      :page-size="pageSize"
      :total-items="total"
      @page-change="handlePageChange"
    >
      <template #cell-rut="{ value }">
        <span v-if="value" class="text-slate-700 dark:text-slate-200">{{ value }}</span>
        <span v-else class="text-slate-500 dark:text-slate-400">—</span>
      </template>
      <template #cell-enabled="{ value }">
        <span
          :class="[
            'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
            value ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300' : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300',
          ]"
        >
          {{ value ? 'Activa' : 'Inactiva' }}
        </span>
      </template>
      <template #cell-createdAt="{ value }">
        {{ formatDate(value as string) }}
      </template>
      <template #actions="{ row }">
        <router-link
          :to="`/organizations/${row.id}`"
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
          title="Ver organización"
        >
          <EyeIcon class="w-4 h-4" />
        </router-link>
        <button
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
          title="Editar organización"
          @click="editOrganization(row)"
        >
          <PencilIcon class="w-4 h-4" />
        </button>
        <button
          v-if="(row as Organization).enabled"
          class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 dark:text-slate-400 hover:text-red-600 dark:hover:bg-red-950 dark:hover:text-red-300"
          title="Deshabilitar organización"
          @click="confirmDelete(row)"
        >
          <TrashIcon class="w-4 h-4" />
        </button>
        <button
          v-else
          class="p-1.5 rounded-lg hover:bg-emerald-50 text-slate-500 dark:text-slate-400 hover:text-emerald-700 dark:hover:bg-emerald-950 dark:hover:text-emerald-300"
          title="Reactivar organización"
          @click="confirmReactivate(row as Organization)"
        >
          <ArrowPathIcon class="w-4 h-4" />
        </button>
      </template>
    </DataTable>

    <BaseModal
      v-model="showCreateModal"
      :title="editingOrg ? 'Editar Organización' : 'Nueva Organización'"
      size="lg"
    >
      <form class="grid grid-cols-1 xl:grid-cols-2 gap-4" @submit.prevent="handleSubmit">
        <BaseInput
          v-model="form.name"
          label="Nombre"
          placeholder="Nombre de la organización"
          :error="errors.name"
          required
        />
        <BaseInput
          v-model="form.rut"
          label="RUT"
          placeholder="123456789012 (opcional)"
          :error="errors.rut"
        />
        <BaseSelect
          v-model="form.sector"
          label="Sector"
          :options="sectorOptions"
          placeholder="Seleccione un sector"
          :error="errors.sector"
          required
        />
        <BaseSelect
          v-model="form.size"
          label="Tamaño"
          :options="sizeOptions"
          placeholder="Seleccione un tamaño"
          :error="errors.size"
          required
        />
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showCreateModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="saving" @click="handleSubmit">
          {{ editingOrg ? 'Guardar Cambios' : 'Crear Organización' }}
        </BaseButton>
      </template>
    </BaseModal>

    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="Deshabilitar Organización"
      :message="`¿Está seguro que desea deshabilitar ${deletingOrg?.name}? Quedará marcada como Inactiva y se deshabilitarán sus usuarios (los auditores no se ven afectados). Su historial de evaluaciones, evidencia y auditoría se conserva y sigue siendo consultable.`"
      confirm-text="Deshabilitar"
      variant="danger"
      @confirm="handleDelete"
    />

    <ConfirmDialog
      v-model="showReactivateConfirm"
      title="Reactivar Organización"
      :message="`¿Reactivar ${reactivatingOrg?.name}? Volverá a figurar como Activa. Sus usuarios NO se habilitan automáticamente: deberá habilitarlos uno por uno desde Usuarios.`"
      confirm-text="Reactivar"
      @confirm="handleReactivate"
    />

    <BaseModal v-model="showResponsiblePrompt" title="Asignar responsable" size="sm">
      <p class="text-sm text-slate-600 dark:text-slate-300">
        <strong>{{ createdOrg?.name }}</strong> fue creada correctamente. Antes de sumar
        evaluadores, auditores o visualizadores, designá a su
        <strong>Responsable de Organización</strong> -- es el primer usuario que debería tener esta
        organización.
      </p>
      <template #footer>
        <BaseButton variant="secondary" @click="dismissResponsiblePrompt">
          Hacerlo más tarde
        </BaseButton>
        <BaseButton variant="primary" @click="goAssignResponsible">
          Asignar responsable ahora
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, onMounted } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useRouter } from 'vue-router';
  import { useOrganizationsStore } from '@/stores/organizations';
  import { useNotification } from '@/composables/useUtils';
  import { formatDate, getErrorMessage, getFieldError } from '@/utils/helpers';
  import DataTable from '@/components/common/DataTable.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
  import {
    PlusIcon,
    MagnifyingGlassIcon,
    EyeIcon,
    PencilIcon,
    TrashIcon,
    ArrowPathIcon,
  } from '@heroicons/vue/24/outline';
  import type { Organization } from '@/types';

  const store = useOrganizationsStore();
  const notification = useNotification();
  const router = useRouter();

  // storeToRefs es necesario: desestructurar el store directamente (`const { x } = store`) rompe
  // la reactividad — captura el valor inicial del ref una sola vez, y la vista deja de actualizarse
  // cuando fetchOrganizations() cambia el estado (la tabla quedaba siempre en "No hay datos").
  const { organizations, total, loading } = storeToRefs(store);
  const currentPage = ref(1);
  const pageSize = 10;
  const searchTerm = ref('');
  const showCreateModal = ref(false);
  const showDeleteConfirm = ref(false);
  const showReactivateConfirm = ref(false);
  const showResponsiblePrompt = ref(false);
  const editingOrg = ref<Organization | null>(null);
  const deletingOrg = ref<Organization | null>(null);
  const reactivatingOrg = ref<Organization | null>(null);
  const createdOrg = ref<Organization | null>(null);
  const saving = ref(false);

  const columns = [
    { key: 'name', label: 'Nombre' },
    { key: 'rut', label: 'RUT' },
    { key: 'sector', label: 'Sector' },
    { key: 'size', label: 'Tamaño' },
    { key: 'enabled', label: 'Estado' },
    { key: 'createdAt', label: 'Creada' },
  ];

  const form = reactive({
    name: '',
    rut: '',
    sector: '',
    size: '',
  });

  const errors = reactive({
    name: '',
    rut: '',
    sector: '',
    size: '',
  });

  // value === label (en español): sector/size son texto libre del lado del backend (sin enum),
  // así que lo que se elige acá es exactamente lo que se guarda y después se muestra tal cual en
  // la tabla y en el detalle de organización -- antes el value quedaba en inglés (ej. "technology")
  // aunque el desplegable mostrara "Tecnología", y esa era la cadena que terminaba viéndose en
  // el resto de la app.
  const sectorOptions = [
    { value: 'Público', label: 'Público' },
    { value: 'Privado', label: 'Privado' },
    { value: 'Mixto', label: 'Mixto' },
    { value: 'Tecnología', label: 'Tecnología' },
    { value: 'Financiero', label: 'Financiero' },
    { value: 'Salud', label: 'Salud' },
    { value: 'Educación', label: 'Educación' },
    { value: 'Otro', label: 'Otro' },
  ];

  const sizeOptions = [
    { value: 'Micro', label: 'Micro (menos de 10 empleados)' },
    { value: 'Pequeña', label: 'Pequeña (10-49 empleados)' },
    { value: 'Mediana', label: 'Mediana (50-249 empleados)' },
    { value: 'Grande', label: 'Grande (250 o más empleados)' },
  ];

  let searchTimeout: ReturnType<typeof setTimeout>;
  function debouncedSearch() {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
      currentPage.value = 1;
      store.fetchOrganizations(1, pageSize, searchTerm.value);
    }, 300);
  }

  function handlePageChange(page: number) {
    currentPage.value = page;
    store.fetchOrganizations(page, pageSize, searchTerm.value);
  }

  function editOrganization(org: Organization) {
    editingOrg.value = org;
    form.name = org.name;
    form.rut = org.rut || '';
    form.sector = org.sector;
    form.size = org.size;
    showCreateModal.value = true;
  }

  function confirmDelete(org: Organization) {
    deletingOrg.value = org;
    showDeleteConfirm.value = true;
  }

  async function handleSubmit() {
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    if (!form.name) {
      errors.name = 'El nombre es requerido';
      return;
    }
    if (!form.sector) {
      errors.sector = 'Seleccione un sector';
      return;
    }
    if (!form.size) {
      errors.size = 'Seleccione un tamaño';
      return;
    }

    saving.value = true;
    try {
      const payload: Partial<Organization> = { ...form, enabled: true };
      if (editingOrg.value) {
        await store.updateOrganization(editingOrg.value.id, payload);
        notification.success('Organización actualizada correctamente');
        showCreateModal.value = false;
        editingOrg.value = null;
        resetForm();
      } else {
        const created = await store.createOrganization(payload);
        notification.success('Organización creada correctamente');
        showCreateModal.value = false;
        resetForm();
        // Regla de UI/orden: toda organización nueva debería tener su ORG_RESPONSIBLE antes de
        // sumarle otros roles -- guiamos al admin a crearlo ahora en vez de dejarla sin dueño.
        createdOrg.value = created;
        showResponsiblePrompt.value = true;
      }
    } catch (err) {
      // Un RUT duplicado se marca en el propio campo (el modal sigue abierto para corregirlo) en
      // lugar de solo un aviso suelto.
      const rutError = getFieldError(err, 'rut');
      if (rutError) {
        errors.rut = rutError;
      } else {
        notification.error(getErrorMessage(err, 'Error al guardar la organización'));
      }
    } finally {
      saving.value = false;
    }
  }

  function goAssignResponsible() {
    if (!createdOrg.value) return;
    showResponsiblePrompt.value = false;
    router.push({
      path: '/users',
      query: { newOrgId: createdOrg.value.id, newOrgName: createdOrg.value.name },
    });
    createdOrg.value = null;
  }

  function dismissResponsiblePrompt() {
    showResponsiblePrompt.value = false;
    createdOrg.value = null;
  }

  function confirmReactivate(org: Organization) {
    reactivatingOrg.value = org;
    showReactivateConfirm.value = true;
  }

  async function handleReactivate() {
    if (!reactivatingOrg.value) return;
    try {
      const { disabledUsers } = await store.reactivateOrganization(reactivatingOrg.value.id);
      if (disabledUsers > 0) {
        notification.warning(
          `Organización reactivada. ${disabledUsers} usuario${disabledUsers === 1 ? '' : 's'} sigue${disabledUsers === 1 ? '' : 'n'} deshabilitado${disabledUsers === 1 ? '' : 's'}: habilítelo${disabledUsers === 1 ? '' : 's'} desde Usuarios.`,
        );
      } else {
        notification.success('Organización reactivada correctamente');
      }
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al reactivar la organización'));
    }
  }

  async function handleDelete() {
    if (!deletingOrg.value) return;
    try {
      await store.deleteOrganization(deletingOrg.value.id);
      notification.success('Organización deshabilitada correctamente');
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al deshabilitar la organización'));
    }
  }

  function resetForm() {
    form.name = '';
    form.rut = '';
    form.sector = '';
    form.size = '';
  }

  onMounted(() => {
    store.fetchOrganizations(1, pageSize);
  });
</script>
