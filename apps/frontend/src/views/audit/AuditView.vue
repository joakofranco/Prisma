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
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">
          {{ evaluation?.name ? `Auditoría — ${evaluation.name}` : 'Auditoría' }}
        </h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          <template v-if="evaluation">
            {{ evaluation.organizationName }} — Catálogo {{ evaluation.catalogVersion }}
          </template>
          <template v-else> Revisión y observaciones de la evaluación </template>
        </p>
      </div>
      <router-link
        :to="`/improvement/${evaluationId}`"
        class="inline-flex items-center gap-2 px-4 py-2 bg-emerald-600 text-white text-sm font-medium rounded-lg hover:bg-emerald-700"
      >
        <ArrowTrendingUpIcon class="w-4 h-4" /> Plan de Mejora
      </router-link>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <template v-else>
      <EvaluationLifecycleStepper v-if="evaluation" :status="evaluation.status" />
      <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
        <div class="flex items-center gap-2 mb-1">
          <SparklesIcon class="w-5 h-5 text-indigo-600" />
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white">
            Asistente de IA — Evidencias por control
          </h3>
        </div>
        <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
          Busca en la evidencia cargada por esta organización los fragmentos que podrían respaldar
          cada control (documento, página y texto exacto). Es sólo una guía de dónde mirar: la
          validación final siempre la registra el auditor como Observación.
        </p>

        <div
          v-if="controlsWithEvidence.length === 0"
          class="text-center py-6 text-slate-500 dark:text-slate-400 text-sm"
        >
          Aún no hay evidencia cargada para esta evaluación.
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="c in controlsWithEvidence"
            :key="c.controlId"
            class="border border-slate-200 dark:border-slate-700 rounded-lg p-4"
          >
            <div class="flex items-center justify-between gap-3">
              <div class="min-w-0">
                <p class="text-sm font-medium text-slate-900 dark:text-white">{{ c.code }}</p>
                <p class="text-xs text-slate-500 dark:text-slate-400 truncate">{{ c.description }}</p>
              </div>
              <BaseButton
                variant="secondary"
                :loading="searchingControlId === c.controlId"
                @click="searchCitations(c)"
              >
                Buscar citas
              </BaseButton>
            </div>

            <div v-if="citationsByControl[c.controlId]" class="mt-3 space-y-2">
              <p v-if="citationsByControl[c.controlId].length === 0" class="text-xs text-slate-400">
                No se encontraron fragmentos relevantes en la evidencia cargada.
              </p>
              <div
                v-for="(cit, i) in citationsByControl[c.controlId]"
                :key="i"
                class="p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-100"
              >
                <div class="flex items-center justify-between gap-2">
                  <p class="text-xs font-medium text-slate-700 dark:text-slate-200 truncate">
                    {{ cit.fileName }} <span v-if="cit.location">· {{ cit.location }}</span>
                  </p>
                  <a
                    v-if="documentUrl(cit)"
                    :href="documentUrl(cit) || undefined"
                    target="_blank"
                    class="text-xs text-blue-600 hover:underline shrink-0"
                  >
                    Ver documento
                  </a>
                </div>
                <p class="text-xs text-slate-600 dark:text-slate-300 mt-1">{{ cit.snippet }}</p>
                <button
                  class="mt-2 text-xs font-medium text-indigo-600 hover:underline"
                  @click="openObservationForCitation(c, cit)"
                >
                  Registrar validación
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
        <div class="flex items-center justify-between mb-1 gap-3">
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white">Observaciones</h3>
          <BaseButton variant="primary" @click="openCreateModal()">
            <PlusIcon class="w-4 h-4" />
            Nueva Observación
          </BaseButton>
        </div>

        <!-- Estampa del auditor: quién trabajó esta auditoría, según los autores reales de las
        observaciones registradas (no "quién está habilitado para auditar" -- eso es la columna
        "Auditor asignado" de /evaluations, ver EvaluationDto.assignedAuditorNames). -->
        <div
          v-if="auditStampNames.length > 0"
          class="flex items-center gap-2 mb-4 text-xs text-slate-500 dark:text-slate-400"
        >
          <CheckBadgeIcon class="w-4 h-4 text-indigo-500 shrink-0" />
          <span>
            Auditoría realizada por
            <span class="font-medium text-slate-700 dark:text-slate-200">{{ auditStampNames.join(', ') }}</span>
          </span>
        </div>

        <div v-if="observations.length === 0" class="text-center py-8 text-slate-500 dark:text-slate-400">
          No hay observaciones registradas
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="obs in observations"
            :key="obs.id"
            class="p-4 rounded-lg border"
            :class="obsTypeClass(obs.type)"
          >
            <div class="flex items-start justify-between">
              <div class="flex-1">
                <div class="flex items-center gap-2 mb-1">
                  <span :class="['text-xs font-semibold uppercase', obsTypeTextClass(obs.type)]">{{
                    obsTypeLabel(obs.type)
                  }}</span>
                  <span :class="['text-xs px-2 py-0.5 rounded-full', obsStatusClass(obs.status)]">{{
                    obsStatusLabel(obs.status)
                  }}</span>
                </div>
                <p class="text-sm text-slate-700 dark:text-slate-200">
                  {{ obs.description }}
                </p>
                <p class="text-xs text-slate-400 mt-2">
                  {{ formatDate(obs.createdAt) }}
                  <span v-if="obs.createdByName"> — Registrada por {{ obs.createdByName }}</span>
                </p>
              </div>
              <div class="flex gap-1 shrink-0">
                <button
                  v-if="obs.status === 'OPEN'"
                  class="p-1.5 rounded-lg hover:bg-blue-50 text-slate-400 hover:text-blue-600"
                  title="Marcar en progreso"
                  @click="updateObservationStatus(obs.id, 'IN_PROGRESS')"
                >
                  <PlayIcon class="w-4 h-4" />
                </button>
                <button
                  v-if="obs.status === 'IN_PROGRESS'"
                  class="p-1.5 rounded-lg hover:bg-emerald-50 text-slate-400 hover:text-emerald-600"
                  title="Marcar resuelta"
                  @click="updateObservationStatus(obs.id, 'RESOLVED')"
                >
                  <CheckIcon class="w-4 h-4" />
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <BaseModal v-model="showCreateModal" title="Nueva Observación" size="lg">
      <form class="space-y-4" @submit.prevent="handleCreateObservation">
        <BaseSelect v-model="newObs.type" label="Tipo" :options="obsTypeOptions" required />
        <BaseTextarea
          v-model="newObs.description"
          label="Descripción"
          placeholder="Describa la observación"
          :rows="4"
          required
        />
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showCreateModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="saving" @click="handleCreateObservation">
          Crear
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, computed, onMounted } from 'vue';
  import { useRoute } from 'vue-router';
  import {
    auditService,
    evidenceService,
    evaluationsService,
    catalogService,
  } from '@/services/resources';
  import { formatDate } from '@/utils/helpers';
  import { useNotification } from '@/composables/useUtils';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import EvaluationLifecycleStepper from '@/components/evaluations/EvaluationLifecycleStepper.vue';
  import {
    ArrowLeftIcon,
    ArrowTrendingUpIcon,
    PlusIcon,
    PlayIcon,
    CheckIcon,
    SparklesIcon,
    CheckBadgeIcon,
  } from '@heroicons/vue/24/outline';
  import type {
    AuditObservation,
    Evaluation,
    Evidence,
    EvidenceCitation,
    MaturityCatalog,
  } from '@/types';

  const route = useRoute();
  const notification = useNotification();

  const evaluationId = route.params.evaluationId as string;
  const loading = ref(true);
  const saving = ref(false);
  const showCreateModal = ref(false);
  const observations = ref<AuditObservation[]>([]);
  const evaluation = ref<Evaluation | null>(null);

  // Autores reales (no "asignados") de las observaciones registradas hasta ahora -- la estampa
  // de qué auditor(es) estuvieron trabajando esta auditoría en concreto.
  const auditStampNames = computed(() => [
    ...new Set(
      observations.value.map((o) => o.createdByName).filter((name): name is string => !!name),
    ),
  ]);

  const newObs = reactive({
    type: 'OBSERVATION' as AuditObservation['type'],
    description: '',
    controlId: undefined as string | undefined,
  });

  // ---------------- Asistente de IA — citas de evidencia por control ----------------

  interface ControlWithEvidence {
    controlId: string;
    code: string;
    description: string;
  }

  const evidences = ref<Evidence[]>([]);
  const controlsWithEvidence = ref<ControlWithEvidence[]>([]);
  const searchingControlId = ref<string | null>(null);
  const citationsByControl = reactive<Record<string, EvidenceCitation[]>>({});

  async function loadControlsWithEvidence() {
    try {
      const [{ data: evidenceData }, { data: evaluationData }] = await Promise.all([
        evidenceService.getByEvaluation(evaluationId),
        evaluationsService.getById(evaluationId),
      ]);
      evidences.value = evidenceData;
      evaluation.value = evaluationData;

      const controlIds = [
        ...new Set(evidenceData.map((e) => e.controlId).filter(Boolean)),
      ] as string[];
      if (controlIds.length === 0) {
        controlsWithEvidence.value = [];
        return;
      }

      const { data } = await catalogService.getByVersion(evaluationData.catalogVersion);
      const catalog = data as MaturityCatalog;
      const controlById = new Map<string, { code: string; description: string }>();
      for (const fn of catalog.functions ?? []) {
        for (const category of fn.categories ?? []) {
          for (const subcategory of category.subcategories ?? []) {
            for (const requirement of subcategory.requirements ?? []) {
              for (const control of requirement.controls ?? []) {
                controlById.set(control.id, {
                  code: control.code,
                  description: control.description,
                });
              }
            }
          }
        }
      }

      controlsWithEvidence.value = controlIds
        .filter((id) => controlById.has(id))
        .map((id) => ({ controlId: id, ...controlById.get(id)! }));
    } catch (err) {
      console.error(err);
    }
  }

  async function searchCitations(control: ControlWithEvidence) {
    searchingControlId.value = control.controlId;
    try {
      const { data } = await evidenceService.getCitations(evaluationId, control.controlId);
      citationsByControl[control.controlId] = data;
    } catch (err) {
      notification.error('No se pudieron obtener citas de evidencia para este control');
      console.error(err);
    } finally {
      searchingControlId.value = null;
    }
  }

  function documentUrl(citation: EvidenceCitation): string | null {
    const evidence = evidences.value.find((e) => e.id === citation.evidenceId);
    if (!evidence?.url) return null;
    const pageMatch = citation.location?.match(/Página (\d+)/);
    return pageMatch ? `${evidence.url}#page=${pageMatch[1]}` : evidence.url;
  }

  function openObservationForCitation(control: ControlWithEvidence, citation: EvidenceCitation) {
    newObs.type = 'OBSERVATION';
    newObs.controlId = control.controlId;
    newObs.description =
      `Revisión de evidencia para ${control.code} — "${citation.fileName ?? 'evidencia'}"` +
      `${citation.location ? ` (${citation.location})` : ''}: ${citation.snippet}`;
    showCreateModal.value = true;
  }

  function openCreateModal() {
    newObs.type = 'OBSERVATION';
    newObs.controlId = undefined;
    newObs.description = '';
    showCreateModal.value = true;
  }

  const obsTypeOptions = [
    { value: 'OBSERVATION', label: 'Observación' },
    { value: 'NON_CONFORMITY', label: 'No Conformidad' },
    { value: 'RECOMMENDATION', label: 'Recomendación' },
  ];

  function obsTypeLabel(type: string) {
    return (
      {
        OBSERVATION: 'Observación',
        NON_CONFORMITY: 'No Conformidad',
        RECOMMENDATION: 'Recomendación',
      }[type] || type
    );
  }

  function obsTypeClass(type: string) {
    return (
      {
        OBSERVATION: 'bg-blue-50 border-blue-200',
        NON_CONFORMITY: 'bg-red-50 border-red-200',
        RECOMMENDATION: 'bg-amber-50 border-amber-200',
      }[type] || 'bg-slate-50 dark:bg-slate-900 border-slate-200 dark:border-slate-700'
    );
  }

  function obsTypeTextClass(type: string) {
    return (
      {
        OBSERVATION: 'text-blue-700',
        NON_CONFORMITY: 'text-red-700',
        RECOMMENDATION: 'text-amber-700',
      }[type] || 'text-slate-700 dark:text-slate-200'
    );
  }

  function obsStatusLabel(status: string) {
    return (
      { OPEN: 'Abierta', IN_PROGRESS: 'En Progreso', RESOLVED: 'Resuelta', CLOSED: 'Cerrada' }[
        status
      ] || status
    );
  }

  function obsStatusClass(status: string) {
    return (
      {
        OPEN: 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300',
        IN_PROGRESS: 'bg-blue-100 text-blue-700',
        RESOLVED: 'bg-emerald-100 text-emerald-700',
        CLOSED: 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400',
      }[status] || 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300'
    );
  }

  async function fetchObservations() {
    loading.value = true;
    try {
      const { data } = await auditService.getObservations(evaluationId);
      observations.value = data;
    } catch (err) {
      console.error(err);
    } finally {
      loading.value = false;
    }
  }

  async function handleCreateObservation() {
    if (!newObs.description) return;
    saving.value = true;
    try {
      await auditService.createObservation({
        evaluationId,
        controlId: newObs.controlId,
        type: newObs.type,
        description: newObs.description,
      });
      notification.success('Observación creada');
      showCreateModal.value = false;
      newObs.type = 'OBSERVATION';
      newObs.controlId = undefined;
      newObs.description = '';
      await fetchObservations();
    } catch {
      notification.error('Error al crear la observación');
    } finally {
      saving.value = false;
    }
  }

  async function updateObservationStatus(id: string, status: AuditObservation['status']) {
    try {
      await auditService.updateObservation(id, { status });
      await fetchObservations();
    } catch (err) {
      console.error(err);
    }
  }

  onMounted(() => {
    fetchObservations();
    loadControlsWithEvidence();
  });
</script>
