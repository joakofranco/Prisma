<template>
  <div class="space-y-6">
    <div class="flex items-center gap-3">
      <router-link
        to="/organizations"
        class="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400"
        title="Volver a organizaciones"
      >
        <ArrowLeftIcon class="w-5 h-5" />
      </router-link>
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">
          {{ organization?.name || 'Organización' }}
        </h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Detalle de la organización</p>
      </div>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <div v-else-if="organization" class="grid grid-cols-1 lg:grid-cols-3 gap-6">
      <div class="lg:col-span-2 space-y-6">
        <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-4">Información General</h3>
          <dl class="grid grid-cols-2 gap-4">
            <div>
              <dt class="text-sm text-slate-500 dark:text-slate-400">Nombre</dt>
              <dd class="text-sm font-medium text-slate-900 dark:text-white">
                {{ organization.name }}
              </dd>
            </div>
            <div>
              <dt class="text-sm text-slate-500 dark:text-slate-400">RUT</dt>
              <dd class="text-sm font-medium text-slate-900 dark:text-white">
                {{ organization.rut || '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-sm text-slate-500 dark:text-slate-400">Sector</dt>
              <dd class="text-sm font-medium text-slate-900 dark:text-white">
                {{ organization.sector }}
              </dd>
            </div>
            <div>
              <dt class="text-sm text-slate-500 dark:text-slate-400">Tamaño</dt>
              <dd class="text-sm font-medium text-slate-900 dark:text-white">
                {{ organization.size }}
              </dd>
            </div>
            <div>
              <dt class="text-sm text-slate-500 dark:text-slate-400">Estado</dt>
              <dd>
                <span
                  :class="[
                    'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
                    organization.enabled
                      ? 'bg-emerald-50 text-emerald-700'
                      : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400',
                  ]"
                >
                  {{ organization.enabled ? 'Activa' : 'Inactiva' }}
                </span>
              </dd>
            </div>
            <div>
              <dt class="text-sm text-slate-500 dark:text-slate-400">Creada</dt>
              <dd class="text-sm font-medium text-slate-900 dark:text-white">
                {{ formatDate(organization.createdAt) }}
              </dd>
            </div>
          </dl>
        </div>

        <div
          v-if="maturityHistory.length > 0"
          class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
        >
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-4">Evolución de la Madurez</h3>
          <EvaluationMaturityTrendChart :evaluations="maturityHistory" />
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-lg font-semibold text-slate-900 dark:text-white">Evaluaciones</h3>
            <router-link
              :to="`/evaluations?organizationId=${organization.id}`"
              class="text-sm text-blue-600 hover:text-blue-700 font-medium"
            >
              Ver todas →
            </router-link>
          </div>
          <div v-if="evalsLoading" class="flex items-center justify-center py-8">
            <div class="animate-spin rounded-full h-6 w-6 border-b-2 border-blue-600" />
          </div>
          <p v-else-if="evaluations.length === 0" class="text-sm text-slate-500 dark:text-slate-400">
            Esta organización todavía no tiene evaluaciones.
          </p>
          <div v-else class="space-y-2">
            <router-link
              v-for="eval_ in evaluations"
              :key="eval_.id"
              :to="`/evaluations/${eval_.id}`"
              class="flex items-center justify-between p-3 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            >
              <div>
                <p class="text-sm font-medium text-slate-900 dark:text-white">
                  {{ eval_.name }}
                </p>
                <p class="text-xs text-slate-500 dark:text-slate-400">
                  Catálogo {{ eval_.catalogVersion }} — {{ formatDate(eval_.createdAt) }}
                </p>
              </div>
              <div class="flex items-center gap-3">
                <span class="text-sm font-semibold text-slate-700 dark:text-slate-200">
                  {{ eval_.globalMaturity != null ? `${eval_.globalMaturity}/4` : '—' }}
                </span>
                <span
                  class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium"
                  :style="{
                    backgroundColor: getStatusBgColor(eval_.status),
                    color: getStatusColor(eval_.status),
                  }"
                >
                  {{ getStatusLabel(eval_.status) }}
                </span>
              </div>
            </router-link>
          </div>
        </div>
      </div>

      <div>
        <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-4">Acciones</h3>
          <div class="space-y-3">
            <BaseButton
              variant="secondary"
              class="w-full"
              @click="$router.push(`/evaluations/new?org=${organization.id}`)"
            >
              <ClipboardDocumentCheckIcon class="w-4 h-4" />
              Nueva Evaluación
            </BaseButton>
            <BaseButton variant="danger" class="w-full" @click="handleDelete">
              <TrashIcon class="w-4 h-4" />
              Eliminar Organización
            </BaseButton>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { organizationsService, evaluationsService } from '@/services/resources';
  import { formatDate, getStatusLabel, getStatusColor, getStatusBgColor } from '@/utils/helpers';
  import BaseButton from '@/components/common/BaseButton.vue';
  import EvaluationMaturityTrendChart from '@/components/charts/EvaluationMaturityTrendChart.vue';
  import { ArrowLeftIcon, ClipboardDocumentCheckIcon, TrashIcon } from '@heroicons/vue/24/outline';
  import type { Organization, Evaluation } from '@/types';

  const route = useRoute();
  const router = useRouter();

  const organization = ref<Organization | null>(null);
  const loading = ref(true);
  const evaluations = ref<Evaluation[]>([]);
  const evalsLoading = ref(true);

  // Evaluaciones aprobadas con madurez calculada: la única serie con sentido para el
  // gráfico de evolución (una evaluación en borrador todavía no tiene globalMaturity).
  const maturityHistory = computed(() => evaluations.value.filter((e) => e.globalMaturity != null));

  onMounted(async () => {
    const orgId = route.params.id as string;
    try {
      const { data } = await organizationsService.getById(orgId);
      organization.value = data;
    } catch {
      router.push('/organizations');
      return;
    } finally {
      loading.value = false;
    }

    try {
      const { data } = await evaluationsService.getAll({ organizationId: orgId, pageSize: 50 });
      evaluations.value = data.data;
    } catch (err) {
      console.error('Error fetching organization evaluations:', err);
    } finally {
      evalsLoading.value = false;
    }
  });

  async function handleDelete() {
    if (!organization.value) return;
    if (confirm(`¿Está seguro que desea eliminar ${organization.value.name}?`)) {
      try {
        await organizationsService.delete(organization.value.id);
        router.push('/organizations');
      } catch (err) {
        console.error(err);
      }
    }
  }
</script>
