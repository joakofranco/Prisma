<template>
  <div
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 w-full"
  >
    <h2 class="text-lg font-semibold text-slate-900 dark:text-white">Guía de los controles</h2>
    <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
      Texto de apoyo (interpretación, ejemplo de aplicación y evidencia a considerar) que se muestra
      en la ventana «Guía del control» de cada pregunta del cuestionario. Puede editarlo control por
      control, o cargar un archivo CSV (columnas codigo, interpretacion, ejemplo y evidencia) o
      Markdown. Descargue el CSV para modificarlo en una planilla y volver a cargarlo.
    </p>

    <div v-if="loading" class="py-10 text-center text-sm text-slate-500 dark:text-slate-400">
      Cargando...
    </div>

    <template v-else>
      <BaseAlert v-if="loadError" variant="danger" class="mt-4">{{ loadError }}</BaseAlert>

      <div v-if="versions.length > 1" class="mt-4 flex gap-1" role="tablist">
        <button
          v-for="v in versions"
          :key="v"
          type="button"
          role="tab"
          :aria-selected="v === version"
          :class="[
            'px-3 py-1.5 rounded-lg text-sm font-medium',
            v === version
              ? 'bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300'
              : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700',
          ]"
          @click="selectVersion(v)"
        >
          {{ v }}
        </button>
      </div>

      <div class="mt-4 flex flex-wrap items-center gap-2">
        <input
          ref="fileInput"
          type="file"
          class="sr-only"
          accept=".csv,.md,.markdown,.txt"
          data-testid="guide-file-input"
          @change="onFileChosen"
        />
        <BaseButton
          variant="primary"
          :loading="importing"
          :disabled="importing || !version"
          data-testid="guide-upload"
          @click="fileInput?.click()"
        >
          Cargar archivo
        </BaseButton>
        <BaseButton
          variant="secondary"
          :disabled="importing || !version"
          data-testid="guide-export"
          @click="exportCsv"
        >
          Descargar CSV
        </BaseButton>
        <BaseButton
          variant="danger"
          :disabled="selectedIds.size === 0 || deleting"
          :loading="deleting"
          data-testid="guide-delete-selected"
          @click="deleteSelected"
        >
          Eliminar seleccionadas ({{ selectedIds.size }})
        </BaseButton>
        <p class="ml-auto text-sm text-slate-600 dark:text-slate-300" data-testid="guide-summary">
          {{ withGuide }} con guía · {{ items.length - withGuide }} sin guía
        </p>
      </div>

      <BaseAlert
        v-if="importResult"
        :variant="importResult.updated > 0 ? 'success' : 'warning'"
        class="mt-4"
        data-testid="guide-import-result"
      >
        <p>
          Se actualizaron {{ importResult.updated }} de {{ importResult.parsed }} controles del
          archivo.
        </p>
        <p v-if="importResult.notInCatalog.length">
          No existen en el catálogo ({{ importResult.notInCatalog.length }}):
          {{ preview(importResult.notInCatalog) }}.
        </p>
        <p v-if="importResult.incomplete.length">
          Incompletos, sin las tres secciones ({{ importResult.incomplete.length }}):
          {{ preview(importResult.incomplete) }}.
        </p>
      </BaseAlert>

      <div class="mt-4 flex flex-wrap items-center gap-3">
        <label class="sr-only" for="guide-search">Buscar</label>
        <input
          id="guide-search"
          v-model="search"
          type="search"
          placeholder="Buscar por código o texto"
          class="flex-1 min-w-[14rem] rounded-lg border border-slate-300 dark:border-slate-500 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          data-testid="guide-search"
        />
        <label class="sr-only" for="guide-status">Estado</label>
        <select
          id="guide-status"
          v-model="status"
          class="rounded-lg border border-slate-300 dark:border-slate-500 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white px-3 py-2"
          data-testid="guide-status"
        >
          <option value="all">Todas</option>
          <option value="with">Con guía</option>
          <option value="without">Sin guía</option>
        </select>
        <label class="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
          <input
            type="checkbox"
            :checked="allFilteredSelected"
            :disabled="filtered.length === 0"
            data-testid="guide-select-all"
            @change="toggleAll(($event.target as HTMLInputElement).checked)"
          />
          Seleccionar los {{ filtered.length }} filtrados
        </label>
      </div>

      <p
        v-if="!filtered.length"
        class="mt-6 text-sm text-slate-500 dark:text-slate-400"
        data-testid="guide-empty"
      >
        No hay controles que coincidan con la búsqueda.
      </p>

      <ul v-else class="mt-4 divide-y divide-slate-200 dark:divide-slate-700">
        <li v-for="item in visible" :key="item.id" class="py-4" :data-testid="`guide-${item.code}`">
          <div class="flex flex-wrap items-center gap-2">
            <input
              type="checkbox"
              :checked="selectedIds.has(item.id)"
              :aria-label="`Seleccionar ${item.code}`"
              :data-testid="`guide-check-${item.code}`"
              @change="toggle(item.id, ($event.target as HTMLInputElement).checked)"
            />
            <span class="text-xs font-semibold text-slate-500 dark:text-slate-400">
              {{ item.code }} · Nivel {{ item.targetLevel }}
            </span>
            <span
              :class="[
                'text-xs font-medium px-2 py-0.5 rounded-full',
                item.interpretation
                  ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300'
                  : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
              ]"
            >
              {{ item.interpretation ? 'Con guía' : 'Sin guía' }}
            </span>
          </div>
          <p class="mt-1 text-sm text-slate-900 dark:text-white">{{ item.description }}</p>

          <template v-if="editingId === item.id">
            <div v-for="field in fields" :key="field.key" class="mt-3">
              <label
                :for="`${field.key}-${item.id}`"
                class="text-xs font-semibold text-slate-600 dark:text-slate-300"
              >
                {{ field.label }}
              </label>
              <textarea
                :id="`${field.key}-${item.id}`"
                v-model="draft[field.key]"
                rows="3"
                maxlength="4000"
                class="mt-1 w-full rounded-lg border border-slate-300 dark:border-slate-500 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white p-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                :data-testid="`guide-${field.key}-${item.code}`"
              />
            </div>
            <div class="mt-3 flex flex-wrap gap-2">
              <BaseButton
                variant="primary"
                :loading="busyId === item.id"
                :data-testid="`guide-save-${item.code}`"
                @click="save(item)"
              >
                Guardar
              </BaseButton>
              <BaseButton variant="ghost" @click="cancelEdit">Cancelar</BaseButton>
            </div>
          </template>
          <template v-else>
            <p
              v-if="item.interpretation"
              class="mt-2 text-sm text-slate-600 dark:text-slate-300 bg-slate-50 dark:bg-slate-700/50 rounded-lg p-3"
            >
              {{ item.interpretation }}
            </p>
            <div class="mt-2 flex flex-wrap gap-2">
              <BaseButton
                variant="secondary"
                :disabled="busyId !== null"
                :data-testid="`guide-edit-${item.code}`"
                @click="startEdit(item)"
              >
                {{ item.interpretation ? 'Editar' : 'Escribir guía' }}
              </BaseButton>
              <BaseButton
                v-if="item.interpretation"
                variant="ghost"
                :disabled="busyId !== null || deleting"
                :data-testid="`guide-delete-${item.code}`"
                @click="deleteOne(item)"
              >
                Eliminar
              </BaseButton>
            </div>
          </template>
        </li>
      </ul>

      <div v-if="filtered.length > visible.length" class="mt-4 text-center">
        <BaseButton variant="ghost" data-testid="guide-more" @click="shown += PAGE">
          Mostrar más ({{ filtered.length - visible.length }} restantes)
        </BaseButton>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { catalogService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import { blobErrorMessage, saveBlob } from '@/utils/files';
  import { getErrorMessage } from '@/utils/helpers';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import type { ControlGuideImportResult, ControlGuideItem } from '@/types';

  type FieldKey = 'interpretation' | 'example' | 'evidence';

  const PAGE = 50;
  const fields: { key: FieldKey; label: string }[] = [
    { key: 'interpretation', label: 'Interpretación' },
    { key: 'example', label: 'Ejemplo de aplicación' },
    { key: 'evidence', label: 'Evidencia a considerar' },
  ];

  const notification = useNotificationStore();

  const fileInput = ref<HTMLInputElement | null>(null);
  const loading = ref(true);
  const loadError = ref('');
  const versions = ref<string[]>([]);
  const version = ref('');
  const items = ref<ControlGuideItem[]>([]);
  const search = ref('');
  const status = ref<'all' | 'with' | 'without'>('all');
  const shown = ref(PAGE);
  const selectedIds = ref<Set<string>>(new Set());
  const importing = ref(false);
  const deleting = ref(false);
  const importResult = ref<ControlGuideImportResult | null>(null);
  const editingId = ref<string | null>(null);
  const busyId = ref<string | null>(null);
  const draft = reactive<Record<FieldKey, string>>({
    interpretation: '',
    example: '',
    evidence: '',
  });

  const withGuide = computed(() => items.value.filter((i) => i.interpretation).length);

  const filtered = computed(() => {
    const query = normalize(search.value);
    return items.value.filter((item) => {
      if (status.value === 'with' && !item.interpretation) return false;
      if (status.value === 'without' && item.interpretation) return false;
      if (!query) return true;
      const haystack = normalize(
        [item.code, item.description, item.interpretation, item.example, item.evidence].join(' '),
      );
      return haystack.includes(query);
    });
  });

  const visible = computed(() => filtered.value.slice(0, shown.value));

  const allFilteredSelected = computed(
    () => filtered.value.length > 0 && filtered.value.every((i) => selectedIds.value.has(i.id)),
  );

  watch([search, status], () => {
    shown.value = PAGE;
  });

  function normalize(text: string | null | undefined) {
    return (text ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim();
  }

  function preview(codes: string[]) {
    return codes.length > 15 ? `${codes.slice(0, 15).join(', ')}…` : codes.join(', ');
  }

  function toggle(id: string, checked: boolean) {
    const next = new Set(selectedIds.value);
    if (checked) next.add(id);
    else next.delete(id);
    selectedIds.value = next;
  }

  function toggleAll(checked: boolean) {
    const next = new Set(selectedIds.value);
    for (const item of filtered.value) {
      if (checked) next.add(item.id);
      else next.delete(item.id);
    }
    selectedIds.value = next;
  }

  async function loadItems() {
    loadError.value = '';
    try {
      const { data } = await catalogService.getGuideItems(version.value);
      items.value = data;
    } catch (err) {
      items.value = [];
      loadError.value = getErrorMessage(err, 'No se pudieron cargar los controles');
    }
    selectedIds.value = new Set();
  }

  async function selectVersion(v: string) {
    if (v === version.value) return;
    version.value = v;
    importResult.value = null;
    cancelEdit();
    await loadItems();
  }

  async function onFileChosen(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    importing.value = true;
    try {
      const { data } = await catalogService.importGuide(version.value, file);
      importResult.value = data;
      await loadItems();
      notification.success(`${data.updated} controles actualizados`);
    } catch (err) {
      importResult.value = null;
      notification.error(getErrorMessage(err, `No se pudo cargar ${file.name}`));
    } finally {
      importing.value = false;
    }
  }

  async function exportCsv() {
    try {
      const { data } = await catalogService.exportGuide(version.value);
      saveBlob(
        new Blob([data], { type: 'text/csv;charset=utf-8' }),
        `guia-controles-${version.value}.csv`,
      );
    } catch (err) {
      notification.error(await blobErrorMessage(err, 'No se pudo descargar el CSV'));
    }
  }

  function startEdit(item: ControlGuideItem) {
    editingId.value = item.id;
    draft.interpretation = item.interpretation ?? '';
    draft.example = item.example ?? '';
    draft.evidence = item.evidence ?? '';
  }

  function cancelEdit() {
    editingId.value = null;
  }

  async function save(item: ControlGuideItem) {
    busyId.value = item.id;
    try {
      const { data } = await catalogService.saveGuide(item.id, { ...draft });
      items.value = items.value.map((i) => (i.id === data.id ? data : i));
      cancelEdit();
      notification.success(`Guía de ${item.code} guardada`);
    } catch (err) {
      notification.error(getErrorMessage(err, `No se pudo guardar la guía de ${item.code}`));
    } finally {
      busyId.value = null;
    }
  }

  async function removeGuides(ids: string[], question: string) {
    if (!window.confirm(question)) return;
    deleting.value = true;
    try {
      const { data } = await catalogService.deleteGuides(ids);
      await loadItems();
      notification.success(`${data.removed} guías eliminadas`);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudieron eliminar las guías'));
    } finally {
      deleting.value = false;
    }
  }

  function deleteOne(item: ControlGuideItem) {
    return removeGuides([item.id], `Se eliminará la guía de ${item.code}. ¿Continuar?`);
  }

  function deleteSelected() {
    const ids = [...selectedIds.value];
    return removeGuides(
      ids,
      `Se eliminarán las guías de los ${ids.length} controles seleccionados. Esta acción no se puede deshacer. ¿Continuar?`,
    );
  }

  onMounted(async () => {
    try {
      const { data } = await catalogService.getVersions();
      versions.value = data.versions;
      version.value = data.versions[0] ?? '';
      if (version.value) await loadItems();
    } catch (err) {
      loadError.value = getErrorMessage(err, 'No se pudo cargar el catálogo');
    } finally {
      loading.value = false;
    }
  });
</script>
