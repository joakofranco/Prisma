<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Evaluaciones</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Gestión de evaluaciones de madurez en ciberseguridad
        </p>
      </div>
      <BaseButton variant="primary" @click="router.push('/evaluations/new')">
        <PlusIcon class="w-4 h-4" />
        Nueva Evaluación
      </BaseButton>
    </div>

    <div class="flex items-center gap-3 flex-wrap">
      <BaseSelect
        v-model="statusFilter"
        :options="statusFilterOptions"
        placeholder="Filtrar por estado"
        class="w-48"
      />
      <BaseSelect
        v-model="orgFilter"
        :options="orgFilterOptions"
        placeholder="Filtrar por organización"
        class="w-64"
      />
    </div>

    <DataTable
      :columns="columns"
      :data="evaluations"
      :loading="loading"
      :show-pagination="true"
      :current-page="currentPage"
      :page-size="pageSize"
      :total-items="total"
      @page-change="handlePageChange"
    >
      <template #cell-status="{ value }">
        <span
          class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium badge-themed"
          :style="getStatusBadgeStyle(value as string)"
        >
          {{ getStatusLabel(value as string) }}
        </span>
      </template>
      <template #cell-globalMaturity="{ value }">
        <span v-if="value !== null && value !== undefined" class="inline-flex items-center gap-1">
          <span
            class="w-2.5 h-2.5 rounded-full"
            :style="{ backgroundColor: getMaturityColor(value as number) }"
          />
          {{ value }}/4
        </span>
        <span v-else class="text-slate-500 dark:text-slate-400">—</span>
      </template>
      <template #cell-createdByName="{ value }">
        {{ value || '—' }}
      </template>
      <template #cell-assignedAuditorNames="{ value }">
        <span v-if="(value as string[])?.length" class="text-slate-700 dark:text-slate-200">
          {{ (value as string[]).join(', ') }}
        </span>
        <span v-else class="text-slate-500 dark:text-slate-400">Sin asignar</span>
      </template>
      <template #cell-createdAt="{ value }">
        {{ formatDate(value as string) }}
      </template>
      <template #actions="{ row }">
        <router-link
          :to="`/evaluations/${row.id}`"
          class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
          title="Ver evaluación"
        >
          <EyeIcon class="w-4 h-4" />
        </router-link>
        <router-link
          v-if="canRespond(row)"
          :to="`/evaluations/${row.id}/respond`"
          class="p-1.5 rounded-lg hover:bg-blue-50 text-slate-500 dark:text-slate-400 hover:text-blue-600 dark:hover:bg-blue-950 dark:hover:text-blue-300"
          title="Responder evaluación"
        >
          <PencilSquareIcon class="w-4 h-4" />
        </router-link>
        <router-link
          v-if="canAudit(row)"
          :to="`/audit/${row.id}`"
          class="p-1.5 rounded-lg hover:bg-amber-50 text-slate-500 dark:text-slate-400 hover:text-amber-700 dark:hover:bg-amber-950 dark:hover:text-amber-300"
          title="Auditar evaluación"
        >
          <MagnifyingGlassCircleIcon class="w-4 h-4" />
        </router-link>
        <button
          v-if="deletableIds.has(row.id)"
          type="button"
          class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 dark:text-slate-400 hover:text-red-600 dark:hover:bg-red-950 dark:hover:text-red-300"
          title="Eliminar evaluación"
          :aria-label="`Eliminar evaluación ${row.name}`"
          @click="confirmDelete(row as Evaluation)"
        >
          <TrashIcon class="w-4 h-4" />
        </button>
      </template>
    </DataTable>

    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="Eliminar evaluación"
      :message="`¿Eliminar la evaluación &quot;${deletingEval?.name}&quot;? No tiene datos cargados, así que se elimina definitivamente.`"
      confirm-text="Eliminar"
      variant="danger"
      @confirm="handleDelete"
    />
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted, watch } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useRouter, useRoute } from 'vue-router';
  import { useEvaluationsStore } from '@/stores/evaluations';
  import { useOrganizationsStore } from '@/stores/organizations';
  import { filterOrganizationOptions } from '@/utils/organizationOptions';
  import { useAuthStore } from '@/stores/auth';
  import {
    getStatusLabel,
    getStatusBadgeStyle,
    getMaturityColor,
    formatDate,
    getErrorMessage,
  } from '@/utils/helpers';
  import DataTable from '@/components/common/DataTable.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
  import { useNotification } from '@/composables/useUtils';
  import { evaluationsService } from '@/services/resources';
  import {
    PlusIcon,
    EyeIcon,
    PencilSquareIcon,
    MagnifyingGlassCircleIcon,
    TrashIcon,
  } from '@heroicons/vue/24/outline';
  import type { Evaluation } from '@/types';

  const router = useRouter();
  const route = useRoute();
  const store = useEvaluationsStore();
  const orgStore = useOrganizationsStore();
  const authStore = useAuthStore();

  // Ver el comentario en OrganizationsView.vue: hay que usar storeToRefs, no desestructurar el
  // store directamente, o la tabla deja de reaccionar a fetchEvaluations().
  const { evaluations, total, loading } = storeToRefs(store);
  const currentPage = ref(1);
  const pageSize = 10;
  const statusFilter = ref('');
  const orgFilter = ref((route.query.organizationId as string) || '');

  // Evaluador y auditor asignado sólo le aportan a quien mira across-org (PRISMA_ADMIN) -- para el
  // resto (acotado a su propia organización vía backend) serían la misma info repetida en cada
  // fila, así que se agregan condicionalmente en vez de siempre.
  const columns = computed(() => [
    { key: 'name', label: 'Nombre' },
    { key: 'organizationName', label: 'Organización' },
    { key: 'catalogVersion', label: 'Catálogo' },
    { key: 'status', label: 'Estado' },
    { key: 'globalMaturity', label: 'Madurez' },
    ...(authStore.isAdmin
      ? [
          { key: 'createdByName', label: 'Evaluador' },
          { key: 'assignedAuditorNames', label: 'Auditor asignado' },
        ]
      : []),
    { key: 'createdAt', label: 'Fecha' },
  ]);

  const statusFilterOptions = [
    // Las archivadas no se listan por defecto: se consultan eligiendo el estado "Archivada".
    { value: '', label: 'Todos (excepto archivadas)' },
    { value: 'DRAFT', label: 'Borrador' },
    { value: 'IN_PROGRESS', label: 'En Curso' },
    { value: 'READY_FOR_AUDIT', label: 'Lista para Auditoría' },
    { value: 'IN_AUDIT', label: 'En Auditoría' },
    { value: 'APPROVED', label: 'Aprobada' },
    { value: 'RETURNED', label: 'Devuelta' },
    { value: 'ARCHIVED', label: 'Archivada' },
  ];

  const orgFilterOptions = computed(() => [
    { value: '', label: 'Todas las organizaciones' },
    ...filterOrganizationOptions(orgStore.organizations),
  ]);

  function handlePageChange(page: number) {
    currentPage.value = page;
    fetchData();
  }

  async function fetchData() {
    const filters: { status?: string; organizationId?: string } = {};
    if (statusFilter.value) filters.status = statusFilter.value;
    if (orgFilter.value) filters.organizationId = orgFilter.value;
    await store.fetchEvaluations(currentPage.value, pageSize, filters);
    await loadDeletable();
  }

  // Eliminar (C22): el botón aparece solo en las evaluaciones que se pueden eliminar -- accesibles
  // y SIN datos (el servidor lo informa con una sola consulta por página). Una evaluación con
  // contenido no se borra: se archiva desde su detalle.
  const notification = useNotification();
  const deletableIds = ref<Set<string>>(new Set());
  const deletingEval = ref<Evaluation | null>(null);
  const showDeleteConfirm = ref(false);

  async function loadDeletable() {
    deletableIds.value = new Set();
    if (!authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE') || evaluations.value.length === 0) {
      return;
    }
    try {
      const { data } = await evaluationsService.getDeletable(evaluations.value.map((e) => e.id));
      deletableIds.value = new Set(data);
    } catch {
      // Sin esta ayuda visual la lista funciona igual (solo no ofrece el botón de eliminar).
    }
  }

  function confirmDelete(evaluation: Evaluation) {
    deletingEval.value = evaluation;
    showDeleteConfirm.value = true;
  }

  async function handleDelete() {
    if (!deletingEval.value) return;
    try {
      await store.deleteEvaluation(deletingEval.value.id);
      notification.success('Evaluación eliminada correctamente');
      await fetchData();
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo eliminar la evaluación'));
    }
  }

  function canRespond(eval_: Evaluation): boolean {
    return (
      authStore.hasAnyRole('PRISMA_ADMIN', 'INTERNAL_EVALUATOR') &&
      ['DRAFT', 'IN_PROGRESS', 'RETURNED'].includes(eval_.status)
    );
  }

  function canAudit(eval_: Evaluation): boolean {
    return (
      authStore.hasAnyRole('PRISMA_ADMIN', 'AUDITOR') &&
      ['READY_FOR_AUDIT', 'IN_AUDIT'].includes(eval_.status)
    );
  }

  watch([statusFilter, orgFilter], () => {
    currentPage.value = 1;
    fetchData();
  });

  onMounted(() => {
    fetchData();
    orgStore.fetchOrganizations(1, 100);
  });
</script>
