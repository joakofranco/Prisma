<template>
  <div class="space-y-6">
    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
      <div class="flex items-center justify-between mb-1">
        <div>
          <h2 class="text-lg font-semibold text-slate-900 dark:text-white">Versiones de catálogo</h2>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Cada versión queda disponible para crear evaluaciones apenas se importa.
          </p>
        </div>
      </div>

      <div v-if="loadingVersions" class="py-6 text-center text-sm text-slate-500 dark:text-slate-400">Cargando...</div>
      <div v-else-if="versions.length === 0" class="py-6 text-center text-sm text-slate-500 dark:text-slate-400">
        Todavía no hay ninguna versión de catálogo cargada.
      </div>
      <div v-else class="mt-4 divide-y divide-slate-100 dark:divide-slate-700">
        <div
          v-for="v in versions"
          :key="v.version"
          class="flex items-center justify-between py-2.5"
        >
          <div>
            <span class="text-sm font-medium text-slate-900 dark:text-white">{{ v.version }}</span>
            <span class="text-sm text-slate-500 dark:text-slate-400 ml-2">{{ v.label }}</span>
          </div>
          <button
            type="button"
            class="p-1.5 rounded-lg hover:bg-red-50 text-slate-400 hover:text-red-600"
            title="Eliminar versión"
            @click="handleDeleteVersion(v.version)"
          >
            <TrashIcon class="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
      <h2 class="text-lg font-semibold text-slate-900 dark:text-white mb-1">Importar nueva versión</h2>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1 mb-5">
        Subí un archivo JSON o CSV de ejemplo, o armá el catálogo a mano abajo. En los tres casos
        revisás y editás el árbol completo antes de guardar. Un mismo Requisito puede pertenecer a
        varias Subcategorías: repetí su código (con sus mismos Controles) bajo cada Subcategoría a
        la que corresponda -- no se duplica, queda enlazado a todas.
      </p>

      <div class="flex flex-wrap items-center gap-3 mb-5">
        <label
          class="inline-flex items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 text-sm font-medium rounded-lg hover:bg-slate-200 cursor-pointer"
        >
          <ArrowUpTrayIcon class="w-4 h-4" />
          Subir JSON o CSV
          <input
            ref="fileInput"
            type="file"
            accept=".json,application/json,.csv,text/csv"
            class="hidden"
            @change="handleFileSelected"
          />
        </label>
        <button
          type="button"
          class="text-xs text-blue-600 hover:underline"
          @click="downloadJsonExample"
        >
          Descargar JSON de ejemplo
        </button>
        <button
          type="button"
          class="text-xs text-blue-600 hover:underline"
          @click="downloadCsvExample"
        >
          Descargar CSV de ejemplo
        </button>
        <button
          v-if="functions.length > 0"
          type="button"
          class="text-xs text-slate-500 dark:text-slate-400 hover:underline ml-auto"
          @click="resetForm"
        >
          Empezar de nuevo
        </button>
      </div>

      <BaseAlert
        v-if="parseError"
        variant="danger"
        class="mb-4"
        dismissible
        @dismiss="parseError = ''"
      >
        {{ parseError }}
      </BaseAlert>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-5">
        <BaseInput
          v-model="importVersion"
          label="Identificador de versión"
          placeholder="Ej: 6.0"
          :error="errors.version"
          required
        />
        <BaseInput
          v-model="importLabel"
          label="Etiqueta"
          placeholder="Ej: MCU 6.0"
          :error="errors.label"
          required
        />
      </div>

      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-semibold text-slate-700 dark:text-slate-200">
          Funciones, categorías, subcategorías, requisitos y controles
        </h3>
        <span v-if="errors.functions" class="text-xs text-red-600">{{ errors.functions }}</span>
      </div>

      <CatalogTreeEditor v-model="functions" />

      <div class="flex justify-end pt-5">
        <BaseButton variant="primary" :loading="importing" @click="handleImport">
          Importar catálogo
        </BaseButton>
      </div>
    </div>

    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="Eliminar versión de catálogo"
      :message="`¿Eliminar la versión &quot;${versionToDelete}&quot;? Esta acción no se puede deshacer.`"
      confirm-text="Eliminar"
      variant="danger"
      @confirm="confirmDeleteVersion"
    />
  </div>
