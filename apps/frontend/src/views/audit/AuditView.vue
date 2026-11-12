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
        class="inline-flex items-center gap-2 px-4 py-2 bg-emerald-700 text-white text-sm font-medium rounded-lg hover:bg-emerald-800"
      >
        <ArrowTrendingUpIcon class="w-4 h-4" /> Plan de Mejora
      </router-link>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <template v-else>
      <EvaluationLifecycleStepper v-if="evaluation" :status="evaluation.status" />
      <div
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
      >
        <div class="flex items-center gap-2 mb-1">
          <SparklesIcon class="w-5 h-5 text-indigo-600 dark:text-indigo-400" />
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
                <p class="text-xs text-slate-500 dark:text-slate-400 truncate">
                  {{ c.description }}
                </p>
              </div>
              <BaseButton
                variant="secondary"
                :loading="searchingControlId === c.controlId"
                @click="searchCitations(c)"
              >
                Buscar citas
              </BaseButton>
            </div>

            <p
              v-if="citationsError[c.controlId]"
              class="mt-3 text-xs text-amber-700 dark:text-amber-400"
              role="alert"
              data-testid="citations-ai-unavailable"
            >
              {{ citationsError[c.controlId] }}
            </p>

            <div v-if="citationsByControl[c.controlId]" class="mt-3 space-y-2">
              <p
                v-if="citationsByControl[c.controlId].length === 0"
                class="text-xs text-slate-500 dark:text-slate-400"
              >
                No se encontraron fragmentos relevantes en la evidencia cargada.
              </p>
              <div
                v-for="(cit, i) in citationsByControl[c.controlId]"
                :key="i"
                class="p-3 rounded-lg bg-slate-50 dark:bg-slate-900 border border-slate-100 dark:border-slate-700"
              >
                <div class="flex items-center justify-between gap-2">
                  <p class="text-xs font-medium text-slate-700 dark:text-slate-200 truncate">
                    {{ cit.fileName }} <span v-if="cit.location">· {{ cit.location }}</span>
                  </p>
                  <button
                    v-if="canViewDocument"
                    class="text-xs text-blue-600 hover:underline shrink-0 dark:text-blue-400"
                    @click="openDocument(cit)"
                  >
                    Ver documento
                  </button>
                </div>
                <p class="text-xs text-slate-600 dark:text-slate-300 mt-1">{{ cit.snippet }}</p>
                <div v-if="canWriteObservations" class="mt-2 flex flex-wrap items-center gap-3">
                  <button
                    type="button"
                    class="text-xs font-medium px-2 py-1 rounded border"
                    :class="
                      citationDecision[citationKey(c, i)] === 'ACCEPTED'
                        ? 'bg-green-600 text-white border-green-600'
                        : 'text-green-700 border-green-300 hover:bg-green-50 dark:text-green-400 dark:border-green-700 dark:hover:bg-green-950'
                    "
                    :aria-pressed="citationDecision[citationKey(c, i)] === 'ACCEPTED'"
                    data-testid="accept-citation"
                    @click="decideCitation(c, cit, i, 'ACCEPTED')"
                  >
                    Aceptar cita
                  </button>
                  <button
                    type="button"
                    class="text-xs font-medium px-2 py-1 rounded border"
                    :class="
                      citationDecision[citationKey(c, i)] === 'REJECTED'
                        ? 'bg-red-600 text-white border-red-600'
                        : 'text-red-700 border-red-300 hover:bg-red-50 dark:text-red-400 dark:border-red-700 dark:hover:bg-red-950'
                    "
                    :aria-pressed="citationDecision[citationKey(c, i)] === 'REJECTED'"
                    data-testid="reject-citation"
                    @click="decideCitation(c, cit, i, 'REJECTED')"
                  >
                    Rechazar cita
                  </button>
                  <button
                    type="button"
                    class="text-xs font-medium text-indigo-600 hover:underline dark:text-indigo-400"
                    @click="openObservationForCitation(c, cit)"
                  >
                    Registrar validación
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div
        v-if="flaggedAuditControls.length > 0"
        class="bg-amber-50 dark:bg-amber-950 rounded-xl border border-amber-200 dark:border-amber-800 p-6"
        data-testid="flagged-controls"
      >
        <h3 class="text-lg font-semibold text-amber-900 dark:text-amber-200 mb-1">
          Controles marcados para revisar ({{ flaggedAuditControls.length }})
        </h3>
        <p class="text-sm text-amber-800 dark:text-amber-300 mb-3">
          Los marcó el equipo que respondió la evaluación (o el propio auditor) para una segunda
          mirada.
        </p>
        <ul class="space-y-1.5">
          <li
            v-for="c in flaggedAuditControls"
            :key="c.id"
            class="text-sm text-amber-900 dark:text-amber-200"
            data-testid="flagged-control"
          >
            <span class="font-medium">{{ c.code }}</span> — {{ c.description }}
            <span v-if="flagAuthors[c.id]" class="text-xs text-amber-700 dark:text-amber-400">
              · marcado por {{ flagAuthors[c.id] }}
            </span>
          </li>
        </ul>
      </div>

      <div
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
        data-testid="verdict-section"
      >
        <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-1">
          Dictamen por control
        </h3>
        <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
          Marque cada control como Validado, No validado o Requiere más evidencia. Es el resultado
          de la revisión del control; las observaciones siguen siendo el registro de hallazgos.
          <span v-if="!canSetVerdict" class="block mt-1 text-amber-700 dark:text-amber-400">
            El dictamen se puede registrar solo mientras la evaluación está en auditoría.
          </span>
        </p>

        <div class="flex flex-wrap gap-2 mb-4 text-xs" data-testid="verdict-summary">
          <span class="px-2 py-1 rounded-full bg-emerald-100 text-emerald-700"
            >Validados: {{ verdictCounts.VALIDATED }}</span
          >
          <span class="px-2 py-1 rounded-full bg-red-100 text-red-700"
            >No validados: {{ verdictCounts.NOT_VALIDATED }}</span
          >
          <span class="px-2 py-1 rounded-full bg-amber-100 text-amber-700"
            >Requieren más evidencia: {{ verdictCounts.NEEDS_MORE_EVIDENCE }}</span
          >
          <span class="px-2 py-1 rounded-full bg-slate-100 text-slate-600"
            >Sin dictamen: {{ verdictCounts.NONE }}</span
          >
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-4">
          <input
            v-model="verdictSearch"
            type="text"
            placeholder="Buscar por código o descripción"
            aria-label="Buscar control"
            class="w-full px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-500 text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
          />
          <select
            v-model="verdictFilter"
            aria-label="Filtrar por dictamen"
            class="w-full px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-500 text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
          >
            <option v-for="o in verdictFilterOptions" :key="o.value" :value="o.value">
              {{ o.label }}
            </option>
          </select>
        </div>

        <div
          v-if="filteredVerdictControls.length === 0"
          class="text-center py-6 text-slate-500 dark:text-slate-400 text-sm"
        >
          No hay controles que coincidan.
        </div>
        <div v-else class="space-y-2">
          <div
            v-for="c in visibleVerdictControls"
            :key="c.id"
            class="flex items-center justify-between gap-3 border border-slate-200 dark:border-slate-700 rounded-lg p-3"
            data-testid="verdict-row"
          >
            <div class="min-w-0">
              <p class="text-sm font-medium text-slate-900 dark:text-white">{{ c.code }}</p>
              <p class="text-xs text-slate-500 dark:text-slate-400 truncate">
                {{ c.description }}
              </p>
            </div>
            <select
              :value="verdicts[c.id] ?? ''"
              :disabled="!canSetVerdict || savingVerdictId === c.id"
              :aria-label="`Dictamen de ${c.code}`"
              class="shrink-0 px-2 py-1.5 rounded-lg border border-slate-300 dark:border-slate-500 text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white disabled:opacity-60"
              @change="setVerdict(c.id, ($event.target as HTMLSelectElement).value)"
            >
              <option value="" disabled>Sin dictamen</option>
              <option v-for="o in VERDICT_OPTIONS" :key="o.value" :value="o.value">
                {{ o.label }}
              </option>
            </select>
          </div>
          <button
            v-if="visibleVerdictControls.length < filteredVerdictControls.length"
            class="text-xs font-medium text-blue-600 hover:underline dark:text-blue-400"
            @click="verdictLimit += 25"
          >
            Mostrar más ({{ filteredVerdictControls.length - visibleVerdictControls.length }}
            restantes)
          </button>
        </div>
      </div>

      <div
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
      >
        <div class="flex items-center justify-between mb-1 gap-3">
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white">Observaciones</h3>
          <BaseButton v-if="canWriteObservations" variant="primary" @click="openCreateModal()">
            <PlusIcon class="w-4 h-4" />
            Nueva Observación
          </BaseButton>
        </div>
        <p
          v-if="evaluation && !canWriteObservations"
          class="mb-4 text-xs text-amber-700 dark:text-amber-400"
          data-testid="observations-locked"
        >
          Las observaciones solo se pueden registrar o modificar mientras la evaluación está en
          auditoría.
        </p>

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
            <span class="font-medium text-slate-700 dark:text-slate-200">{{
              auditStampNames.join(', ')
            }}</span>
          </span>
        </div>

        <div
          v-if="observations.length === 0"
          class="text-center py-8 text-slate-500 dark:text-slate-400"
        >
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
                  <span
                    v-if="obs.subcategoryId && subcategoryById.get(obs.subcategoryId)"
                    class="text-xs px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
                  >
                    {{ subcategoryById.get(obs.subcategoryId)!.code }}
                  </span>
                </div>
                <p class="text-sm text-slate-700 dark:text-slate-200">
                  {{ obs.description }}
                </p>
                <p
                  v-if="obs.evidenceId"
                  class="text-xs text-slate-600 mt-1 dark:text-slate-300"
                  data-testid="observation-evidence"
                >
                  Documento citado: {{ obs.evidenceFileName ?? 'evidencia' }}
                  <button
                    v-if="canViewDocument"
                    type="button"
                    class="ml-1 text-blue-600 hover:underline dark:text-blue-400"
                    data-testid="observation-open-document"
                    @click="
                      openDocument({ evidenceId: obs.evidenceId, location: observationPage(obs) })
                    "
                  >
                    Ver documento
                  </button>
                </p>
                <p class="text-xs text-slate-500 mt-2 dark:text-slate-400">
                  {{ formatDate(obs.createdAt) }}
                  <span v-if="obs.createdByName"> — Registrada por {{ obs.createdByName }}</span>
                </p>
              </div>
              <div v-if="canWriteObservations" class="flex gap-1 shrink-0">
                <button
                  class="p-1.5 rounded-lg hover:bg-slate-100 text-slate-500 hover:text-slate-700 dark:hover:bg-slate-700 dark:hover:text-slate-200 dark:text-slate-400"
                  title="Editar observación"
                  data-testid="edit-observation"
                  @click="openEditModal(obs)"
                >
                  <PencilIcon class="w-4 h-4" />
                </button>
                <button
                  v-if="obs.status === 'OPEN'"
                  class="p-1.5 rounded-lg hover:bg-blue-50 text-slate-500 hover:text-blue-600 dark:hover:bg-blue-950 dark:hover:text-blue-300 dark:text-slate-400"
                  title="Marcar en progreso"
                  @click="updateObservationStatus(obs.id, 'IN_PROGRESS')"
                >
                  <PlayIcon class="w-4 h-4" />
                </button>
                <button
                  v-if="obs.status === 'IN_PROGRESS'"
                  class="p-1.5 rounded-lg hover:bg-emerald-50 text-slate-500 hover:text-emerald-700 dark:hover:bg-emerald-950 dark:hover:text-emerald-300 dark:text-slate-400"
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

    <BaseModal
      v-model="showCreateModal"
      :title="editingObservation ? 'Editar Observación' : 'Nueva Observación'"
      size="lg"
    >
      <form class="space-y-4" @submit.prevent="handleSubmitObservation">
        <BaseSelect v-model="newObs.type" label="Tipo" :options="obsTypeOptions" required />
        <BaseSelect
          v-if="!editingObservation && subcategoryOptions.length > 1"
          v-model="newObs.subcategoryId"
          label="Subcategoría"
          :options="subcategoryOptions"
        />
        <BaseTextarea
          v-model="newObs.description"
          label="Descripción"
          placeholder="Describa la observación"
          :rows="4"
          :error="obsError"
          required
        />
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showCreateModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="saving" @click="handleSubmitObservation">
          {{ editingObservation ? 'Guardar' : 'Crear' }}
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
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import { useNotification } from '@/composables/useUtils';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import { useAuthStore } from '@/stores/auth';
  import {
    VERDICT_OPTIONS,
    VERDICT_LABELS,
    flattenControls,
    filterControls,
    countByVerdict,
    type VerdictControl,
  } from '@/utils/auditVerdicts';
  import EvaluationLifecycleStepper from '@/components/evaluations/EvaluationLifecycleStepper.vue';
  import {
    ArrowLeftIcon,
    ArrowTrendingUpIcon,
    PlusIcon,
    PlayIcon,
    PencilIcon,
    CheckIcon,
    SparklesIcon,
    CheckBadgeIcon,
  } from '@heroicons/vue/24/outline';
  import type {
    AuditObservation,
    Evaluation,
    AuditVerdict,
    Evidence,
    EvidenceCitation,
    MaturityCatalog,
  } from '@/types';

  const route = useRoute();
  const notification = useNotification();
  const authStore = useAuthStore();

  const evaluationId = route.params.evaluationId as string;
  const loading = ref(true);
  const saving = ref(false);
  const showCreateModal = ref(false);
  const observations = ref<AuditObservation[]>([]);
  const evaluation = ref<Evaluation | null>(null);
  // Crear, editar y cambiar el estado de una observación solo se puede mientras la evaluación está
  // en auditoría (el backend lo exige igual y responde 409): fuera de eso quedan como registro.
  const canWriteObservations = computed(() => evaluation.value?.status === 'IN_AUDIT');
  const editingObservation = ref<AuditObservation | null>(null);
  const obsError = ref('');

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
    subcategoryId: '',
    // Evidencia citada de la que sale la observación (RF-EVI-15): solo al registrarla desde una cita.
    evidenceId: undefined as string | undefined,
  });

  // RF-AUD-05: además de por control, el auditor puede atar una observación a una subcategoría
  // del catálogo -- lista plana armada a partir del mismo catálogo que ya se trae para resolver
  // los controles con evidencia (ver loadControlsWithEvidence).
  const subcategories = ref<{ id: string; code: string; name: string }[]>([]);
  const subcategoryById = computed(() => new Map(subcategories.value.map((s) => [s.id, s])));
  const subcategoryOptions = computed(() => [
    { value: '', label: 'Sin subcategoría' },
    ...subcategories.value.map((s) => ({
      value: s.id,
      label: s.code ? `${s.code} — ${s.name}` : s.name,
    })),
  ]);

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
  // «La IA no está disponible» es distinto de «sin resultados» (PRI-359).
  const citationsError = reactive<Record<string, string>>({});
  // Decisión del auditor sobre cada cita (aceptar/rechazar), por control y posición en la lista.
  const citationDecision = reactive<Record<string, 'ACCEPTED' | 'REJECTED'>>({});

  function citationKey(control: ControlWithEvidence, index: number) {
    return `${control.controlId}:${index}`;
  }

  async function decideCitation(
    control: ControlWithEvidence,
    citation: EvidenceCitation,
    index: number,
    decision: 'ACCEPTED' | 'REJECTED',
  ) {
    const key = citationKey(control, index);
    if (citationDecision[key] === decision) return;
    try {
      await evidenceService.sendCitationFeedback(evaluationId, [
        {
          controlId: control.controlId,
          fileName: citation.fileName,
          location: citation.location,
          snippet: citation.snippet,
          score: citation.score ?? null,
          decision,
        },
      ]);
      citationDecision[key] = decision;
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo registrar la decisión sobre la cita'));
    }
  }

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
      const flatSubcategories: { id: string; code: string; name: string }[] = [];
      for (const fn of catalog.functions ?? []) {
        for (const category of fn.categories ?? []) {
          for (const subcategory of category.subcategories ?? []) {
            flatSubcategories.push({
              id: subcategory.id,
              code: subcategory.code ?? '',
              name: subcategory.name,
            });
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
      subcategories.value = flatSubcategories;

      controlsWithEvidence.value = controlIds
        .filter((id) => controlById.has(id))
        .map((id) => ({ controlId: id, ...controlById.get(id)! }));
    } catch (err) {
      console.error(err);
    }
  }

  async function searchCitations(control: ControlWithEvidence) {
    searchingControlId.value = control.controlId;
    delete citationsError[control.controlId];
    try {
      const { data } = await evidenceService.getCitations(evaluationId, control.controlId);
      citationsByControl[control.controlId] = data;
      // Una búsqueda nueva trae otra lista: las decisiones anteriores ya no corresponden.
      Object.keys(citationDecision)
        .filter((k) => k.startsWith(`${control.controlId}:`))
        .forEach((k) => delete citationDecision[k]);
    } catch (err) {
      if ((err as { response?: { status?: number } }).response?.status === 503) {
        delete citationsByControl[control.controlId];
        citationsError[control.controlId] =
          'El asistente de IA no está disponible en este momento. Probá de nuevo más tarde.';
      } else {
        notification.error(getErrorMessage(err, 'No se pudieron obtener citas de evidencia'));
      }
      console.error(err);
    } finally {
      searchingControlId.value = null;
    }
  }

  // Pueden bajar archivos PRISMA_ADMIN, ORG_RESPONSIBLE y AUDITOR (PRI-286): para el resto el botón no
  // aparece y se trabaja con el fragmento citado.
  const canViewDocument = authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'AUDITOR');

  // La ubicación («Página N») de la cita viaja en la descripción de la observación (el vínculo
  // estructurado es solo con la evidencia): se recupera de ahí para abrir el documento en esa
  // página.
  function observationPage(obs: AuditObservation): string | null {
    const match = obs.description.match(/\(Página \d+\)/);
    return match ? match[0].slice(1, -1) : null;
  }

  async function openDocument(citation: Pick<EvidenceCitation, 'evidenceId' | 'location'>) {
    if (!citation.evidenceId) return;
    try {
      const { data } = await evidenceService.download(citation.evidenceId);
      const pageMatch = citation.location?.match(/Página (\d+)/);
      const url = URL.createObjectURL(data);
      window.open(pageMatch ? `${url}#page=${pageMatch[1]}` : url, '_blank');
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo abrir el documento'));
    }
  }

  function openObservationForCitation(control: ControlWithEvidence, citation: EvidenceCitation) {
    editingObservation.value = null;
    obsError.value = '';
    newObs.type = 'OBSERVATION';
    newObs.controlId = control.controlId;
    newObs.subcategoryId = '';
    newObs.evidenceId = citation.evidenceId ?? undefined;
    newObs.description =
      `Revisión de evidencia para ${control.code} — "${citation.fileName ?? 'evidencia'}"` +
      `${citation.location ? ` (${citation.location})` : ''}: ${citation.snippet}`;
    showCreateModal.value = true;
  }

  function openCreateModal() {
    // Sin esto, después de editar una observación «Nueva observación» seguía en modo edición.
    editingObservation.value = null;
    obsError.value = '';
    newObs.type = 'OBSERVATION';
    newObs.controlId = undefined;
    newObs.subcategoryId = '';
    newObs.evidenceId = undefined;
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
        OBSERVATION: 'bg-blue-50 border-blue-200 dark:bg-blue-950 dark:border-blue-800',
        NON_CONFORMITY: 'bg-red-50 border-red-200 dark:bg-red-950 dark:border-red-800',
        RECOMMENDATION: 'bg-amber-50 border-amber-200 dark:bg-amber-950 dark:border-amber-800',
      }[type] || 'bg-slate-50 dark:bg-slate-900 border-slate-200 dark:border-slate-700'
    );
  }

  function obsTypeTextClass(type: string) {
    return (
      {
        OBSERVATION: 'text-blue-700 dark:text-blue-400',
        NON_CONFORMITY: 'text-red-700 dark:text-red-400',
        RECOMMENDATION: 'text-amber-700 dark:text-amber-400',
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
        IN_PROGRESS: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300',
        RESOLVED: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300',
        CLOSED: 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300',
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

  function openEditModal(obs: AuditObservation) {
    editingObservation.value = obs;
    obsError.value = '';
    newObs.type = obs.type;
    newObs.description = obs.description;
    showCreateModal.value = true;
  }

  async function handleSubmitObservation() {
    obsError.value = '';
    if (!newObs.description.trim()) {
      obsError.value = 'La descripción es obligatoria';
      return;
    }
    if (editingObservation.value) {
      await handleEditObservation(editingObservation.value.id);
    } else {
      await handleCreateObservation();
    }
  }

  // Edición de tipo y descripción (PRI-354). Los errores se muestran: antes un fallo se tragaba en
  // silencio y el auditor no sabía por qué no pasaba nada.
  async function handleEditObservation(id: string) {
    saving.value = true;
    try {
      await auditService.updateObservation(id, {
        type: newObs.type,
        description: newObs.description,
      });
      notification.success('Observación actualizada');
      showCreateModal.value = false;
      await fetchObservations();
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo actualizar la observación'));
    } finally {
      saving.value = false;
    }
  }

  async function handleCreateObservation() {
    saving.value = true;
    try {
      await auditService.createObservation({
        evaluationId,
        controlId: newObs.controlId,
        subcategoryId: newObs.subcategoryId || undefined,
        evidenceId: newObs.evidenceId,
        type: newObs.type,
        description: newObs.description,
      });
      notification.success('Observación creada');
      showCreateModal.value = false;
      newObs.type = 'OBSERVATION';
      newObs.controlId = undefined;
      newObs.subcategoryId = '';
      newObs.evidenceId = undefined;
      newObs.description = '';
      await fetchObservations();
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al crear la observación'));
    } finally {
      saving.value = false;
    }
  }

  // Cambiar el estado fallaba en silencio (solo un console.error): el auditor veía que el botón no
  // hacía nada. Ahora se avisa con el mensaje del backend, o uno genérico.
  async function updateObservationStatus(id: string, status: AuditObservation['status']) {
    try {
      await auditService.updateObservation(id, { status });
      await fetchObservations();
    } catch (err) {
      console.error(err);
      notification.error(getErrorMessage(err, 'No se pudo cambiar el estado de la observación'));
    }
  }

  // ---------------- Dictamen por control (RF-AUD-06) ----------------

  const verdictControls = ref<VerdictControl[]>([]);
  const flaggedIds = ref<Set<string>>(new Set());
  const flagAuthors = reactive<Record<string, string>>({});
  const flaggedAuditControls = computed(() =>
    verdictControls.value.filter((c) => flaggedIds.value.has(c.id)),
  );
  const verdicts = reactive<Record<string, AuditVerdict>>({});
  const verdictSearch = ref('');
  const verdictFilter = ref<'ALL' | 'NONE' | AuditVerdict>('ALL');
  const verdictLimit = ref(25);
  const savingVerdictId = ref<string | null>(null);

  const verdictFilterOptions = [
    { value: 'ALL', label: 'Todos los controles' },
    { value: 'NONE', label: 'Sin dictamen' },
    ...Object.entries(VERDICT_LABELS).map(([value, label]) => ({ value, label })),
  ];

  // El backend solo acepta el dictamen con la evaluación en auditoría.
  const canSetVerdict = computed(() => evaluation.value?.status === 'IN_AUDIT');
  const verdictCounts = computed(() => countByVerdict(verdictControls.value, verdicts));
  const filteredVerdictControls = computed(() =>
    filterControls(verdictControls.value, verdicts, verdictSearch.value, verdictFilter.value),
  );
  const visibleVerdictControls = computed(() =>
    filteredVerdictControls.value.slice(0, verdictLimit.value),
  );

  async function loadVerdicts() {
    try {
      const { data: evaluationData } = await evaluationsService.getById(evaluationId);
      evaluation.value = evaluationData;
      const [{ data: catalog }, { data: saved }, { data: flags }] = await Promise.all([
        catalogService.getByVersion(
          evaluationData.catalogVersion,
          evaluationData.communityProfileId,
        ),
        auditService.getVerdicts(evaluationId),
        evaluationsService.getFlags(evaluationId),
      ]);
      flaggedIds.value = new Set(flags.map((fl) => fl.controlId));
      for (const fl of flags) {
        if (fl.flaggedByName) flagAuthors[fl.controlId] = fl.flaggedByName;
      }
      verdictControls.value = flattenControls(catalog as MaturityCatalog);
      for (const v of saved) verdicts[v.controlId] = v.verdict;
    } catch (err) {
      console.error(err);
    }
  }

  async function setVerdict(controlId: string, value: string) {
    const previous = verdicts[controlId];
    verdicts[controlId] = value as AuditVerdict;
    savingVerdictId.value = controlId;
    try {
      await auditService.setVerdict(evaluationId, controlId, value as AuditVerdict);
    } catch (err) {
      if (previous) verdicts[controlId] = previous;
      else delete verdicts[controlId];
      notification.error(getErrorMessage(err, 'No se pudo guardar el dictamen'));
    } finally {
      savingVerdictId.value = null;
    }
  }

  onMounted(() => {
    fetchObservations();
    loadControlsWithEvidence();
    loadVerdicts();
  });
</script>
