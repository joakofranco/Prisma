<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Reportes</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
        Generación de reportes ejecutivos y técnicos
      </p>
    </div>

    <div
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
    >
      <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-4">Generar Reporte</h3>
      <div class="grid grid-cols-1 xl:grid-cols-2 gap-4 items-end">
        <BaseSelect
          v-model="selectedEvaluation"
          label="Seleccionar Evaluación"
          :options="evalOptions"
          placeholder="Seleccione una evaluación aprobada"
        />

        <div v-if="selectedEvaluation" class="flex gap-3">
          <BaseButton
            variant="primary"
            :loading="generatingExecutive"
            data-testid="executive-report"
            @click="generateExecutive"
          >
            <PresentationChartBarIcon class="w-4 h-4" />
            Reporte ejecutivo
          </BaseButton>
          <BaseButton variant="secondary" :loading="generatingPdf" @click="generatePDF">
            <ArrowDownTrayIcon class="w-4 h-4" />
            Reporte final (PDF)
          </BaseButton>
          <BaseButton variant="secondary" :loading="generatingExcel" @click="generateExcel">
            <DocumentTextIcon class="w-4 h-4" />
            Reporte final (Excel)
          </BaseButton>
        </div>
      </div>
    </div>

    <div
      v-if="inAuditEvaluations.length > 0"
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
    >
      <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-1">
        Informe Preliminar de Auditoría
      </h3>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
        Hallazgos, observaciones y evidencias faltantes de evaluaciones en auditoría, antes de
        aprobar.
      </p>
      <div class="space-y-3">
        <div
          v-for="eval_ in inAuditEvaluations"
          :key="eval_.id"
          class="flex items-center justify-between p-4 bg-slate-50 dark:bg-slate-900 rounded-lg"
        >
          <div>
            <p class="text-sm font-medium text-slate-900 dark:text-white">
              {{ eval_.name }}
            </p>
            <p class="text-xs text-slate-500 dark:text-slate-400">
              {{ eval_.organizationName }} — {{ formatDate(eval_.createdAt) }}
            </p>
          </div>
          <div class="flex gap-2">
            <BaseButton
              variant="ghost"
              size="sm"
              :loading="generatingPreliminaryPdf === eval_.id"
              @click="generatePreliminaryPDF(eval_.id)"
            >
              <ArrowDownTrayIcon class="w-4 h-4" /> PDF
            </BaseButton>
            <BaseButton
              variant="ghost"
              size="sm"
              :loading="generatingPreliminaryExcel === eval_.id"
              @click="generatePreliminaryExcel(eval_.id)"
            >
              <DocumentTextIcon class="w-4 h-4" /> Excel
            </BaseButton>
          </div>
        </div>
      </div>
    </div>

    <div
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
    >
      <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-1">
        Reportes de Evaluaciones Aprobadas
      </h3>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
        El reporte ejecutivo resume la evaluación en una o dos páginas para la gerencia; el reporte
        final trae el detalle completo por subcategoría.
      </p>
      <div
        v-if="approvedEvaluations.length === 0"
        class="text-center py-8 text-slate-500 dark:text-slate-400"
      >
        No hay evaluaciones aprobadas para generar reportes
      </div>
      <div v-else class="space-y-3">
        <div
          v-for="eval_ in approvedEvaluations"
          :key="eval_.id"
          class="flex items-center justify-between p-4 bg-slate-50 dark:bg-slate-900 rounded-lg"
        >
          <div>
            <p class="text-sm font-medium text-slate-900 dark:text-white">
              {{ eval_.name }}
            </p>
            <p class="text-xs text-slate-500 dark:text-slate-400">
              {{ eval_.organizationName }} — {{ formatDate(eval_.createdAt) }}
            </p>
          </div>
          <div class="flex gap-2">
            <BaseButton
              variant="ghost"
              size="sm"
              data-testid="executive-report-row"
              @click="
                selectedEvaluation = eval_.id;
                generateExecutive();
              "
            >
              <PresentationChartBarIcon class="w-4 h-4" /> Ejecutivo
            </BaseButton>
            <BaseButton
              variant="ghost"
              size="sm"
              @click="
                selectedEvaluation = eval_.id;
                generatePDF();
              "
            >
              <ArrowDownTrayIcon class="w-4 h-4" /> Final PDF
            </BaseButton>
            <BaseButton
              variant="ghost"
              size="sm"
              @click="
                selectedEvaluation = eval_.id;
                generateExcel();
              "
            >
              <DocumentTextIcon class="w-4 h-4" /> Final Excel
            </BaseButton>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted } from 'vue';
  import { evaluationsService, reportsService } from '@/services/resources';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import { saveBlob } from '@/utils/files';
  import { useNotification } from '@/composables/useUtils';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import {
    ArrowDownTrayIcon,
    DocumentTextIcon,
    PresentationChartBarIcon,
  } from '@heroicons/vue/24/outline';
  import type { Evaluation } from '@/types';

  const notification = useNotification();

  const evaluations = ref<Evaluation[]>([]);
  const inAuditEvaluations = ref<Evaluation[]>([]);
  const selectedEvaluation = ref('');
  const generatingPdf = ref(false);
  const generatingExecutive = ref(false);
  const generatingExcel = ref(false);
  const generatingPreliminaryPdf = ref<string | null>(null);
  const generatingPreliminaryExcel = ref<string | null>(null);

  const approvedEvaluations = computed(() =>
    evaluations.value.filter((e) => e.status === 'APPROVED'),
  );

  const evalOptions = computed(() =>
    approvedEvaluations.value.map((e) => ({
      value: e.id,
      label: `${e.name} — ${e.organizationName}`,
    })),
  );

  async function generatePDF() {
    if (!selectedEvaluation.value) return;
    generatingPdf.value = true;
    try {
      const { data } = await reportsService.generatePDF(selectedEvaluation.value);
      const url = window.URL.createObjectURL(new Blob([data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `reporte-prisma-${selectedEvaluation.value}.pdf`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      notification.success('Reporte PDF descargado');
    } catch {
      notification.error('Error al generar el reporte PDF');
    } finally {
      generatingPdf.value = false;
    }
  }

  // Reporte ejecutivo (RF-REP-01): un PDF aparte, de una o dos páginas, solo para evaluaciones
  // aprobadas. Si el servidor lo rechaza (por ejemplo 409 por el estado), se muestra su mensaje.
  async function generateExecutive() {
    if (!selectedEvaluation.value) return;
    generatingExecutive.value = true;
    try {
      const { data } = await reportsService.generateExecutivePDF(selectedEvaluation.value);
      saveBlob(
        new Blob([data], { type: 'application/pdf' }),
        `reporte-ejecutivo-${selectedEvaluation.value}.pdf`,
      );
      notification.success('Reporte ejecutivo descargado');
    } catch (err) {
      notification.error(await reportErrorMessage(err, 'Error al generar el reporte ejecutivo'));
    } finally {
      generatingExecutive.value = false;
    }
  }

  // Con responseType 'blob', el cuerpo de un error también llega como Blob: hay que leerlo para
  // mostrar el mensaje del backend (por ejemplo, «solo pueden exportarse con la evaluación
  // aprobada») en lugar de uno genérico.
  async function reportErrorMessage(err: unknown, fallback: string): Promise<string> {
    const data = (err as { response?: { data?: unknown } })?.response?.data;
    if (data instanceof Blob) {
      try {
        const parsed = JSON.parse(await data.text()) as { message?: string };
        if (parsed.message) return parsed.message;
      } catch {
        // No era JSON: se usa el mensaje genérico.
      }
      return fallback;
    }
    return getErrorMessage(err, fallback);
  }

  async function generateExcel() {
    if (!selectedEvaluation.value) return;
    generatingExcel.value = true;
    try {
      const { data } = await reportsService.generateExcel(selectedEvaluation.value);
      const url = window.URL.createObjectURL(new Blob([data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `reporte-prisma-${selectedEvaluation.value}.xlsx`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      notification.success('Reporte Excel descargado');
    } catch {
      notification.error('Error al generar el reporte Excel');
    } finally {
      generatingExcel.value = false;
    }
  }

  async function generatePreliminaryPDF(evaluationId: string) {
    generatingPreliminaryPdf.value = evaluationId;
    try {
      const { data } = await reportsService.generatePreliminaryPDF(evaluationId);
      const url = window.URL.createObjectURL(new Blob([data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `informe-preliminar-${evaluationId}.pdf`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      notification.success('Informe preliminar PDF descargado');
    } catch {
      notification.error('Error al generar el informe preliminar PDF');
    } finally {
      generatingPreliminaryPdf.value = null;
    }
  }

  async function generatePreliminaryExcel(evaluationId: string) {
    generatingPreliminaryExcel.value = evaluationId;
    try {
      const { data } = await reportsService.generatePreliminaryExcel(evaluationId);
      const url = window.URL.createObjectURL(new Blob([data]));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `informe-preliminar-${evaluationId}.xlsx`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      notification.success('Informe preliminar Excel descargado');
    } catch {
      notification.error('Error al generar el informe preliminar Excel');
    } finally {
      generatingPreliminaryExcel.value = null;
    }
  }

  onMounted(async () => {
    try {
      const { data } = await evaluationsService.getAll({
        page: 1,
        pageSize: 100,
        status: 'APPROVED',
      });
      evaluations.value = data.data;
    } catch (err) {
      console.error(err);
    }
    try {
      const { data } = await evaluationsService.getAll({
        page: 1,
        pageSize: 100,
        status: 'IN_AUDIT',
      });
      inAuditEvaluations.value = data.data;
    } catch (err) {
      console.error(err);
    }
  });
</script>
