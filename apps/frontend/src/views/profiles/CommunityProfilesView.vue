<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Perfiles Comunitarios</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Subconjuntos de controles del catálogo para acotar evaluaciones por sector (Gobierno,
          PYME, etc.)
        </p>
      </div>
      <BaseButton variant="primary" @click="openCreateModal">
        <PlusIcon class="w-4 h-4" />
        Nuevo Perfil
      </BaseButton>
    </div>

    <div class="flex items-center gap-3">
      <BaseSelect
        v-model="catalogVersion"
        label="Versión del catálogo"
        :options="catalogOptions"
        class="max-w-xs"
        @update:model-value="fetchProfiles"
      />
    </div>

    <DataTable :columns="columns" :data="profiles" :loading="loading">
      <template #cell-controlCount="{ value }">
        <span class="text-sm text-slate-600 dark:text-slate-300">{{ value }} controles</span>
      </template>
      <template #actions="{ row }">
        <button
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700"
          title="Editar perfil"
          @click="editProfile(row)"
        >
          <PencilIcon class="w-4 h-4" />
        </button>
        <button
          class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 dark:text-slate-400 hover:text-red-600"
          title="Eliminar perfil"
          @click="confirmDelete(row)"
        >
          <TrashIcon class="w-4 h-4" />
        </button>
      </template>
    </DataTable>

    <BaseModal
      v-model="showModal"
      :title="editingProfile ? 'Editar Perfil Comunitario' : 'Nuevo Perfil Comunitario'"
      size="lg"
    >
      <form class="space-y-4" @submit.prevent="handleSubmit">
        <BaseInput
          v-model="form.name"
          label="Nombre"
          placeholder="Ej: Gobierno / Sector Público"
          :error="errors.name"
          required
        />
        <BaseTextarea
          v-model="form.description"
          label="Descripción"
          placeholder="Para qué sector/comunidad es este perfil"
          :rows="2"
        />
        <BaseSelect
          v-model="form.catalogVersion"
          label="Versión del catálogo"
          :options="catalogOptions"
          required
          @update:model-value="loadCatalogTree"
        />

        <div>
          <label class="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">
            Controles incluidos ({{ form.controlIds.length }} seleccionados)
          </label>
          <p v-if="errors.controlIds" class="mb-2 text-xs text-red-600">{{ errors.controlIds }}</p>
          <div class="max-h-96 overflow-y-auto border border-slate-200 dark:border-slate-700 rounded-lg p-3 space-y-3">
            <div v-if="loadingTree" class="text-center py-6 text-sm text-slate-400">
              Cargando catálogo...
            </div>
            <div v-for="fn in catalogFunctions" :key="fn.id" class="space-y-1">
              <p class="text-sm font-semibold text-slate-800 dark:text-slate-100">{{ fn.name }}</p>
              <div v-for="cat in fn.categories" :key="cat.id" class="ml-3 space-y-1">
                <p class="text-xs font-medium text-slate-600 dark:text-slate-300">{{ cat.name }}</p>
                <div v-for="sub in cat.subcategories" :key="sub.id" class="ml-3 space-y-1">
                  <div v-for="req in sub.requirements" :key="req.id" class="ml-3 space-y-1">
                    <label
                      v-for="control in req.controls"
                      :key="control.id"
                      class="flex items-start gap-2 py-0.5"
                    >
                      <input
                        v-model="form.controlIds"
                        type="checkbox"
                        :value="control.id"
                        class="mt-0.5 rounded border-slate-300 dark:border-slate-600 text-blue-600 focus:ring-blue-500"
                      />
                      <span class="text-xs text-slate-600 dark:text-slate-300">
                        {{ control.code }} — {{ control.description }}
                      </span>
                    </label>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="saving" @click="handleSubmit">
          {{ editingProfile ? 'Guardar Cambios' : 'Crear Perfil' }}
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, onMounted } from 'vue';
  import { communityProfilesService, catalogService } from '@/services/resources';
  import { useNotification } from '@/composables/useUtils';
  import { getErrorMessage } from '@/utils/helpers';
  import DataTable from '@/components/common/DataTable.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import { PlusIcon, PencilIcon, TrashIcon } from '@heroicons/vue/24/outline';
  import type { CommunityProfileSummary, MaturityCatalog, MaturityFunction } from '@/types';

  const notification = useNotification();

  const profiles = ref<CommunityProfileSummary[]>([]);
  const loading = ref(true);
  const saving = ref(false);
  const loadingTree = ref(false);
  const showModal = ref(false);
  const editingProfile = ref<CommunityProfileSummary | null>(null);
  const catalogVersion = ref('5.0');
  const catalogOptions = ref<{ value: string; label: string }[]>([
    { value: '5.0', label: 'MCU 5.0' },
  ]);
  const catalogFunctions = ref<MaturityFunction[]>([]);

  const columns = [
    { key: 'name', label: 'Nombre' },
    { key: 'description', label: 'Descripción' },
    { key: 'catalogVersion', label: 'Catálogo' },
    { key: 'controlCount', label: 'Controles' },
  ];

  const form = reactive({
    name: '',
    description: '',
    catalogVersion: '5.0',
    controlIds: [] as string[],
  });

  const errors = reactive({
    name: '',
    controlIds: '',
  });

  async function fetchProfiles() {
    loading.value = true;
    try {
      const { data } = await communityProfilesService.getAll(catalogVersion.value);
      profiles.value = data;
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al cargar los perfiles comunitarios'));
    } finally {
      loading.value = false;
    }
  }

  async function loadCatalogTree() {
    loadingTree.value = true;
    try {
      const { data } = await catalogService.getByVersion(form.catalogVersion);
      catalogFunctions.value = (data as MaturityCatalog).functions || [];
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al cargar el catálogo'));
    } finally {
      loadingTree.value = false;
    }
  }

  function openCreateModal() {
    editingProfile.value = null;
    form.name = '';
    form.description = '';
    form.catalogVersion = catalogVersion.value;
    form.controlIds = [];
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    showModal.value = true;
    loadCatalogTree();
  }

  async function editProfile(summary: CommunityProfileSummary) {
    editingProfile.value = summary;
    form.name = summary.name;
    form.description = summary.description || '';
    form.catalogVersion = summary.catalogVersion;
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    showModal.value = true;
    await loadCatalogTree();
    try {
      const { data } = await communityProfilesService.getById(summary.id);
      form.controlIds = [...data.controlIds];
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al cargar el perfil'));
    }
  }

  function confirmDelete(profile: CommunityProfileSummary) {
    if (
      confirm(
        `¿Eliminar el perfil "${profile.name}"? Las evaluaciones que lo usan pasan a ver el catálogo completo.`,
      )
    ) {
      handleDelete(profile.id);
    }
  }

  async function handleDelete(id: string) {
    try {
      await communityProfilesService.delete(id);
      notification.success('Perfil eliminado correctamente');
      await fetchProfiles();
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al eliminar el perfil'));
    }
  }

  async function handleSubmit() {
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    if (!form.name) {
      errors.name = 'El nombre es requerido';
      return;
    }
    if (form.controlIds.length === 0) {
      errors.controlIds = 'Seleccione al menos un control';
      return;
    }

    saving.value = true;
    try {
      const payload = {
        name: form.name,
        description: form.description || undefined,
        catalogVersion: form.catalogVersion,
        controlIds: form.controlIds,
      };
      if (editingProfile.value) {
        await communityProfilesService.update(editingProfile.value.id, payload);
        notification.success('Perfil actualizado correctamente');
      } else {
        await communityProfilesService.create(payload);
        notification.success('Perfil creado correctamente');
      }
      showModal.value = false;
      await fetchProfiles();
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al guardar el perfil'));
    } finally {
      saving.value = false;
    }
  }

  onMounted(async () => {
    try {
      const { data } = await catalogService.getVersions();
      if (data.versions.length > 0) {
        catalogOptions.value = data.versions.map((v) => ({ value: v, label: `MCU ${v}` }));
        catalogVersion.value = data.versions[0];
        form.catalogVersion = data.versions[0];
      }
    } catch {
      // se mantiene el fallback ("5.0") ya inicializado en catalogOptions
    }
    await fetchProfiles();
  });
</script>
