<template>
  <div class="space-y-6">
    <div class="flex items-center gap-3">
      <router-link
        :to="`/evaluations/${evaluationId}`"
        class="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400"
        title="Volver a la evaluación"
      >
        <ArrowLeftIcon class="w-5 h-5" />
      </router-link>
      <div class="flex-1">
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Evidencias</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Gestión de evidencias documentales de respaldo</p>
      </div>
      <BaseButton variant="primary" @click="showUploadModal = true">
        <CloudArrowUpIcon class="w-4 h-4" />
        Subir Evidencia
      </BaseButton>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <div
      v-else-if="evidences.length === 0"
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-12 text-center"
    >
      <DocumentIcon class="w-12 h-12 text-slate-300 mx-auto mb-3" />
      <p class="text-slate-500 dark:text-slate-400">No hay evidencias cargadas para esta evaluación</p>
    </div>

    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      <div
        v-for="ev in evidences"
        :key="ev.id"
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 hover:shadow-md transition-shadow"
      >
        <div class="flex items-start gap-3">
          <div class="p-2 bg-blue-50 rounded-lg">
            <DocumentIcon class="w-6 h-6 text-blue-600" />
          </div>
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium text-slate-900 dark:text-white truncate">
              {{ ev.fileName }}
            </p>
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
              {{ formatFileSize(ev.fileSize) }} — {{ ev.fileType }}
            </p>
            <p v-if="ev.description" class="text-xs text-slate-400 mt-1 truncate">
              {{ ev.description }}
            </p>
          </div>
        </div>
        <div class="flex items-center justify-between mt-3 pt-3 border-t border-slate-100">
          <span
            v-if="ev.aiIndexed"
            class="inline-flex items-center gap-1 text-xs text-emerald-600"
            title="El asistente de IA extrajo el contenido y puede citarlo para el auditor"
          >
            <SparklesIcon class="w-3.5 h-3.5" /> Indexado
          </span>
          <button
            v-else
            class="inline-flex items-center gap-1 text-xs text-slate-400 hover:text-blue-600 disabled:opacity-50"
            :disabled="reindexingId === ev.id"
            @click="reindexEvidence(ev)"
          >
            <SparklesIcon class="w-3.5 h-3.5" />
            {{ reindexingId === ev.id ? 'Analizando…' : 'Analizar con IA' }}
          </button>
          <div class="flex gap-1">
            <button
              v-if="ev.url"
              class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-400 hover:text-blue-600"
              title="Descargar evidencia"
              @click="downloadEvidence(ev)"
            >
              <ArrowDownTrayIcon class="w-4 h-4" />
            </button>
            <button
              class="p-1.5 rounded-lg hover:bg-red-50 text-slate-400 hover:text-red-600"
              title="Eliminar evidencia"
              @click="confirmDeleteEvidence(ev)"
            >
              <TrashIcon class="w-4 h-4" />
            </button>
          </div>
        </div>
        <p class="text-xs text-slate-400 mt-2">{{ formatDate(ev.uploadedAt) }}</p>
      </div>
    </div>

    <BaseModal v-model="showUploadModal" title="Subir Evidencia" size="lg">
      <form class="space-y-4" @submit.prevent="handleUpload">
        <BaseCombobox
          v-if="controlOptions.length > 0"
          v-model="uploadForm.controlId"
          label="Control Asociado"
          :options="controlOptions"
          placeholder="Buscar por código o nombre del control"
          :error="uploadErrors.controlId"
          required
        />
        <p v-else class="text-sm text-slate-500 dark:text-slate-400">
          No hay controles marcados como "Cumple" todavía en esta evaluación -- las evidencias solo
          se cargan para controles cuya respuesta fue positiva.
        </p>
        <BaseTextarea
          v-model="uploadForm.description"
          label="Descripción"
          placeholder="Describa brevemente la evidencia"
        />
        <div>
          <label class="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">Archivo</label>
          <input
            ref="fileInput"
            type="file"
            class="w-full text-sm text-slate-600 dark:text-slate-300 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-sm file:font-medium file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100"
          />
          <p v-if="uploadErrors.file" class="mt-1 text-xs text-red-600">
            {{ uploadErrors.file }}
          </p>
        </div>
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showUploadModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="uploading" @click="handleUpload">
          Subir
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, onMounted } from 'vue';
  import { useRoute } from 'vue-router';
  import { evidenceService, evaluationsService, catalogService } from '@/services/resources';
  import { formatDate, formatFileSize, getErrorMessage } from '@/utils/helpers';
  import { useNotification } from '@/composables/useUtils';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseCombobox from '@/components/common/BaseCombobox.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import {
    ArrowLeftIcon,
    CloudArrowUpIcon,
    DocumentIcon,
    ArrowDownTrayIcon,
    TrashIcon,
    SparklesIcon,
  } from '@heroicons/vue/24/outline';
  import type { Evidence, MaturityCatalog } from '@/types';

  const route = useRoute();
  const notification = useNotification();

  const evaluationId = route.params.evaluationId as string;
  const loading = ref(true);
  const uploading = ref(false);
  const showUploadModal = ref(false);
  const evidences = ref<Evidence[]>([]);
  const fileInput = ref<HTMLInputElement | null>(null);
  const reindexingId = ref<string | null>(null);

  const uploadForm = reactive({
    controlId: '',
    description: '',
  });

  const uploadErrors = reactive({
    controlId: '',
    file: '',
  });

  const controlOptions = ref<{ value: string; label: string }[]>([]);

  async function fetchEvidences() {
    loading.value = true;
    try {
      const { data } = await evidenceService.getByEvaluation(evaluationId);
      evidences.value = data;
    } catch (err) {
      console.error(err);
    } finally {
      loading.value = false;
    }
  }

  async function fetchControlOptions() {
    try {
      const [{ data: evaluation }, { data: responses }] = await Promise.all([
        evaluationsService.getById(evaluationId),
        evaluationsService.getResponses(evaluationId),
      ]);
      // Las evidencias solo se cargan para controles cuya respuesta fue "Cumple" -- no tiene
      // sentido pedir respaldo documental de algo que no se cumple, o que todavia no se respondió.
      const compliantControlIds = new Set(
        responses.filter((r) => r.compliant).map((r) => r.controlId),
      );
      const { data } = await catalogService.getByVersion(
        evaluation.catalogVersion,
        evaluation.communityProfileId,
      );
      const catalog = data as MaturityCatalog;
      const seenControlIds = new Set<string>();
      const options: { value: string; label: string }[] = [];
      for (const fn of catalog.functions ?? []) {
        for (const category of fn.categories ?? []) {
          for (const subcategory of category.subcategories ?? []) {
            for (const requirement of subcategory.requirements ?? []) {
              for (const control of requirement.controls ?? []) {
                // Un Requisito puede pertenecer a varias Subcategorias -- el mismo control puede
                // aparecer mas de una vez en el árbol, no listarlo repetido en el buscador.
                if (seenControlIds.has(control.id) || !compliantControlIds.has(control.id)) {
                  continue;
                }
                seenControlIds.add(control.id);
                options.push({
                  value: control.id,
                  label: `${control.code} — ${control.description}`,
                });
              }
            }
          }
        }
      }
      controlOptions.value = options;
    } catch (err) {
      console.error(err);
    }
  }

  async function reindexEvidence(ev: Evidence) {
    reindexingId.value = ev.id;
    try {
      const { data } = await evidenceService.reindex(ev.id);
      const idx = evidences.value.findIndex((e) => e.id === ev.id);
      if (idx !== -1) evidences.value[idx] = data;
      if (data.aiIndexed) {
        notification.success('Evidencia analizada: ya puede citarse al buscar evidencias');
      } else {
        notification.error('No se pudo extraer contenido de este archivo para el asistente de IA');
      }
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al analizar la evidencia'));
    } finally {
      reindexingId.value = null;
    }
  }

  async function handleUpload() {
    uploadErrors.controlId = '';
    uploadErrors.file = '';
    if (!uploadForm.controlId) {
      uploadErrors.controlId = 'Seleccione un control';
      return;
    }
    if (!fileInput.value?.files?.[0]) {
      uploadErrors.file = 'Seleccione un archivo';
      return;
    }

    uploading.value = true;
    try {
      const formData = new FormData();
      formData.append('evaluationId', evaluationId);
      formData.append('controlId', uploadForm.controlId);
      formData.append('description', uploadForm.description);
      formData.append('file', fileInput.value.files[0]);

      await evidenceService.upload(formData);
      notification.success('Evidencia subida correctamente');
      showUploadModal.value = false;
      uploadForm.controlId = '';
      uploadForm.description = '';
      await fetchEvidences();
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al subir la evidencia'));
    } finally {
      uploading.value = false;
    }
  }

  function downloadEvidence(ev: Evidence) {
    if (ev.url) window.open(ev.url, '_blank');
  }

  function confirmDeleteEvidence(ev: Evidence) {
    if (confirm(`¿Eliminar la evidencia "${ev.fileName}"?`)) {
      deleteEvidence(ev.id);
    }
  }

  async function deleteEvidence(id: string) {
    try {
      await evidenceService.delete(id);
      notification.success('Evidencia eliminada');
      await fetchEvidences();
    } catch {
      notification.error('Error al eliminar la evidencia');
    }
  }

  onMounted(() => {
    fetchEvidences();
    fetchControlOptions();
  });
</script>
