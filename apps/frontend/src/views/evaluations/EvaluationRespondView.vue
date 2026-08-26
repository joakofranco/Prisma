<template>
  <div class="space-y-4">
    <div class="flex items-center justify-between gap-3">
      <div class="flex items-center gap-3 min-w-0">
        <router-link
          :to="`/evaluations/${evaluationId}`"
          class="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400 shrink-0"
          title="Volver a la evaluación"
        >
          <ArrowLeftIcon class="w-5 h-5" />
        </router-link>
        <div class="min-w-0">
          <h1 class="text-xl font-bold text-slate-900 dark:text-white truncate">
            {{ evaluation?.name || 'Responder Evaluación' }}
          </h1>
          <p class="text-xs text-slate-400 truncate">
            {{ evaluation?.organizationName }} · Catálogo MCU {{ evaluation?.catalogVersion }}
            <span v-if="evaluation?.communityProfileName">
              · Perfil {{ evaluation.communityProfileName }}</span
            >
          </p>
        </div>
      </div>
      <BaseButton variant="secondary" :loading="saving" class="shrink-0" @click="saveProgress">
        Guardar progreso
      </BaseButton>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <div
      v-else-if="flatQuestions.length === 0"
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-8 text-center"
    >
      <p class="text-slate-500 dark:text-slate-400">
        Este catálogo{{ evaluation?.communityProfileName ? ' / perfil comunitario' : '' }} no tiene
        controles para responder.
      </p>
    </div>

    <div v-else class="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-6 items-start">
      <!-- Navegador del catálogo -->
      <aside
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 lg:sticky lg:top-20 max-h-[calc(100vh-6rem)] overflow-y-auto"
      >
        <h2 class="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-3">
          Estructura MCU {{ evaluation?.catalogVersion }}
        </h2>
        <nav class="space-y-1">
          <div v-for="(func, fi) in catalogFunctions" :key="func.id">
            <button
              class="w-full flex items-center justify-between py-2 text-left gap-2"
              @click="toggleFunction(func.id)"
            >
              <span class="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate"
                >{{ fi + 1 }}. {{ func.name }}</span
              >
              <span class="flex items-center gap-1.5 shrink-0">
                <span class="text-xs font-medium text-slate-400">{{
                  functionProgressLabel(func.id)
                }}</span>
                <ChevronDownIcon
                  :class="[
                    'w-4 h-4 text-slate-400 transition-transform shrink-0',
                    expandedFunctions.has(func.id) ? 'rotate-180' : '',
                  ]"
                />
              </span>
            </button>

            <div
              v-if="expandedFunctions.has(func.id)"
              class="pl-2 ml-1 border-l border-slate-100 space-y-2 pb-2"
            >
              <div v-for="(cat, ci) in func.categories" :key="cat.id">
                <p class="text-xs font-medium text-slate-500 dark:text-slate-400 px-2 mt-2 mb-1">
                  {{ fi + 1 }}.{{ ci + 1 }} {{ cat.name }}
                </p>
                <button
                  v-for="(sub, si) in cat.subcategories"
                  :key="sub.id"
                  class="w-full flex items-center gap-2 px-2 py-1.5 rounded-lg text-left text-sm transition-colors"
                  :class="
                    isCurrentSubcategory(sub.id)
                      ? 'bg-blue-50 text-blue-700 font-medium'
                      : 'text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'
                  "
                  @click="goToSubcategory(sub.id)"
                >
                  <span class="w-2 h-2 rounded-full shrink-0" :class="dotClass(sub)" />
                  <span class="truncate"
                    >{{ fi + 1 }}.{{ ci + 1 }}.{{ si + 1 }} {{ sub.name }}</span
                  >
                </button>
              </div>
            </div>
          </div>
        </nav>
      </aside>

      <!-- Panel de pregunta -->
      <div class="space-y-4 min-w-0">
        <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5">
          <div class="flex items-center justify-between mb-2">
            <span class="text-sm font-medium text-slate-700 dark:text-slate-200">Progreso general del plan</span>
            <span class="text-lg font-bold text-slate-900 dark:text-white">{{ progressPct }}%</span>
          </div>
          <div class="w-full h-2.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
            <div
              class="h-full bg-blue-600 rounded-full transition-all duration-300"
              :style="{ width: progressPct + '%' }"
            />
          </div>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-1.5">
            {{ answeredCount }} de {{ totalQuestions }} preguntas respondidas
            <span v-if="remainingCount > 0">· faltan {{ remainingCount }}</span>
          </p>
          <div class="flex items-center justify-between gap-3 mt-3">
            <div class="flex items-center gap-1 text-xs min-w-0 overflow-hidden">
              <span class="text-slate-400 truncate">{{ currentQuestion.func.name }}</span>
              <ChevronRightIcon class="w-3 h-3 text-slate-300 shrink-0" />
              <span class="text-slate-400 truncate">{{ currentQuestion.category.name }}</span>
              <ChevronRightIcon class="w-3 h-3 text-slate-300 shrink-0" />
              <span class="text-slate-700 dark:text-slate-200 font-medium truncate">{{
                currentQuestion.subcategory.name
              }}</span>
            </div>
            <span class="text-xs text-slate-500 dark:text-slate-400 shrink-0">
              Pregunta {{ posInSubcategory }} de {{ totalInSubcategory }}
            </span>
          </div>
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
          <div class="flex items-start justify-between gap-3 mb-1">
            <div class="flex items-center gap-2">
              <span
                class="shrink-0 text-xs font-semibold px-2 py-0.5 rounded-full"
                :class="LEVEL_BADGE[currentQuestion.control.targetLevel]"
              >
                Nivel {{ currentQuestion.control.targetLevel }}
              </span>
              <span class="text-xs text-slate-400">{{ currentQuestion.control.code }}</span>
            </div>
            <button
              class="shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-medium transition-colors"
              :class="
                isFlagged
                  ? 'bg-amber-50 border-amber-300 text-amber-700'
                  : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'
              "
              @click="toggleFlag"
            >
              <BookmarkIconSolid v-if="isFlagged" class="w-4 h-4" />
              <BookmarkIcon v-else class="w-4 h-4" />
              Marcar para revisar
            </button>
          </div>
          <h3 class="text-lg font-bold text-slate-900 dark:text-white mt-2">
            {{ currentQuestion.control.description }}
          </h3>
          <p
            v-if="currentQuestion.requirement.description"
            class="text-sm text-slate-500 dark:text-slate-400 mt-1 mb-5"
          >
            Requisito {{ currentQuestion.requirement.code }} —
            {{ currentQuestion.requirement.description }}
          </p>
          <p v-else class="mb-5" />

          <div
            v-if="isAutoFailed(currentQuestion)"
            class="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800"
          >
            No se pregunta: el Requisito {{ currentQuestion.requirement.code }} ya no puede alcanzar
            el nivel {{ currentQuestion.control.targetLevel }} porque no cumplió un control de nivel
            {{ requirementFailedLevel.get(currentQuestion.requirement.id) }}. Queda registrado como
            "No cumple" automáticamente.
          </div>
          <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <button
              role="radio"
              :aria-checked="currentAnswer === true"
              class="flex items-center gap-3 p-4 rounded-xl border-2 text-left transition-colors"
              :class="
                currentAnswer === true
                  ? 'bg-emerald-50 border-emerald-500 ring-2 ring-emerald-300'
                  : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 hover:border-emerald-300'
              "
              @click="selectCompliant(true)"
            >
              <CheckCircleIcon
                class="w-6 h-6 shrink-0"
                :class="currentAnswer === true ? 'text-emerald-600' : 'text-slate-300'"
              />
              <span class="font-semibold text-slate-900 dark:text-white">Cumple</span>
            </button>
            <button
              role="radio"
              :aria-checked="currentAnswer === false"
              class="flex items-center gap-3 p-4 rounded-xl border-2 text-left transition-colors"
              :class="
                currentAnswer === false
                  ? 'bg-red-50 border-red-500 ring-2 ring-red-300'
                  : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 hover:border-red-300'
              "
              @click="selectCompliant(false)"
            >
              <XCircleIcon
                class="w-6 h-6 shrink-0"
                :class="currentAnswer === false ? 'text-red-600' : 'text-slate-300'"
              />
              <span class="font-semibold text-slate-900 dark:text-white">No cumple</span>
            </button>
          </div>

          <div class="mt-4">
            <button
              v-if="!showObservations && !currentObservations"
              class="text-xs font-medium text-blue-600 hover:underline"
              @click="showObservations = true"
            >
              + Agregar observación
            </button>
            <BaseTextarea
              v-else
              v-model="currentObservations"
              label="Observaciones (opcional)"
              placeholder="Notas, evidencia disponible o contexto adicional"
              :rows="2"
            />
          </div>
        </div>

        <div class="flex items-center justify-between gap-3">
          <BaseButton variant="outline" :disabled="isFirst" @click="goPrev"> Anterior </BaseButton>
          <BaseButton variant="primary" :loading="saving" @click="isLast ? finish() : goNext()">
            {{ isLast ? 'Guardar y Finalizar' : 'Siguiente' }}
          </BaseButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, computed, onMounted } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { useEvaluationsStore } from '@/stores/evaluations';
  import { catalogService } from '@/services/resources';
  import { useNotification } from '@/composables/useUtils';
  import { getErrorMessage } from '@/utils/helpers';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import {
    ArrowLeftIcon,
    ChevronDownIcon,
    ChevronRightIcon,
    BookmarkIcon,
    CheckCircleIcon,
    XCircleIcon,
  } from '@heroicons/vue/24/outline';
  import { BookmarkIcon as BookmarkIconSolid } from '@heroicons/vue/24/solid';
  import type {
    Evaluation,
    MaturityFunction,
    MaturityCategory,
    MaturitySubcategory,
    MaturityRequirement,
    MaturityControl,
    MaturityCatalog,
  } from '@/types';

  interface FlatQuestion {
    func: MaturityFunction;
    category: MaturityCategory;
    subcategory: MaturitySubcategory;
    requirement: MaturityRequirement;
    control: MaturityControl;
  }

  // Cada control es un ítem de checklist (cumple / no cumple) dentro de un Requisito graduado
  // por nivel 1-4 -- el badge sólo indica a qué nivel pertenece, no se autoevalúa 1-5 por
  // control (ver EvaluationService.calculateMaturity en el backend para el cálculo acumulativo).
  const LEVEL_BADGE: Record<number, string> = {
    1: 'bg-red-100 text-red-700',
    2: 'bg-orange-100 text-orange-700',
    3: 'bg-yellow-100 text-yellow-700',
    4: 'bg-emerald-100 text-emerald-700',
  };

  const route = useRoute();
  const router = useRouter();
  const store = useEvaluationsStore();
  const notification = useNotification();

  const evaluationId = route.params.id as string;
  const loading = ref(true);
  const saving = ref(false);
  const evaluation = ref<Evaluation | null>(null);
  const catalogFunctions = ref<MaturityFunction[]>([]);
  const expandedFunctions = ref<Set<string>>(new Set());
  const currentIndex = ref(0);
  const flaggedControls = ref<Set<string>>(new Set());
  const showObservations = ref(false);

  const responseMap = reactive<
    Record<string, { compliant: boolean; answered: boolean; observations: string }>
  >({});

  // Un Requisito (y sus Controles) puede pertenecer a varias Subcategorias -- aparece una vez por
  // cada una en el arbol del catalogo, pero cada Control se pregunta una sola vez en la secuencia
  // (se responde una unica vez sin importar cuantas Subcategorias comparten ese Requisito).
  const flatQuestions = computed<FlatQuestion[]>(() => {
    const out: FlatQuestion[] = [];
    const seenControlIds = new Set<string>();
    for (const func of catalogFunctions.value) {
      for (const category of func.categories) {
        for (const subcategory of category.subcategories) {
          for (const requirement of subcategory.requirements) {
            for (const control of requirement.controls) {
              if (seenControlIds.has(control.id)) continue;
              seenControlIds.add(control.id);
              out.push({ func, category, subcategory, requirement, control });
            }
          }
        }
      }
    }
    return out;
  });

  const currentQuestion = computed(
    () => flatQuestions.value[currentIndex.value] ?? flatQuestions.value[0],
  );

  // Modelo acumulativo (ver EvaluationService.calculateMaturity en el backend): un Requisito no
  // puede alcanzar ningún nivel superior al primero que tenga un control marcado "No cumple". Se
  // calcula por Requisito, no por Subcategoría -- un mismo Requisito puede pertenecer a varias.
  const requirementFailedLevel = computed(() => {
    const map = new Map<string, number>();
    for (const q of flatQuestions.value) {
      const r = responseMap[q.control.id];
      if (r?.answered && r.compliant === false) {
        const current = map.get(q.requirement.id);
        if (current === undefined || q.control.targetLevel < current) {
          map.set(q.requirement.id, q.control.targetLevel);
        }
      }
    }
    return map;
  });

  // Los controles de nivel SUPERIOR al primero incumplido de su Requisito ya no pueden aportar
  // nada al cálculo (el backend corta ahí igual): se saltean en el cuestionario y quedan en
  // "No cumple" automáticamente sin volver a preguntarlos. Los del MISMO nivel que el que falló sí
  // se siguen preguntando -- el nivel ya está perdido, pero completan el detalle del Requisito.
  function isControlAutoFailed(requirementId: string, targetLevel: number): boolean {
    const failedLevel = requirementFailedLevel.value.get(requirementId);
    return failedLevel !== undefined && targetLevel > failedLevel;
  }

  function isAutoFailed(q: FlatQuestion | undefined): boolean {
    if (!q) return false;
    return isControlAutoFailed(q.requirement.id, q.control.targetLevel);
  }

  function isEffectivelyAnswered(q: FlatQuestion): boolean {
    return !!responseMap[q.control.id]?.answered || isAutoFailed(q);
  }

  const isFirst = computed(() => {
    for (let i = currentIndex.value - 1; i >= 0; i--) {
      if (!isAutoFailed(flatQuestions.value[i])) return false;
    }
    return true;
  });
  const isLast = computed(() => {
    for (let i = currentIndex.value + 1; i < flatQuestions.value.length; i++) {
      if (!isAutoFailed(flatQuestions.value[i])) return false;
    }
    return true;
  });

  // null = todavía sin responder; true/false = Cumple / No cumple.
  const currentAnswer = computed<boolean | null>(() => {
    const r = responseMap[currentQuestion.value?.control.id];
    return r?.answered ? r.compliant : null;
  });
  const isFlagged = computed(() => flaggedControls.value.has(currentQuestion.value?.control.id));

  const currentObservations = computed({
    get: () => responseMap[currentQuestion.value?.control.id]?.observations || '',
    set: (value: string) => {
      const id = currentQuestion.value.control.id;
      if (!responseMap[id])
        responseMap[id] = { compliant: false, answered: false, observations: '' };
      responseMap[id].observations = value;
    },
  });

  const answeredCount = computed(
    () => flatQuestions.value.filter((q) => isEffectivelyAnswered(q)).length,
  );
  const totalQuestions = computed(() => flatQuestions.value.length);
  const remainingCount = computed(() => totalQuestions.value - answeredCount.value);

  const progressPct = computed(() => {
    if (totalQuestions.value === 0) return 0;
    return Math.round((answeredCount.value / totalQuestions.value) * 100);
  });

  // Avance por funcion (no solo el del plan completo de arriba): cuantas de sus preguntas ya
  // tienen respuesta, para mostrar junto al nombre de cada funcion en el navegador lateral.
  const progressByFunction = computed(() => {
    const byFunction = new Map<string, { answered: number; total: number }>();
    for (const q of flatQuestions.value) {
      const entry = byFunction.get(q.func.id) ?? { answered: 0, total: 0 };
      entry.total += 1;
      if (isEffectivelyAnswered(q)) entry.answered += 1;
      byFunction.set(q.func.id, entry);
    }
    return byFunction;
  });

  const questionsInCurrentSubcategory = computed(() =>
    flatQuestions.value.filter((q) => q.subcategory.id === currentQuestion.value?.subcategory.id),
  );
  const posInSubcategory = computed(
    () =>
      questionsInCurrentSubcategory.value.findIndex(
        (q) => q.control.id === currentQuestion.value?.control.id,
      ) + 1,
  );
  const totalInSubcategory = computed(() => questionsInCurrentSubcategory.value.length);

  function isCurrentSubcategory(subcategoryId: string): boolean {
    return currentQuestion.value?.subcategory.id === subcategoryId;
  }

  function isSubcategoryComplete(subcategory: MaturitySubcategory): boolean {
    return subcategory.requirements.every((req) =>
      req.controls.every(
        (c) => !!responseMap[c.id]?.answered || isControlAutoFailed(req.id, c.targetLevel),
      ),
    );
  }

  function dotClass(subcategory: MaturitySubcategory): string {
    if (isCurrentSubcategory(subcategory.id)) return 'bg-blue-600';
    if (isSubcategoryComplete(subcategory)) return 'bg-emerald-500';
    return 'bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600';
  }

  function functionProgressLabel(functionId: string): string {
    const entry = progressByFunction.value.get(functionId);
    if (!entry) return '';
    return `${entry.answered}/${entry.total}`;
  }

  function toggleFunction(id: string) {
    const next = new Set(expandedFunctions.value);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    expandedFunctions.value = next;
  }

  function findSubcategoryById(subcategoryId: string): MaturitySubcategory | undefined {
    for (const func of catalogFunctions.value) {
      for (const category of func.categories) {
        const found = category.subcategories.find((s) => s.id === subcategoryId);
        if (found) return found;
      }
    }
    return undefined;
  }

  function goToSubcategory(subcategoryId: string) {
    // Si esta Subcategoria comparte TODOS sus controles con otra (mismo Requisito enlazado a
    // varias Subcategorias), esos controles ya aparecen en flatQuestions con la etiqueta de la
    // OTRA Subcategoria (la primera que los recorrio) -- buscar por control.id en vez de por
    // subcategory.id evita que el click quede "muerto" en ese caso.
    const subcategory = findSubcategoryById(subcategoryId);
    if (!subcategory) return;
    const controlIds = new Set(
      subcategory.requirements.flatMap((req) => req.controls.map((c) => c.id)),
    );
    // Preferir un control que todavía se pregunte (no auto-fallado) -- si TODOS los de esta
    // Subcategoría ya quedaron capados por el modelo acumulativo, cae al primero igual.
    const candidates = flatQuestions.value.filter((q) => controlIds.has(q.control.id));
    const idx = flatQuestions.value.indexOf(
      candidates.find((q) => !isAutoFailed(q)) ?? candidates[0],
    );
    if (idx >= 0) {
      currentIndex.value = idx;
      showObservations.value = false;
    }
  }

  function selectCompliant(value: boolean) {
    const id = currentQuestion.value.control.id;
    if (!responseMap[id]) responseMap[id] = { compliant: false, answered: false, observations: '' };
    responseMap[id].compliant = value;
    responseMap[id].answered = true;
  }

  function toggleFlag() {
    const next = new Set(flaggedControls.value);
    const id = currentQuestion.value.control.id;
    if (next.has(id)) next.delete(id);
    else next.add(id);
    flaggedControls.value = next;
  }

  function goPrev() {
    let prev = currentIndex.value - 1;
    while (prev >= 0 && isAutoFailed(flatQuestions.value[prev])) {
      prev--;
    }
    if (prev >= 0) {
      currentIndex.value = prev;
      showObservations.value = false;
    }
  }

  function goNext() {
    let next = currentIndex.value + 1;
    while (next < flatQuestions.value.length && isAutoFailed(flatQuestions.value[next])) {
      next++;
    }
    if (next < flatQuestions.value.length) {
      currentIndex.value = next;
      showObservations.value = false;
    }
  }

  async function persistResponses(): Promise<boolean> {
    saving.value = true;
    try {
      // Los controles auto-fallados (ver isAutoFailed) se mandan como "No cumple" SIEMPRE que la
      // condición se cumpla ahora mismo -- pisa cualquier respuesta manual que hubiera quedado
      // guardada antes de que su Requisito cayera bajo el modelo acumulativo (no debería poder
      // pasar en el flujo normal, que va de menor a mayor nivel, pero así no queda una respuesta
      // vieja "sobreviviendo" invisible, sin forma de llegar a corregirla desde el asistente). Si
      // más tarde se corrige el control que los capaba, dejan de mandarse acá y su respuesta
      // guardada queda tal cual hasta que se responda de nuevo.
      const entries = new Map<string, { compliant: boolean; observations: string }>();
      for (const [controlId, data] of Object.entries(responseMap)) {
        if (data.answered) entries.set(controlId, data);
      }
      for (const q of flatQuestions.value) {
        if (isAutoFailed(q)) {
          entries.set(q.control.id, {
            compliant: false,
            observations: responseMap[q.control.id]?.observations || '',
          });
        }
      }
      for (const [controlId, data] of entries) {
        await store.saveResponse(evaluationId, {
          controlId,
          compliant: data.compliant,
          observations: data.observations,
        });
      }
      return true;
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al guardar las respuestas'));
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function saveProgress() {
    const ok = await persistResponses();
    if (ok) notification.success('Progreso guardado correctamente');
  }

  async function finish() {
    const ok = await persistResponses();
    if (!ok) return;
    try {
      await store.calculateMaturity(evaluationId);
      notification.success('Respuestas guardadas y madurez calculada correctamente');
    } catch {
      // El calculo de madurez es best-effort acá: las respuestas ya se guardaron, así que no
      // bloqueamos la salida por esto -- se puede recalcular luego desde el detalle.
    }
    router.push(`/evaluations/${evaluationId}`);
  }

  onMounted(async () => {
    try {
      const [ev, responsesData] = await Promise.all([
        store.fetchEvaluation(evaluationId),
        store.fetchResponses(evaluationId),
      ]);
      evaluation.value = ev;

      responsesData.forEach((r) => {
        responseMap[r.controlId] = {
          compliant: r.compliant,
          answered: true,
          observations: r.observations || '',
        };
      });

      // Antes esto estaba hardcodeado a la versión "5.0" sin importar qué versión tuviera la
      // evaluación en sí. Además, si la evaluación tiene un perfil comunitario asignado, el
      // árbol viene podado a solo esos controles -- el resto del catálogo no se responde.
      const { data: catalogData } = await catalogService.getByVersion(
        ev.catalogVersion || '5.0',
        ev.communityProfileId,
      );
      catalogFunctions.value = (catalogData as MaturityCatalog).functions || [];
      if (catalogFunctions.value.length > 0) {
        expandedFunctions.value.add(catalogFunctions.value[0].id);
      }

      // Retomar en la primera pregunta sin responder, si existe alguna ya respondida.
      const firstUnanswered = flatQuestions.value.findIndex(
        (q) => !responseMap[q.control.id]?.answered,
      );
      if (firstUnanswered > 0) {
        currentIndex.value = firstUnanswered;
        const func = flatQuestions.value[firstUnanswered].func;
        expandedFunctions.value.add(func.id);
      }
    } catch (err) {
      console.error(err);
    } finally {
      loading.value = false;
    }
  });
</script>
