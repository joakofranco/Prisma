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
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Gestión de evidencias documentales de respaldo
        </p>
      </div>
      <BaseButton
        variant="primary"
        :disabled="!canModify"
        :title="canModify ? undefined : lockedReason"
        data-testid="upload-evidence"
        @click="showUploadModal = true"
      >
        <CloudArrowUpIcon class="w-4 h-4" />
        Subir Evidencia
      </BaseButton>
    </div>

    <!-- Desde que se envía a auditoría la organización no puede cambiar la evidencia (el backend lo
    rechaza): se avisa y se deshabilitan subir, analizar con IA y eliminar. Descargar y ver siguen. -->
    <div
      v-if="!canModify"
      class="px-4 py-3 rounded-lg bg-slate-100 border border-slate-200 text-sm text-slate-700 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-300"
      role="status"
      data-testid="evidence-locked"
    >
      Las evidencias están en <strong>solo lectura</strong>. {{ lockedReason }} Se pueden volver a
      cargar cuando la evaluación esté en Borrador, En curso o Devuelta.
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <div
      v-else-if="evidences.length === 0"
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-12 text-center"
    >
      <DocumentIcon class="w-12 h-12 text-slate-300 mx-auto mb-3" />
      <p class="text-slate-500 dark:text-slate-400">
        No hay evidencias cargadas para esta evaluación
      </p>
    </div>

    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      <div
        v-for="ev in evidences"
        :key="ev.id"
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 hover:shadow-md transition-shadow"
      >
        <div class="flex items-start gap-3">
          <div class="p-2 bg-blue-50 rounded-lg dark:bg-blue-950">
            <DocumentIcon class="w-6 h-6 text-blue-600 dark:text-blue-400" />
          </div>
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium text-slate-900 dark:text-white truncate">
              {{ ev.fileName }}
            </p>
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
              {{ formatFileSize(ev.fileSize) }} — {{ ev.fileType }}
            </p>
            <p
              v-if="ev.fileHash"
              class="text-xs text-slate-500 mt-1 font-mono truncate dark:text-slate-400"
              :title="`SHA-256: ${ev.fileHash}`"
            >
              SHA-256: {{ ev.fileHash.slice(0, 16) }}…
            </p>
            <p
              v-if="ev.description"
              class="text-xs text-slate-500 mt-1 truncate dark:text-slate-400"
            >
              {{ ev.description }}
            </p>
            <p
              v-if="ev.scanStatus === 'QUARANTINED'"
              class="mt-2 inline-block text-[11px] font-semibold px-2 py-0.5 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300"
              title="El antivirus no estaba disponible al subirla: se analizará automáticamente y mientras tanto no se puede descargar ni usar"
              data-test="quarantine-badge"
            >
              En cuarentena: pendiente de análisis
            </p>
            <p
              v-else-if="ev.scanStatus === 'INFECTED'"
              class="mt-2 inline-block text-[11px] font-semibold px-2 py-0.5 rounded-full bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300"
              title="El análisis posterior detectó contenido malicioso y el archivo fue eliminado"
              data-test="infected-badge"
            >
              Rechazada: contenido malicioso
            </p>
          </div>
        </div>
        <div
          class="flex items-center justify-between mt-3 pt-3 border-t border-slate-100 dark:border-slate-700"
        >
          <span
            v-if="ev.aiIndexed"
            class="inline-flex items-center gap-1 text-xs text-emerald-700 dark:text-emerald-400"
            title="El asistente de IA extrajo el contenido y puede citarlo para el auditor"
          >
            <SparklesIcon class="w-3.5 h-3.5" /> Indexado
          </span>
          <button
            v-else-if="isUsable(ev) && isAiAnalyzable(ev.fileType)"
            class="inline-flex items-center gap-1 text-xs text-slate-500 hover:text-blue-600 disabled:opacity-50 disabled:cursor-not-allowed dark:hover:text-blue-300 dark:text-slate-400"
            :disabled="reindexingId === ev.id || !canModify"
            :title="canModify ? undefined : lockedReason"
            data-testid="reindex-evidence"
            @click="reindexEvidence(ev)"
          >
            <SparklesIcon class="w-3.5 h-3.5" />
            {{ reindexingId === ev.id ? 'Analizando…' : 'Analizar con IA' }}
          </button>
          <span
            v-else
            class="text-xs text-slate-500 dark:text-slate-400"
            title="El asistente de IA solo lee PDF, DOCX, TXT y Markdown"
          >
            Sin análisis de IA
          </span>
          <div class="flex gap-1">
            <button
              v-if="canDownload && isUsable(ev) && previewKind(ev.fileType)"
              class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 hover:text-blue-600 dark:hover:text-blue-300 dark:text-slate-400"
              title="Ver evidencia"
              @click="openPreview(ev)"
            >
              <EyeIcon class="w-4 h-4" />
            </button>
            <button
              v-if="canDownload && isUsable(ev)"
              class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 hover:text-blue-600 dark:hover:text-blue-300 dark:text-slate-400"
              title="Descargar evidencia"
              @click="downloadEvidence(ev)"
            >
              <ArrowDownTrayIcon class="w-4 h-4" />
            </button>
            <button
              class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 hover:text-red-600 dark:hover:bg-red-950 dark:hover:text-red-300 dark:text-slate-400 disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent"
              :title="canModify ? 'Eliminar evidencia' : lockedReason"
              :disabled="!canModify"
              data-testid="delete-evidence"
              @click="confirmDeleteEvidence(ev)"
            >
              <TrashIcon class="w-4 h-4" />
            </button>
          </div>
        </div>
        <p class="text-xs text-slate-500 mt-2 dark:text-slate-400">
          {{ formatDate(ev.uploadedAt) }}
        </p>
      </div>
    </div>

    <BaseModal v-model="previewOpen" :title="previewEvidence?.fileName ?? 'Vista previa'" size="xl">
      <div class="flex items-center justify-center min-h-[60vh]">
        <div
          v-if="previewLoading"
          class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"
          role="status"
          aria-label="Cargando vista previa"
        />
        <p v-else-if="previewError" class="text-sm text-red-600 dark:text-red-400" role="alert">
          {{ previewError }}
        </p>
        <iframe
          v-else-if="previewUrl && previewType === 'pdf'"
          :src="previewUrl"
          :title="`Vista previa de ${previewEvidence?.fileName}`"
          class="w-full h-[75vh] rounded-lg border border-slate-200 dark:border-slate-700"
        />
        <img
          v-else-if="previewUrl && previewType === 'image'"
          :src="previewUrl"
          :alt="`Vista previa de ${previewEvidence?.fileName}`"
          class="max-w-full max-h-[75vh] object-contain"
        />
      </div>
      <template #footer>
        <BaseButton
          v-if="previewEvidence"
          variant="secondary"
          @click="downloadEvidence(previewEvidence)"
        >
          <ArrowDownTrayIcon class="w-4 h-4" />
          Descargar
        </BaseButton>
        <BaseButton variant="primary" @click="previewOpen = false"> Cerrar </BaseButton>
      </template>
    </BaseModal>

    <BaseModal v-model="showUploadModal" title="Subir Evidencia" size="lg">
      <form class="space-y-4" @submit.prevent="handleUpload">
        <BaseSelect
          v-if="controlOptions.length > 0"
          v-model="uploadForm.controlId"
          label="Control Asociado (opcional)"
          :options="controlOptions"
          placeholder="Buscar por código o nombre del control"
          clearable
        />
        <p v-else class="text-sm text-slate-500 dark:text-slate-400">
          No hay controles marcados como "Cumple" todavía en esta evaluación. Puede subir la
          evidencia sin asociarla a un control y asociarla más adelante.
        </p>
        <BaseTextarea
          v-model="uploadForm.description"
          label="Descripción"
          placeholder="Describa brevemente la evidencia"
        />
        <div>
          <label class="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1"
            >Archivo</label
          >
          <input
            ref="fileInput"
            type="file"
            :accept="acceptAttr"
            class="w-full text-sm text-slate-600 dark:text-slate-300 file:mr-4 file:py-2 file:px-4 file:rounded-lg file:border-0 file:text-sm file:font-medium file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100"
            @change="validateSelectedFile"
          />
          <p v-if="rulesHint" class="mt-1 text-xs text-slate-500 dark:text-slate-400">
            {{ rulesHint }}
          </p>
          <p v-if="uploadErrors.file" class="mt-1 text-xs text-red-600 dark:text-red-400">
            {{ uploadErrors.file }}
          </p>
        </div>
        <div
          v-if="uploading"
          class="flex items-center gap-3 rounded-lg border border-blue-200 dark:border-blue-900 bg-blue-50 dark:bg-blue-950 px-4 py-3 text-sm text-blue-800 dark:text-blue-200"
          role="status"
          data-test="scan-progress"
        >
          <div class="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-600" />
          Analizando el archivo con el antivirus…
        </div>
        <div
          v-if="malware"
          class="rounded-lg border border-red-200 dark:border-red-900 bg-red-50 dark:bg-red-950 px-4 py-3 text-sm text-red-800 dark:text-red-200"
          role="alert"
          data-test="malware-alert"
        >
          <p class="font-semibold">{{ malware.message }}</p>
          <p class="mt-1 text-xs">
            <template v-if="malware.signature"
              >Amenaza detectada: <span class="font-mono">{{ malware.signature }}</span
              >.
            </template>
            No se guardó. Revise el archivo o suba otro.
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
  import { ref, reactive, computed, watch, onBeforeUnmount, onMounted } from 'vue';
  import { useRoute } from 'vue-router';
  import { evidenceService, evaluationsService, catalogService } from '@/services/resources';
  import { formatDate, formatFileSize, getErrorMessage } from '@/utils/helpers';
  import { useNotification } from '@/composables/useUtils';
  import { useAuthStore } from '@/stores/auth';
  import { saveBlob } from '@/utils/files';
  import {
    acceptAttribute,
    isAiAnalyzable,
    isAllowedExtension,
    tooLargeMessage,
    unsupportedFormatMessage,
  } from '@/utils/evidenceFormats';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import { previewKind, type PreviewKind } from '@/utils/evidencePreview';
  import {
    ArrowLeftIcon,
    CloudArrowUpIcon,
    DocumentIcon,
    ArrowDownTrayIcon,
    EyeIcon,
    TrashIcon,
    SparklesIcon,
  } from '@heroicons/vue/24/outline';
  import { isSelfAssessmentEditable, selfAssessmentLockedReason } from '@/utils/evaluationRules';
  import type { Evidence, MaturityCatalog } from '@/types';

  const route = useRoute();
  const notification = useNotification();
  const authStore = useAuthStore();

  const evaluationId = route.params.evaluationId as string;
  const loading = ref(true);
  const uploading = ref(false);
  const showUploadModal = ref(false);
  const evidences = ref<Evidence[]>([]);
  const fileInput = ref<HTMLInputElement | null>(null);
  const reindexingId = ref<string | null>(null);

  // Estado de la evaluación: subir, borrar y reindexar solo se admiten mientras se está
  // respondiendo (mismas reglas que EvidenceService). Si no se pudo cargar no se bloquea nada y
  // el backend sigue validando.
  const evaluationStatus = ref<string | null>(null);
  const canModify = computed(() => isSelfAssessmentEditable(evaluationStatus.value));
  const lockedReason = computed(() => selfAssessmentLockedReason(evaluationStatus.value));

  const uploadForm = reactive({
    controlId: '',
    description: '',
  });

  const uploadErrors = reactive({
    file: '',
  });

  const malware = ref<{ message: string; signature: string } | null>(null);

  const controlOptions = ref<{ value: string; label: string }[]>([]);

  // Formatos permitidos, tal como los define el backend. Si no se pudieron cargar queda vacía: no se
  // restringe el selector ni se verifica antes de enviar, y decide el servidor.
  const allowedExtensions = ref<string[]>([]);
  const maxSizeBytes = ref(0);
  const acceptAttr = computed(() =>
    allowedExtensions.value.length > 0 ? acceptAttribute(allowedExtensions.value) : undefined,
  );

  const rulesHint = computed(() => {
    const parts: string[] = [];
    if (allowedExtensions.value.length > 0) {
      parts.push(`Formatos aceptados: ${allowedExtensions.value.map((e) => `.${e}`).join(', ')}`);
    }
    if (maxSizeBytes.value > 0) {
      parts.push(`Tamaño máximo: ${formatFileSize(maxSizeBytes.value)}`);
    }
    return parts.join('. ');
  });

  async function fetchAllowedFormats() {
    try {
      const { data } = await evidenceService.getAllowedFormats();
      allowedExtensions.value = data.allowedExtensions ?? [];
      maxSizeBytes.value = data.maxSizeBytes ?? 0;
    } catch (err) {
      console.error(err);
    }
  }

  // Verifica la extensión apenas se elige el archivo y otra vez al enviar: el filtro `accept` se
  // puede saltear con «todos los archivos», y así un archivo no permitido no viaja al servidor.
  // La extensión se mira antes que el tamaño: un .exe de cualquier peso tiene que decir que el tipo
  // no está permitido (PRI-355), no que es demasiado grande.
  function formatError(file: File | undefined): string {
    if (!file) return '';
    if (
      allowedExtensions.value.length > 0 &&
      !isAllowedExtension(file.name, allowedExtensions.value)
    ) {
      return unsupportedFormatMessage(allowedExtensions.value);
    }
    if (maxSizeBytes.value > 0 && file.size > maxSizeBytes.value) {
      return tooLargeMessage(maxSizeBytes.value);
    }
    return '';
  }

  function isUsable(ev: Evidence) {
    return !ev.scanStatus || ev.scanStatus === 'CLEAN';
  }

  function validateSelectedFile() {
    malware.value = null;
    uploadErrors.file = formatError(fileInput.value?.files?.[0]);
  }

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
      evaluationStatus.value = evaluation.status ?? null;
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

  // Un 413 que corta Nginx (por encima de su límite) llega sin el JSON del backend: sin esto el
  // usuario vería «Error al subir la evidencia» sin saber que el problema es el tamaño.
  function uploadErrorMessage(err: unknown): string {
    const response = (err as { response?: { status?: number; data?: { message?: string } } })
      ?.response;
    if (response?.status === 413 && !response.data?.message && maxSizeBytes.value > 0) {
      return tooLargeMessage(maxSizeBytes.value);
    }
    return getErrorMessage(err, 'Error al subir la evidencia');
  }

  interface UploadErrorBody {
    code?: string;
    message?: string;
    details?: Record<string, string[]>;
  }

  function uploadErrorBody(err: unknown): { status?: number; body?: UploadErrorBody } {
    const response = (err as { response?: { status?: number; data?: UploadErrorBody } })?.response;
    return { status: response?.status, body: response?.data };
  }

  async function handleUpload() {
    uploadErrors.file = '';
    malware.value = null;
    if (!fileInput.value?.files?.[0]) {
      uploadErrors.file = 'Seleccione un archivo';
      return;
    }
    const formatProblem = formatError(fileInput.value.files[0]);
    if (formatProblem) {
      uploadErrors.file = formatProblem;
      return;
    }

    uploading.value = true;
    try {
      const formData = new FormData();
      formData.append('evaluationId', evaluationId);
      // El control es opcional: sin control, la evidencia queda asociada solo a la evaluación.
      if (uploadForm.controlId) formData.append('controlId', uploadForm.controlId);
      formData.append('description', uploadForm.description);
      formData.append('file', fileInput.value.files[0]);

      const { data: uploaded } = await evidenceService.upload(formData);
      if (uploaded?.scanStatus === 'QUARANTINED') {
        notification.warning(
          'Evidencia subida en cuarentena: se analizará automáticamente cuando el antivirus se restablezca',
        );
      } else {
        notification.success('Evidencia subida correctamente');
      }
      showUploadModal.value = false;
      uploadForm.controlId = '';
      uploadForm.description = '';
      await fetchEvidences();
    } catch (err) {
      const { status, body } = uploadErrorBody(err);
      if (status === 422 && body?.code === 'MALWARE_DETECTED') {
        malware.value = {
          message: body.message ?? 'El archivo fue rechazado: se detectó contenido malicioso.',
          signature: body.details?.signature?.[0] ?? '',
        };
      } else {
        notification.error(uploadErrorMessage(err));
      }
    } finally {
      uploading.value = false;
    }
  }

  // Descargan PRISMA_ADMIN, ORG_RESPONSIBLE y AUDITOR (el backend lo exige igual). El archivo se pide con
  // el JWT y se guarda desde un blob: no hay un enlace directo al almacenamiento.
  const canDownload = authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'AUDITOR');

  async function downloadEvidence(ev: Evidence) {
    try {
      const { data } = await evidenceService.download(ev.id);
      saveBlob(data, ev.fileName);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo descargar la evidencia'));
    }
  }

  // Vista previa (RF-EVI-11): PDF e imágenes, con el mismo endpoint y los mismos roles que la
  // descarga. El archivo se pide como blob (con el JWT) y se muestra desde una URL de objeto, que se
  // libera al cerrar el modal y al cambiar de evidencia para no dejar archivos en memoria.
  const previewOpen = ref(false);
  const previewEvidence = ref<Evidence | null>(null);
  const previewType = ref<PreviewKind | null>(null);
  const previewUrl = ref('');
  const previewLoading = ref(false);
  const previewError = ref('');

  function releasePreviewUrl() {
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value);
      previewUrl.value = '';
    }
  }

  async function openPreview(ev: Evidence) {
    const kind = previewKind(ev.fileType);
    if (!kind) return;
    releasePreviewUrl();
    previewEvidence.value = ev;
    previewType.value = kind;
    previewError.value = '';
    previewLoading.value = true;
    previewOpen.value = true;
    try {
      const { data } = await evidenceService.download(ev.id);
      // Se fija el tipo conocido (el que validó el backend): el visor no depende de la cabecera.
      const url = URL.createObjectURL(new Blob([data], { type: ev.fileType }));
      // Si se cerró el modal, o se abrió otra evidencia, mientras bajaba: no queda nada que mostrar.
      if (!previewOpen.value || previewEvidence.value?.id !== ev.id) {
        URL.revokeObjectURL(url);
        return;
      }
      previewUrl.value = url;
    } catch (err) {
      previewError.value = getErrorMessage(err, 'No se pudo cargar la vista previa');
    } finally {
      previewLoading.value = false;
    }
  }

  watch(previewOpen, (open) => {
    if (!open) {
      releasePreviewUrl();
      previewEvidence.value = null;
      previewError.value = '';
    }
  });
  onBeforeUnmount(releasePreviewUrl);

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
    fetchAllowedFormats();
    fetchEvidences();
    fetchControlOptions();
  });
</script>