</template>

<script setup lang="ts">
  import { onMounted, ref } from 'vue';
  import { TrashIcon, ArrowUpTrayIcon } from '@heroicons/vue/24/outline';
  import { catalogService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import { getErrorMessage } from '@/utils/helpers';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
  import CatalogTreeEditor from '@/components/catalog/CatalogTreeEditor.vue';
  import type { CatalogImportFunction, CatalogImportPayload, MaturityLevel } from '@/types';

  const notification = useNotificationStore();

  const versions = ref<{ version: string; label: string }[]>([]);
  const loadingVersions = ref(true);
  const showDeleteConfirm = ref(false);
  const versionToDelete = ref('');

  const fileInput = ref<HTMLInputElement | null>(null);
  const parseError = ref('');
  const importVersion = ref('');
  const importLabel = ref('');
  const functions = ref<CatalogImportFunction[]>([]);
  const importing = ref(false);
  const errors = ref({ version: '', label: '', functions: '' });

  async function fetchVersions() {
    loadingVersions.value = true;
    try {
      const { data } = await catalogService.getVersions();
      const details = await Promise.all(
        data.versions.map(async (version) => {
          try {
            const { data: full } = await catalogService.getByVersion(version);
            const label = (full as { label?: string }).label;
            return { version, label: label || version };
          } catch {
            return { version, label: version };
          }
        }),
      );
      versions.value = details;
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudieron cargar las versiones de catálogo'));
    } finally {
      loadingVersions.value = false;
    }
  }

  function handleDeleteVersion(version: string) {
    versionToDelete.value = version;
    showDeleteConfirm.value = true;
  }

  async function confirmDeleteVersion() {
    try {
      await catalogService.deleteVersion(versionToDelete.value);
      notification.success('Versión de catálogo eliminada');
      await fetchVersions();
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo eliminar la versión'));
    }
  }

  function resetForm() {
    importVersion.value = '';
    importLabel.value = '';
    functions.value = [];
    parseError.value = '';
    errors.value = { version: '', label: '', functions: '' };
    if (fileInput.value) fileInput.value.value = '';
  }

  function clientId(): string {
    return crypto.randomUUID();
  }

  // ---------------- Parseo de JSON ----------------

  interface RawControl {
    code?: string;
    description?: string;
    targetLevel?: number;
  }
  interface RawRequirement {
    code?: string;
    description?: string;
    controls?: RawControl[];
  }
  interface RawSubcategory {
    code?: string;
    name?: string;
    description?: string;
    requirements?: RawRequirement[];
  }
  interface RawCategory {
    code?: string;
    name?: string;
    description?: string;
    subcategories?: RawSubcategory[];
  }
  interface RawFunction {
    code?: string;
    name?: string;
    description?: string;
    categories?: RawCategory[];
  }
  interface RawCatalog {
    version?: string;
    label?: string;
    functions?: RawFunction[];
  }

  function hydrateFunctions(raw: RawFunction[] | undefined): CatalogImportFunction[] {
    return (raw || []).map((f) => ({
      clientId: clientId(),
      code: f.code || '',
      name: f.name || '',
      description: f.description || '',
      categories: (f.categories || []).map((c) => ({
        clientId: clientId(),
        code: c.code || '',
        name: c.name || '',
        description: c.description || '',
        subcategories: (c.subcategories || []).map((s) => ({
          clientId: clientId(),
          code: s.code || '',
          name: s.name || '',
          description: s.description || '',
          requirements: (s.requirements || []).map((r) => ({
            clientId: clientId(),
            code: r.code || '',
            description: r.description || '',
            controls: (r.controls || []).map((ct) => ({
              clientId: clientId(),
              code: ct.code || '',
              description: ct.description || '',
              targetLevel: (ct.targetLevel && [1, 2, 3, 4].includes(ct.targetLevel)
                ? ct.targetLevel
                : 1) as MaturityLevel,
            })),
          })),
        })),
      })),
    }));
  }

  function parseJsonFile(text: string) {
    const raw = JSON.parse(text) as RawCatalog;
    importVersion.value = raw.version || '';
    importLabel.value = raw.label || '';
    functions.value = hydrateFunctions(raw.functions);
  }

  // ---------------- Parseo de CSV ----------------
  // Formato: una fila por control, con los códigos/nombres de función/categoría/subcategoría/
  // requisito repetidos en cada fila que le pertenece (ver downloadCsvExample). Soporta campos
  // entre comillas con comas o comillas escapadas adentro (RFC4180 básico), no solo split(',').

  function parseCsvLine(line: string): string[] {
    const fields: string[] = [];
    let current = '';
    let inQuotes = false;
    for (let i = 0; i < line.length; i++) {
      const char = line[i];
      if (inQuotes) {
        if (char === '"' && line[i + 1] === '"') {
          current += '"';
          i++;
        } else if (char === '"') {
          inQuotes = false;
        } else {
          current += char;
        }
      } else if (char === '"') {
        inQuotes = true;
      } else if (char === ',') {
        fields.push(current);
        current = '';
      } else {
        current += char;
      }
    }
    fields.push(current);
    return fields.map((f) => f.trim());
  }

  const CSV_COLUMNS = [
    'function_code',
    'function_name',
    'function_description',
    'category_code',
    'category_name',
    'category_description',
    'subcategory_code',
    'subcategory_name',
    'subcategory_description',
    'requirement_code',
    'requirement_description',
    'control_code',
    'control_description',
    'control_target_level',
  ] as const;

  function parseCsvFile(text: string): CatalogImportFunction[] {
    const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
    if (lines.length < 2) {
      throw new Error('El CSV no tiene filas de datos');
    }
    const header = parseCsvLine(lines[0]).map((h) => h.trim().toLowerCase());
    const col = (name: string) => {
      const idx = header.indexOf(name);
      if (idx === -1) throw new Error(`Falta la columna "${name}" en el CSV`);
      return idx;
    };
    const idx = Object.fromEntries(CSV_COLUMNS.map((c) => [c, col(c)])) as Record<
      (typeof CSV_COLUMNS)[number],
      number
    >;

    const functionsByKey = new Map<string, CatalogImportFunction>();
    const categoriesByKey = new Map<string, CatalogImportFunction['categories'][number]>();
    const subcategoriesByKey = new Map<
      string,
      CatalogImportFunction['categories'][number]['subcategories'][number]
    >();
    const requirementsByKey = new Map<
      string,
      CatalogImportFunction['categories'][number]['subcategories'][number]['requirements'][number]
    >();

    for (let i = 1; i < lines.length; i++) {
      const row = parseCsvLine(lines[i]);
      const fCode = row[idx.function_code];
      const cCode = row[idx.category_code];
      const sCode = row[idx.subcategory_code];
      const rCode = row[idx.requirement_code];
      if (!fCode || !cCode || !sCode || !rCode) {
        continue; // fila vacía o incompleta: se ignora en vez de romper todo el import
      }

      let func = functionsByKey.get(fCode);
      if (!func) {
        func = {
          clientId: clientId(),
          code: fCode,
          name: row[idx.function_name] || '',
          description: row[idx.function_description] || '',
          categories: [],
        };
        functionsByKey.set(fCode, func);
      }

      const catKey = `${fCode}::${cCode}`;
      let category = categoriesByKey.get(catKey);
      if (!category) {
        category = {
          clientId: clientId(),
          code: cCode,
          name: row[idx.category_name] || '',
          description: row[idx.category_description] || '',
          subcategories: [],
        };
        categoriesByKey.set(catKey, category);
        func.categories.push(category);
      }

      const subKey = `${catKey}::${sCode}`;
      let subcategory = subcategoriesByKey.get(subKey);
      if (!subcategory) {
        subcategory = {
          clientId: clientId(),
          code: sCode,
          name: row[idx.subcategory_name] || '',
          description: row[idx.subcategory_description] || '',
          requirements: [],
        };
        subcategoriesByKey.set(subKey, subcategory);
        category.subcategories.push(subcategory);
      }

      const reqKey = `${subKey}::${rCode}`;
      let requirement = requirementsByKey.get(reqKey);
      if (!requirement) {
        requirement = {
          clientId: clientId(),
          code: rCode,
          description: row[idx.requirement_description] || '',
          controls: [],
        };
        requirementsByKey.set(reqKey, requirement);
        subcategory.requirements.push(requirement);
      }

      const targetLevelRaw = Number(row[idx.control_target_level]);
      requirement.controls.push({
        clientId: clientId(),
        code: row[idx.control_code] || '',
        description: row[idx.control_description] || '',
        targetLevel: ([1, 2, 3, 4].includes(targetLevelRaw) ? targetLevelRaw : 1) as MaturityLevel,
      });
    }

    return Array.from(functionsByKey.values());
  }

  // ---------------- Selección de archivo ----------------

  async function handleFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    parseError.value = '';

    try {
      const text = await file.text();
      if (file.name.toLowerCase().endsWith('.json')) {
        parseJsonFile(text);
      } else {
        functions.value = parseCsvFile(text);
        if (!importVersion.value) importVersion.value = '';
        if (!importLabel.value) importLabel.value = '';
      }
      notification.success('Archivo leído. Revisá el árbol antes de importar.');
    } catch (err) {
      parseError.value =
        err instanceof Error
          ? `No se pudo leer el archivo: ${err.message}`
          : 'No se pudo leer el archivo';
    } finally {
      input.value = '';
    }
  }

  // ---------------- Ejemplos descargables ----------------

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

  const EXAMPLE: RawCatalog = {
    version: '6.0',
    label: 'MCU 6.0 (ejemplo)',
    functions: [
      {
        code: 'F1',
        name: 'Gestión de Riesgos',
        description: 'Identificación, evaluación y tratamiento de riesgos.',
        categories: [
          {
            code: 'F1.C1',
            name: 'Evaluación de Riesgos',
            description: 'Procesos de identificación y valoración de amenazas.',
            subcategories: [
              {
                code: 'F1.C1.SC1',
                name: 'Identificación de Activos',
                description: 'Identificación y valoración de los activos de información.',
                requirements: [
                  {
                    code: 'R-EJ-01',
                    description: 'Mantener un inventario de activos de información',
                    controls: [
                      {
                        code: 'C-EJ-01',
                        description: 'Existe un inventario básico de activos',
                        targetLevel: 1,
                      },
                      {
                        code: 'C-EJ-02',
                        description: 'El inventario se revisa y actualiza periódicamente',
                        targetLevel: 2,
                      },
                    ],
                  },
                ],
              },
              {
                code: 'F1.C1.SC2',
                name: 'Clasificación de Activos',
                description: 'Clasificación de los activos según su criticidad.',
                requirements: [
                  {
                    // Mismo código que R-EJ-01 arriba: NO se duplica, queda enlazado también a
                    // esta Subcategoría (un Requisito puede pertenecer a varias).
                    code: 'R-EJ-01',
                    description: 'Mantener un inventario de activos de información',
                    controls: [
                      {
                        code: 'C-EJ-01',
                        description: 'Existe un inventario básico de activos',
                        targetLevel: 1,
                      },
                      {
                        code: 'C-EJ-02',
                        description: 'El inventario se revisa y actualiza periódicamente',
                        targetLevel: 2,
                      },
                    ],
                  },
                ],
              },
            ],
          },
        ],
      },
    ],
  };

  function downloadJsonExample() {
    triggerDownload('catalogo-ejemplo.json', JSON.stringify(EXAMPLE, null, 2), 'application/json');
  }

  function downloadCsvExample() {
    const rows = [
      CSV_COLUMNS.join(','),
      [
        'F1',
        'Gestión de Riesgos',
        'Identificación, evaluación y tratamiento de riesgos.',
        'F1.C1',
        'Evaluación de Riesgos',
        'Procesos de identificación y valoración de amenazas.',
        'F1.C1.SC1',
        'Identificación de Activos',
        'Identificación y valoración de los activos de información.',
        'R-EJ-01',
        'Mantener un inventario de activos de información',
        'C-EJ-01',
        'Existe un inventario básico de activos',
        '1',
      ]
        .map((f) => `"${f.replace(/"/g, '""')}"`)
        .join(','),
      [
        'F1',
        'Gestión de Riesgos',
        'Identificación, evaluación y tratamiento de riesgos.',
        'F1.C1',
        'Evaluación de Riesgos',
        'Procesos de identificación y valoración de amenazas.',
        'F1.C1.SC1',
        'Identificación de Activos',
        'Identificación y valoración de los activos de información.',
        'R-EJ-01',
        'Mantener un inventario de activos de información',
        'C-EJ-02',
        'El inventario se revisa y actualiza periódicamente',
        '2',
      ]
        .map((f) => `"${f.replace(/"/g, '""')}"`)
        .join(','),
      // Mismo requirement_code ("R-EJ-01") que las filas de arriba, ahora bajo otra
      // subcategory_code ("F1.C1.SC2"): no se duplica el Requisito, se agrega la asociación.
      [
        'F1',
        'Gestión de Riesgos',
        'Identificación, evaluación y tratamiento de riesgos.',
        'F1.C1',
        'Evaluación de Riesgos',
        'Procesos de identificación y valoración de amenazas.',
        'F1.C1.SC2',
        'Clasificación de Activos',
        'Clasificación de los activos según su criticidad.',
        'R-EJ-01',
        'Mantener un inventario de activos de información',
        'C-EJ-01',
        'Existe un inventario básico de activos',
        '1',
      ]
        .map((f) => `"${f.replace(/"/g, '""')}"`)
        .join(','),
      [
        'F1',
        'Gestión de Riesgos',
        'Identificación, evaluación y tratamiento de riesgos.',
        'F1.C1',
        'Evaluación de Riesgos',
        'Procesos de identificación y valoración de amenazas.',
        'F1.C1.SC2',
        'Clasificación de Activos',
        'Clasificación de los activos según su criticidad.',
        'R-EJ-01',
        'Mantener un inventario de activos de información',
        'C-EJ-02',
        'El inventario se revisa y actualiza periódicamente',
        '2',
      ]
        .map((f) => `"${f.replace(/"/g, '""')}"`)
        .join(','),
    ];
    triggerDownload('catalogo-ejemplo.csv', rows.join('\r\n'), 'text/csv');
  }

  // ---------------- Envío ----------------

  function toPayload(): CatalogImportPayload {
    return {
      version: importVersion.value.trim(),
      label: importLabel.value.trim(),
      functions: functions.value.map((f) => ({
        code: f.code.trim(),
        name: f.name.trim(),
        description: f.description.trim(),
        categories: f.categories.map((c) => ({
          code: c.code.trim(),
          name: c.name.trim(),
          description: c.description.trim(),
          subcategories: c.subcategories.map((s) => ({
            code: s.code.trim(),
            name: s.name.trim(),
            description: s.description.trim(),
            requirements: s.requirements.map((r) => ({
              code: r.code.trim(),
              description: r.description.trim(),
              controls: r.controls.map((ct) => ({
                code: ct.code.trim(),
                description: ct.description.trim(),
                targetLevel: ct.targetLevel,
              })),
            })),
          })),
        })),
      })),
    };
  }

  function validate(): boolean {
    errors.value = { version: '', label: '', functions: '' };
    let ok = true;
    if (!importVersion.value.trim()) {
      errors.value.version = 'La versión es obligatoria';
      ok = false;
    }
    if (!importLabel.value.trim()) {
      errors.value.label = 'La etiqueta es obligatoria';
      ok = false;
    }
    if (functions.value.length === 0) {
      errors.value.functions = 'Agregá al menos una función (subiendo un archivo o a mano)';
      ok = false;
    }
    return ok;
  }

  async function handleImport() {
    if (!validate()) return;
    importing.value = true;
    try {
      await catalogService.importCatalog(toPayload());
      notification.success('Catálogo importado correctamente');
      resetForm();
      await fetchVersions();
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo importar el catálogo'));
    } finally {
      importing.value = false;
    }
  }

  onMounted(fetchVersions);
</script>
