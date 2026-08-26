<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Reportes</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Generación de reportes ejecutivos y técnicos</p>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
      <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-4">Generar Reporte</h3>
      <div class="space-y-4 max-w-xl">
        <BaseSelect
          v-model="selectedEvaluation"
          label="Seleccionar Evaluación"
          :options="evalOptions"
          placeholder="Seleccione una evaluación aprobada"
        />

        <div v-if="selectedEvaluation" class="flex gap-3">
          <BaseButton variant="primary" :loading="generatingPdf" @click="generatePDF">
            <ArrowDownTrayIcon class="w-4 h-4" />
            Descargar PDF
          </BaseButton>
          <BaseButton variant="secondary" :loading="generatingExcel" @click="generateExcel">
            <DocumentTextIcon class="w-4 h-4" />
            Descargar Excel
          </BaseButton>
        </div>
      </div>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
      <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-4">Reportes Disponibles</h3>
      <div v-if="approvedEvaluations.length === 0" class="text-center py-8 text-slate-500 dark:text-slate-400">
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
              @click="
                selectedEvaluation = eval_.id;
                generatePDF();
              "
            >
              <ArrowDownTrayIcon class="w-4 h-4" /> PDF
            </BaseButton>
            <BaseButton
              variant="ghost"
              size="sm"
              @click="
                selectedEvaluation = eval_.id;
                generateExcel();
              "
            >
              <DocumentTextIcon class="w-4 h-4" /> Excel
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
  import { formatDate } from '@/utils/helpers';
  import { useNotification } from '@/composables/useUtils';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import { ArrowDownTrayIcon, DocumentTextIcon } from '@heroicons/vue/24/outline';
  import type { Evaluation } from '@/types';

  const notification = useNotification();

  const evaluations = ref<Evaluation[]>([]);
  const selectedEvaluation = ref('');
  const generatingPdf = ref(false);
  const generatingExcel = ref(false);

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
  });
</script>
