<template>
  <div
    class="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-800 w-full"
  >
    <div class="p-6">
      <p class="text-[11px] font-semibold uppercase tracking-wide text-blue-600 dark:text-blue-400">
        Administración · Catálogo
      </p>
      <h2 class="mt-1 text-lg font-semibold text-slate-900 dark:text-white">
        Guía de los controles
      </h2>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
        Texto de apoyo (interpretación, ejemplo de aplicación y evidencia a considerar) que se
        muestra en la ventana «Guía del control» de cada pregunta del cuestionario. Puede editarlo
        control por control, o cargar un archivo CSV (columnas codigo, interpretacion, ejemplo y
        evidencia) o Markdown. Descargue el CSV para modificarlo en una planilla y volver a
        cargarlo.
      </p>
    </div>

    <div v-if="loading" class="px-6 pb-10 text-center text-sm text-slate-500 dark:text-slate-400">
      Cargando...
    </div>

    <template v-else>
      <BaseAlert v-if="loadError" variant="danger" class="mx-6 mb-4">{{ loadError }}</BaseAlert>

      <div v-if="versions.length > 1" class="flex gap-1 px-6 pb-4" role="tablist">
        <button
          v-for="v in versions"
          :key="v"
          type="button"
          role="tab"
          :aria-selected="v === version"
          :class="[
            'rounded-full px-3 py-1 text-xs font-semibold',
            v === version
              ? 'bg-blue-600 text-white'
              : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600',
          ]"
          @click="selectVersion(v)"
        >
          Versión {{ v }}
        </button>
      </div>

      <div
        class="flex flex-wrap items-center gap-4 border-y border-blue-100 bg-blue-50 px-6 py-4 dark:border-blue-900 dark:bg-blue-950/40"
      >
        <div class="min-w-[14rem] flex-1">
          <p
            class="text-[11px] font-semibold uppercase tracking-wide text-blue-600 dark:text-blue-400"
          >
            Cobertura de la guía
          </p>
          <p class="mt-0.5 text-base font-bold text-slate-900 dark:text-white">
            {{ withGuide }} de {{ items.length }} controles con guía
          </p>
          <p class="text-xs text-slate-500 dark:text-slate-400" data-testid="guide-summary">
            {{ withGuide }} con guía · {{ items.length - withGuide }} sin guía
          </p>
          <div
            class="mt-2 h-1.5 w-full max-w-xs overflow-hidden rounded-full bg-blue-100 dark:bg-blue-900"
            role="progressbar"
            :aria-valuenow="coverage"
            aria-valuemin="0"
            aria-valuemax="100"
          >
            <div
              class="h-full rounded-full bg-emerald-500 transition-all"
              :style="{ width: `${coverage}%` }"
            />
          </div>
        </div>

        <input
          ref="fileInput"
          type="file"
          class="sr-only"
          accept=".csv,.md,.markdown,.txt"
          data-testid="guide-file-input"
          @change="onFileChosen"
        />
        <button
          type="button"
          :disabled="importing || !version"
          class="inline-flex items-center gap-3 rounded-lg bg-blue-600 px-4 py-2.5 text-left text-white shadow-sm transition-colors hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
          data-testid="guide-upload"
          @click="fileInput?.click()"
        >
          <ArrowUpTrayIcon class="h-5 w-5 shrink-0" />
          <span>
            <span class="block text-sm font-semibold">
              {{ importing ? 'Cargando…' : 'Cargar archivo' }}
            </span>
            <span class="block text-[11px] text-blue-100">CSV o Markdown, hasta 5 MB</span>
          </span>
        </button>
      </div>

      <BaseAlert
        v-if="importResult"
        :variant="importResult.updated > 0 ? 'success' : 'warning'"
        class="mx-6 mt-4"
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

      <div class="px-6 pt-5">
        <p
          class="text-[11px] font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400"
        >
          Buscar y filtrar
        </p>
        <div class="mt-2 flex flex-wrap items-center gap-3">
          <div class="relative min-w-[14rem] flex-1">
            <MagnifyingGlassIcon
              class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
            />
            <label class="sr-only" for="guide-search">Buscar</label>
            <input
              id="guide-search"
              v-model="search"
              type="search"
              placeholder="Buscar por código o texto"
              class="w-full rounded-lg border border-slate-300 bg-white py-2 pl-9 pr-3 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:border-slate-500 dark:bg-slate-700 dark:text-white"
              data-testid="guide-search"
            />
          </div>
          <div class="flex gap-1.5" role="group" aria-label="Estado de la guía">
            <button
              v-for="option in statusOptions"
              :key="option.key"
              type="button"
              :aria-pressed="status === option.key"
              :class="[
                'inline-flex items-center gap-1.5 rounded-full border px-3 py-1.5 text-xs font-semibold transition-colors',
                status === option.key
                  ? 'border-blue-600 bg-blue-600 text-white'
                  : 'border-slate-300 bg-white text-slate-600 hover:bg-slate-50 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700',
              ]"
              :data-testid="`guide-status-${option.key}`"
              @click="status = option.key"
            >
              {{ option.label }}
              <span
                :class="[
                  'rounded-full px-1.5 text-[11px]',
                  status === option.key
                    ? 'bg-white/25 text-white'
                    : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
                ]"
              >
                {{ option.count }}
              </span>
            </button>
          </div>
        </div>
      </div>

      <div
        class="mx-6 mt-4 flex flex-wrap items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 dark:border-slate-700 dark:bg-slate-900/40"
      >
        <span class="text-xs text-slate-500 dark:text-slate-400">Otras acciones:</span>
        <button
          type="button"
          :disabled="importing || !version"
          :class="[
            smallButton,
            'border-slate-300 text-slate-700 hover:bg-slate-100 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700',
          ]"
          data-testid="guide-export"
          @click="exportCsv"
        >
          <ArrowDownTrayIcon class="h-3.5 w-3.5" />
          Descargar CSV
        </button>
        <button
          type="button"
          :disabled="selectedIds.size === 0 || deleting"
          :class="[
            smallButton,
            'border-red-300 text-red-700 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950',
          ]"
          data-testid="guide-delete-selected"
          @click="deleteSelected"
        >
          <TrashIcon class="h-3.5 w-3.5" />
          Eliminar seleccionadas ({{ selectedIds.size }})
        </button>
        <label class="ml-auto flex items-center gap-2 text-xs text-slate-600 dark:text-slate-300">
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
        class="px-6 py-8 text-center text-sm text-slate-500 dark:text-slate-400"
        data-testid="guide-empty"
      >
        No hay controles que coincidan con la búsqueda.
      </p>

      <ul v-else class="space-y-3 px-6 pb-6 pt-4">
        <li
          v-for="item in visible"
          :key="item.id"
          :class="[
            'overflow-hidden rounded-xl border',
            item.interpretation
              ? 'border-emerald-200 dark:border-emerald-900'
              : 'border-slate-200 dark:border-slate-700',
          ]"
          :data-testid="`guide-${item.code}`"
        >
          <div class="p-4">
            <div class="flex flex-wrap items-center gap-2">
              <input
                type="checkbox"
                :checked="selectedIds.has(item.id)"
                :aria-label="`Seleccionar ${item.code}`"
                :data-testid="`guide-check-${item.code}`"
                @change="toggle(item.id, ($event.target as HTMLInputElement).checked)"
              />
              <span
                class="rounded-full bg-blue-100 px-2 py-0.5 text-xs font-semibold text-blue-700 dark:bg-blue-900 dark:text-blue-300"
              >
                {{ item.code }}
              </span>
              <span class="text-xs text-slate-500 dark:text-slate-400">
                Nivel {{ item.targetLevel }}
              </span>
              <span
                :class="[
                  'ml-auto rounded-full px-2 py-0.5 text-xs font-semibold',
                  item.interpretation
                    ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300'
                    : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
                ]"
              >
                {{ item.interpretation ? 'Con guía' : 'Sin guía' }}
              </span>
            </div>
            <p class="mt-2 text-sm font-medium text-slate-900 dark:text-white">
              {{ item.description }}
            </p>

            <template v-if="editingId === item.id">
              <div
                v-for="field in fields"
                :key="field.key"
                :class="['mt-3 rounded-lg border p-3', field.tone.box]"
              >
                <label
                  :for="`${field.key}-${item.id}`"
                  :class="['text-[11px] font-semibold uppercase tracking-wide', field.tone.eyebrow]"
                >
                  {{ field.label }}
                </label>
                <textarea
                  :id="`${field.key}-${item.id}`"
                  v-model="draft[field.key]"
                  rows="3"
                  maxlength="4000"
                  class="mt-1 w-full rounded-lg border border-slate-300 bg-white p-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-blue-500 dark:border-slate-500 dark:bg-slate-700 dark:text-white"
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
            <div
              v-else-if="item.interpretation"
              class="mt-3 rounded-lg border border-blue-200 bg-blue-50 p-3 dark:border-blue-900 dark:bg-blue-950/40"
            >
              <p
                class="text-[11px] font-semibold uppercase tracking-wide text-blue-600 dark:text-blue-400"
              >
                Interpretación
              </p>
              <p class="mt-1 text-sm text-slate-700 dark:text-slate-200">
                {{ item.interpretation }}
              </p>
            </div>
          </div>

          <div
            v-if="editingId !== item.id"
            class="flex flex-wrap items-center gap-2 border-t border-slate-200 bg-slate-50 px-4 py-2 dark:border-slate-700 dark:bg-slate-900/40"
          >
            <span class="text-xs text-slate-500 dark:text-slate-400">Acciones:</span>
            <button
              type="button"
              :disabled="busyId !== null"
              :class="[
                smallButton,
                'border-slate-300 text-slate-700 hover:bg-slate-100 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700',
              ]"
              :data-testid="`guide-edit-${item.code}`"
              @click="startEdit(item)"
            >
              <PencilSquareIcon class="h-3.5 w-3.5" />
              {{ item.interpretation ? 'Editar' : 'Escribir guía' }}
            </button>
            <button
              v-if="item.interpretation"
              type="button"
              :disabled="busyId !== null || deleting"
              :class="[
                smallButton,
                'border-red-300 text-red-700 hover:bg-red-50 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-950',
              ]"
              :data-testid="`guide-delete-${item.code}`"
              @click="deleteOne(item)"
            >
              <TrashIcon class="h-3.5 w-3.5" />
              Eliminar
            </button>
          </div>
        </li>
      </ul>

      <div v-if="filtered.length > visible.length" class="pb-6 text-center">
        <BaseButton variant="ghost" data-testid="guide-more" @click="shown += PAGE">
          Mostrar más ({{ filtered.length - visible.length }} restantes)
        </BaseButton>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import {
    ArrowDownTrayIcon,
    ArrowUpTrayIcon,
    MagnifyingGlassIcon,
    PencilSquareIcon,
    TrashIcon,
  } from '@heroicons/vue/24/outline';
  import { catalogService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import { blobErrorMessage, saveBlob } from '@/utils/files';
  import { getErrorMessage } from '@/utils/helpers';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import type { ControlGuideImportResult, ControlGuideItem } from '@/types';

  type FieldKey = 'interpretation' | 'example' | 'evidence';
  type StatusKey = 'all' | 'with' | 'without';

  const PAGE = 50;

  const smallButton =
    'inline-flex items-center gap-1.5 rounded-md border bg-white px-2.5 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 dark:bg-slate-800';

  const fields: {
    key: FieldKey;
    label: string;
    tone: { box: string; eyebrow: string };
  }[] = [
    {
      key: 'interpretation',
      label: 'Interpretación',
      tone: {
        box: 'border-blue-200 bg-blue-50 dark:border-blue-900 dark:bg-blue-950/40',
        eyebrow: 'text-blue-600 dark:text-blue-400',
      },
    },
    {
      key: 'example',
      label: 'Ejemplo de aplicación',
      tone: {
        box: 'border-emerald-200 bg-emerald-50 dark:border-emerald-900 dark:bg-emerald-950/40',
        eyebrow: 'text-emerald-600 dark:text-emerald-400',
      },
    },
    {
      key: 'evidence',
      label: 'Evidencia a considerar',
      tone: {
        box: 'border-amber-200 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/40',
        eyebrow: 'text-amber-600 dark:text-amber-400',
      },
    },
  ];

  const notification = useNotificationStore();

  const fileInput = ref<HTMLInputElement | null>(null);
  const loading = ref(true);
  const loadError = ref('');
  const versions = ref<string[]>([]);
  const version = ref('');
  const items = ref<ControlGuideItem[]>([]);
  const search = ref('');
  const status = ref<StatusKey>('all');
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
  const coverage = computed(() =>
    items.value.length ? Math.round((withGuide.value / items.value.length) * 100) : 0,
  );

  const statusOptions = computed<{ key: StatusKey; label: string; count: number }[]>(() => [
    { key: 'all', label: 'Todas', count: items.value.length },
    { key: 'with', label: 'Con guía', count: withGuide.value },
    { key: 'without', label: 'Sin guía', count: items.value.length - withGuide.value },
  ]);

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
