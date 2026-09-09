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
          @update:model-value="onVersionChange"
        />

        <div v-if="!editingProfile" class="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <BaseSelect
            v-model="duplicateFromId"
            label="Partir de un perfil existente (opcional)"
            :options="duplicateOptions"
            @update:model-value="applyDuplicateFrom"
          />
        </div>

        <div>
          <div class="flex items-center justify-between mb-2">
            <label class="block text-sm font-medium text-slate-700 dark:text-slate-200">
              Controles incluidos ({{ form.controlIds.length }} seleccionados)
            </label>
            <div class="flex items-center gap-2">
              <button
                type="button"
                class="text-xs text-blue-600 hover:underline"
                :disabled="form.controlIds.length === 0"
                @click="exportSelection"
              >
                Exportar CSV
              </button>
              <label class="text-xs text-blue-600 hover:underline cursor-pointer">
                Importar
                <input
                  ref="importInput"
                  type="file"
                  accept=".csv,.json,text/csv,application/json"
                  class="hidden"
                  @change="handleImportFile"
                />
              </label>
              <label class="flex items-center gap-1 text-xs text-slate-500 dark:text-slate-400">
                <input
                  v-model="importMerge"
                  type="checkbox"
                  class="rounded border-slate-300 text-blue-600"
                />
                sumar a la actual
              </label>
            </div>
          </div>
          <p v-if="errors.controlIds" class="mb-2 text-xs text-red-600">{{ errors.controlIds }}</p>
          <BaseAlert
            v-if="importInfo"
            variant="warning"
            class="mb-2"
            dismissible
            @dismiss="importInfo = ''"
          >
            {{ importInfo }}
          </BaseAlert>

          <CommunityProfileControlPicker
            v-model="form.controlIds"
            :controls="flatControls"
            :loading="loadingControls"
          />
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
  import { ref, reactive, computed, onMounted } from 'vue';
  import { communityProfilesService, catalogService } from '@/services/resources';
  import { useNotification } from '@/composables/useUtils';
  import { getErrorMessage } from '@/utils/helpers';
  import DataTable from '@/components/common/DataTable.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import CommunityProfileControlPicker from '@/components/profiles/CommunityProfileControlPicker.vue';
  import { PlusIcon, PencilIcon, TrashIcon } from '@heroicons/vue/24/outline';
  import type { CommunityProfileSummary, CatalogControlFlat } from '@/types';

  const notification = useNotification();

  const profiles = ref<CommunityProfileSummary[]>([]);
  const loading = ref(true);
  const saving = ref(false);
  const loadingControls = ref(false);
  const showModal = ref(false);
  const editingProfile = ref<CommunityProfileSummary | null>(null);
  const catalogVersion = ref('5.0');
  const catalogOptions = ref<{ value: string; label: string }[]>([
    { value: '5.0', label: 'MCU 5.0' },
  ]);
  const flatControls = ref<CatalogControlFlat[]>([]);

  const duplicateFromId = ref('');
  const importInput = ref<HTMLInputElement | null>(null);
  const importMerge = ref(false);
  const importInfo = ref('');

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

  const idToCode = computed(() => new Map(flatControls.value.map((c) => [c.id, c.code] as const)));
  const codeToId = computed(() => new Map(flatControls.value.map((c) => [c.code, c.id] as const)));

  const duplicateOptions = computed(() => [
    { value: '', label: '— Ninguno (empezar vacío) —' },
    ...profiles.value
      .filter((p) => p.catalogVersion === form.catalogVersion)
      .map((p) => ({ value: p.id, label: `${p.name} (${p.controlCount} controles)` })),
  ]);

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

  async function loadControls() {
    loadingControls.value = true;
    flatControls.value = [];
    try {
      const { data } = await catalogService.getControls(form.catalogVersion);
      flatControls.value = data;
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al cargar los controles del catálogo'));
    } finally {
      loadingControls.value = false;
    }
  }

  function onVersionChange() {
    // Al cambiar de versión, los ids ya seleccionados dejan de ser válidos.
    form.controlIds = [];
    duplicateFromId.value = '';
    loadControls();
  }

  function resetModalState() {
    duplicateFromId.value = '';
    importMerge.value = false;
    importInfo.value = '';
    if (importInput.value) importInput.value.value = '';
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
  }

  function openCreateModal() {
    editingProfile.value = null;
    form.name = '';
    form.description = '';
    form.catalogVersion = catalogVersion.value;
    form.controlIds = [];
    resetModalState();
    showModal.value = true;
    loadControls();
  }

  async function editProfile(summary: CommunityProfileSummary) {
    editingProfile.value = summary;
    form.name = summary.name;
    form.description = summary.description || '';
    form.catalogVersion = summary.catalogVersion;
    form.controlIds = [];
    resetModalState();
    showModal.value = true;
    await loadControls();
    try {
      const { data } = await communityProfilesService.getById(summary.id);
      form.controlIds = [...data.controlIds];
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al cargar el perfil'));
    }
  }

  async function applyDuplicateFrom() {
    if (!duplicateFromId.value) return;
    try {
      const { data } = await communityProfilesService.getById(duplicateFromId.value);
      form.controlIds = [...data.controlIds];
      notification.success('Selección copiada del perfil elegido');
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo copiar la selección'));
    }
  }

  function triggerDownload(filename: string, content: string, mime: string) {
    const blob = new Blob([content], { type: mime });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  function exportSelection() {
    const codes = form.controlIds
      .map((id) => idToCode.value.get(id))
      .filter((c): c is string => !!c)
      .sort();
    triggerDownload(
      `perfil-controles-${form.catalogVersion}.csv`,
      codes.join('\r\n') + '\r\n',
      'text/csv',
    );
  }

  async function handleImportFile(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    importInfo.value = '';
    try {
      const text = await file.text();
      let codes: string[];
      if (file.name.toLowerCase().endsWith('.json')) {
        const parsed = JSON.parse(text) as string[] | { codes?: string[] };
        codes = Array.isArray(parsed) ? parsed : parsed.codes || [];
      } else {
        codes = text
          .split(/\r?\n/)
          .map((l) => l.split(',')[0].trim())
          .filter((l) => l && l.toLowerCase() !== 'code');
      }
      const resolved: string[] = [];
      const unknown: string[] = [];
      for (const code of codes) {
        const id = codeToId.value.get(code);
        if (id) resolved.push(id);
        else unknown.push(code);
      }
      const base = importMerge.value ? form.controlIds : [];
      form.controlIds = [...new Set([...base, ...resolved])];
      let msg = `${resolved.length} controles aplicados.`;
      if (unknown.length)
        msg += ` ${unknown.length} códigos no existen en el catálogo ${form.catalogVersion}: ${unknown.slice(0, 10).join(', ')}${unknown.length > 10 ? '…' : ''}`;
      importInfo.value = msg;
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo leer el archivo'));
    } finally {
      input.value = '';
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
