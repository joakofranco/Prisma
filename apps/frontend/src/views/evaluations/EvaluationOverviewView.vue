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
      <div class="flex-1 min-w-0">
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white truncate">
          {{ evaluation?.name || overview?.evaluationName || 'Estructura de la evaluación' }}
        </h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          <span v-if="evaluation?.organizationName">{{ evaluation.organizationName }} · </span>
          <span v-if="overview">{{ overview.catalogLabel }}</span>
          <span v-if="overview?.communityProfileName"> · {{ overview.communityProfileName }}</span>
        </p>
      </div>
      <span
        v-if="evaluation"
        class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium badge-themed"
        :style="getStatusBadgeStyle(evaluation.status)"
      >
        {{ getStatusLabel(evaluation.status) }}
      </span>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <BaseAlert v-else-if="errorMessage" variant="danger">{{ errorMessage }}</BaseAlert>

    <div
      v-else-if="overview && rows.length === 0"
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-8 text-center"
    >
      <p class="text-slate-500 dark:text-slate-400">
        Este catálogo{{ overview.communityProfileName ? ' / perfil comunitario' : '' }} no tiene
        subcategorías con controles.
      </p>
    </div>

    <template v-else-if="overview">
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div
          class="rounded-xl p-4 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700"
        >
          <p class="text-sm font-medium text-slate-500 dark:text-slate-400">Subcategorías</p>
          <p class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            {{ overview.subcategoryCount }}
          </p>
        </div>
        <div
          class="rounded-xl p-4 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700"
        >
          <p class="text-sm font-medium text-slate-500 dark:text-slate-400">Requisitos MCU</p>
          <p class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            {{ overview.requirementCount }}
          </p>
        </div>
        <div
          class="rounded-xl p-4 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700"
        >
          <p class="text-sm font-medium text-slate-500 dark:text-slate-400">Controles</p>
          <p class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
            {{ overview.controlCount }}
          </p>
        </div>
        <div
          class="rounded-xl p-4 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700"
        >
          <p class="text-sm font-medium text-slate-500 dark:text-slate-400">Por prioridad</p>
          <div class="flex flex-wrap gap-1.5 mt-2">
            <span :class="['px-2 py-0.5 rounded-full text-xs font-semibold', PRIORITY_BADGE.ALTA]">
              Alta {{ overview.priorityCounts.alta }}
            </span>
            <span :class="['px-2 py-0.5 rounded-full text-xs font-semibold', PRIORITY_BADGE.MEDIA]">
              Media {{ overview.priorityCounts.media }}
            </span>
            <span :class="['px-2 py-0.5 rounded-full text-xs font-semibold', PRIORITY_BADGE.BAJA]">
              Baja {{ overview.priorityCounts.baja }}
            </span>
            <span
              v-if="overview.priorityCounts.sinPrioridad > 0"
              :class="['px-2 py-0.5 rounded-full text-xs font-semibold', PRIORITY_BADGE.NONE]"
            >
              Sin prioridad {{ overview.priorityCounts.sinPrioridad }}
            </span>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-[250px_1fr] gap-6 items-start">
        <aside
          aria-label="Estructura del catálogo"
          class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 lg:sticky lg:top-20 max-h-[calc(100vh-6rem)] overflow-y-auto"
        >
          <h2
            class="text-xs font-semibold text-slate-500 uppercase tracking-wide mb-3 dark:text-slate-400"
          >
            Estructura {{ overview.catalogLabel }}
          </h2>
          <nav class="space-y-3">
            <div v-for="fn in overview.functions" :key="fn.id">
              <p class="text-sm font-semibold text-slate-900 dark:text-white">{{ fn.name }}</p>
              <div
                v-for="category in fn.categories"
                :key="category.id"
                class="ml-2 pl-3 mt-1 border-l border-slate-200 dark:border-slate-700"
              >
                <p class="text-xs text-slate-500 dark:text-slate-400">
                  {{ category.code }} · {{ category.name }}
                </p>
                <button
                  v-for="sub in category.subcategories"
                  :key="sub.id"
                  type="button"
                  :class="[
                    'w-full text-left px-2 py-1.5 mt-0.5 rounded-md text-xs font-mono transition-colors',
                    activeId === sub.id
                      ? 'bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300 font-semibold'
                      : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700',
                  ]"
                  @click="goTo(sub.id)"
                >
                  {{ sub.code }}
                </button>
              </div>
            </div>
          </nav>
        </aside>

        <section class="space-y-4 min-w-0">
          <div class="flex flex-wrap items-center gap-2">
            <div class="flex-1 min-w-[220px]">
              <label for="overview-search" class="sr-only">Buscar en la estructura</label>
              <input
                id="overview-search"
                v-model="query"
                type="search"
                placeholder="Buscar por ID, nombre, requisito o control"
                class="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-900 dark:text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div class="flex flex-wrap gap-1.5" role="group" aria-label="Filtrar por prioridad">
              <button
                v-for="opt in PRIORITY_FILTERS"
                :key="opt.value"
                type="button"
                :aria-pressed="priorityFilter === opt.value"
                :class="[
                  'px-3 py-1.5 rounded-full text-sm border transition-colors',
                  priorityFilter === opt.value
                    ? 'bg-slate-900 text-white border-slate-900 dark:bg-white dark:text-slate-900 dark:border-white'
                    : 'bg-white text-slate-700 border-slate-300 hover:bg-slate-50 dark:bg-slate-800 dark:text-slate-200 dark:border-slate-600 dark:hover:bg-slate-700',
                ]"
                @click="priorityFilter = opt.value"
              >
                {{ opt.label }}
              </button>
            </div>
            <BaseButton variant="secondary" @click="expandAll">Expandir todo</BaseButton>
            <BaseButton variant="secondary" @click="collapseAll">Contraer todo</BaseButton>
          </div>

          <p class="text-sm text-slate-500 dark:text-slate-400" aria-live="polite">
            {{ filteredRows.length }} de {{ rows.length }} subcategorías
          </p>

          <div
            v-if="filteredRows.length === 0"
            class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-8 text-center"
          >
            <p class="text-slate-500 dark:text-slate-400">
              Ninguna subcategoría coincide con la búsqueda o el filtro.
            </p>
          </div>

          <div v-for="group in groups" :key="group.id" class="space-y-3">
            <h2
              class="text-xs font-semibold text-slate-500 uppercase tracking-wide dark:text-slate-400"
            >
              Función: {{ group.name }}
            </h2>

            <article
              v-for="row in group.rows"
              :id="`sub-${row.subcategory.id}`"
              :key="row.subcategory.id"
              class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden scroll-mt-24"
            >
              <h3>
                <button
                  type="button"
                  class="w-full flex items-center gap-3 px-5 py-4 text-left hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
                  :aria-expanded="isOpen(row.subcategory.id)"
                  :aria-controls="`panel-${row.subcategory.id}`"
                  @click="toggle(row.subcategory.id)"
                >
                  <span
                    class="font-mono text-sm font-bold text-blue-700 dark:text-blue-300 w-24 shrink-0"
                  >
                    {{ row.subcategory.code }}
                  </span>
                  <span class="flex-1 min-w-0">
                    <span class="block font-semibold text-slate-900 dark:text-white">
                      {{ row.subcategory.name }}
                    </span>
                    <span class="block text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                      {{ row.functionName }} › {{ row.categoryName }}
                    </span>
                  </span>
                  <span class="hidden md:flex gap-1 shrink-0" aria-hidden="true">
                    <span
                      v-for="level in OVERVIEW_LEVELS"
                      :key="level"
                      :class="[
                        'px-1.5 py-0.5 rounded text-[11px] font-semibold',
                        levelCounts(row)[level] > 0 ? LEVEL_BADGE[level] : LEVEL_BADGE_EMPTY,
                      ]"
                    >
                      N{{ level }} · {{ levelCounts(row)[level] }}
                    </span>
                  </span>
                  <span
                    :class="[
                      'px-2.5 py-0.5 rounded-full text-xs font-semibold whitespace-nowrap shrink-0',
                      PRIORITY_BADGE[row.subcategory.priority ?? 'NONE'],
                    ]"
                  >
                    {{ priorityLabel(row.subcategory.priority) }}
                  </span>
                  <ChevronDownIcon
                    :class="[
                      'w-5 h-5 text-slate-400 shrink-0 transition-transform',
                      isOpen(row.subcategory.id) ? 'rotate-180' : '',
                    ]"
                  />
                </button>
              </h3>

              <div
                v-if="isOpen(row.subcategory.id)"
                :id="`panel-${row.subcategory.id}`"
                class="border-t border-slate-200 dark:border-slate-700 px-5 py-4 space-y-3"
              >
                <p
                  v-if="row.subcategory.description"
                  class="text-sm text-slate-700 dark:text-slate-300"
                >
                  {{ row.subcategory.description }}
                </p>

                <div
                  class="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden"
                >
                  <button
                    type="button"
                    class="w-full flex items-center gap-3 px-4 py-2.5 text-sm font-semibold text-left bg-slate-50 dark:bg-slate-900/40 text-slate-800 dark:text-slate-100"
                    :aria-expanded="isSectionOpen(row.subcategory.id, 'req')"
                    @click="toggleSection(row.subcategory.id, 'req')"
                  >
                    <span class="flex-1">Requisitos MCU relacionados</span>
                    <span
                      class="text-xs font-normal px-2 py-0.5 rounded-full bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-200"
                    >
                      {{ row.subcategory.requirements.length }}
                    </span>
                    <ChevronDownIcon
                      :class="[
                        'w-4 h-4 text-slate-400 transition-transform',
                        isSectionOpen(row.subcategory.id, 'req') ? 'rotate-180' : '',
                      ]"
                    />
                  </button>
                  <ul
                    v-if="isSectionOpen(row.subcategory.id, 'req')"
                    class="divide-y divide-slate-100 dark:divide-slate-700"
                  >
                    <li
                      v-for="req in row.subcategory.requirements"
                      :key="req.id"
                      class="px-4 py-2.5 text-sm text-slate-700 dark:text-slate-300 flex gap-3"
                    >
                      <span
                        class="font-mono font-semibold text-blue-700 dark:text-blue-300 w-16 shrink-0"
                      >
                        {{ req.code }}
                      </span>
                      <span>{{ req.description }}</span>
                    </li>
                  </ul>
                </div>

                <div
                  v-for="level in OVERVIEW_LEVELS"
                  :key="level"
                  class="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden"
                >
                  <button
                    type="button"
                    class="w-full flex items-center gap-3 px-4 py-2.5 text-sm font-semibold text-left bg-slate-50 dark:bg-slate-900/40 text-slate-800 dark:text-slate-100"
                    :aria-expanded="isSectionOpen(row.subcategory.id, level)"
                    @click="toggleSection(row.subcategory.id, level)"
                  >
                    <span
                      :class="[
                        'px-2 py-0.5 rounded-full text-xs font-semibold',
                        LEVEL_BADGE[level],
                      ]"
                    >
                      Nivel {{ level }}
                    </span>
                    <span class="flex-1 text-xs font-normal text-slate-500 dark:text-slate-400">
                      {{ levelCounts(row)[level] === 0 ? 'Sin controles en este nivel' : '' }}
                    </span>
                    <span
                      class="text-xs font-normal px-2 py-0.5 rounded-full bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-200"
                    >
                      {{ levelCounts(row)[level] }}
                    </span>
                    <ChevronDownIcon
                      :class="[
                        'w-4 h-4 text-slate-400 transition-transform',
                        isSectionOpen(row.subcategory.id, level) ? 'rotate-180' : '',
                      ]"
                    />
                  </button>
                  <ul
                    v-if="isSectionOpen(row.subcategory.id, level)"
                    class="divide-y divide-slate-100 dark:divide-slate-700"
                  >
                    <li
                      v-for="control in controlsByLevel(row)[level]"
                      :key="control.id"
                      class="px-4 py-2.5 text-sm text-slate-700 dark:text-slate-300 flex gap-3"
                    >
                      <span
                        class="font-mono font-semibold text-slate-900 dark:text-white w-20 shrink-0"
                      >
                        {{ control.code }}
                      </span>
                      <span>{{ control.description }}</span>
                    </li>
                    <li
                      v-if="levelCounts(row)[level] === 0"
                      class="px-4 py-2.5 text-sm text-slate-500 dark:text-slate-400"
                    >
                      Esta subcategoría no tiene controles de nivel {{ level }} en el perfil.
                    </li>
                  </ul>
                </div>
              </div>
            </article>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted, nextTick } from 'vue';
  import { useRoute } from 'vue-router';
  import { evaluationsService } from '@/services/resources';
  import { getErrorMessage, getStatusBadgeStyle, getStatusLabel } from '@/utils/helpers';
  import {
    OVERVIEW_LEVELS,
    countControlsByLevel,
    filterOverviewRows,
    flattenOverview,
    groupControlsByLevel,
    groupRowsByFunction,
    priorityLabel,
    type OverviewRow,
    type PriorityFilter,
  } from '@/utils/evaluationOverview';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import { ArrowLeftIcon, ChevronDownIcon } from '@heroicons/vue/24/outline';
  import type { Evaluation, EvaluationOverview } from '@/types';

  const LEVEL_BADGE: Record<number, string> = {
    1: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300',
    2: 'bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300',
    3: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-300',
    4: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300',
  };
  const LEVEL_BADGE_EMPTY = 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-200';

  const PRIORITY_BADGE: Record<string, string> = {
    ALTA: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300',
    MEDIA: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300',
    BAJA: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300',
    NONE: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
  };

  const PRIORITY_FILTERS: { value: PriorityFilter; label: string }[] = [
    { value: 'ALL', label: 'Todas' },
    { value: 'ALTA', label: 'Alta' },
    { value: 'MEDIA', label: 'Media' },
    { value: 'BAJA', label: 'Baja' },
  ];

  const route = useRoute();
  const evaluationId = route.params.id as string;

  const loading = ref(true);
  const errorMessage = ref('');
  const overview = ref<EvaluationOverview | null>(null);
  const evaluation = ref<Evaluation | null>(null);
  const query = ref('');
  const priorityFilter = ref<PriorityFilter>('ALL');
  const openSubs = ref<Set<string>>(new Set());
  const openSections = ref<Set<string>>(new Set());
  const activeId = ref('');

  const rows = computed(() => (overview.value ? flattenOverview(overview.value) : []));
  const filteredRows = computed(() =>
    filterOverviewRows(rows.value, query.value, priorityFilter.value),
  );
  const groups = computed(() => groupRowsByFunction(filteredRows.value));

  const levelCounts = (row: OverviewRow) => countControlsByLevel(row.subcategory.controls);
  const controlsByLevel = (row: OverviewRow) => groupControlsByLevel(row.subcategory.controls);

  const isOpen = (id: string) => openSubs.value.has(id);
  const sectionKey = (id: string, section: string | number) => `${id}:${section}`;
  const isSectionOpen = (id: string, section: string | number) =>
    openSections.value.has(sectionKey(id, section));

  function toggle(id: string) {
    const next = new Set(openSubs.value);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
      const sections = new Set(openSections.value);
      sections.add(sectionKey(id, 'req'));
      sections.add(sectionKey(id, 1));
      openSections.value = sections;
    }
    openSubs.value = next;
  }

  function toggleSection(id: string, section: string | number) {
    const next = new Set(openSections.value);
    const key = sectionKey(id, section);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    openSections.value = next;
  }

  function expandAll() {
    const subs = new Set<string>();
    const sections = new Set<string>();
    for (const row of filteredRows.value) {
      const id = row.subcategory.id;
      subs.add(id);
      sections.add(sectionKey(id, 'req'));
      for (const level of OVERVIEW_LEVELS) sections.add(sectionKey(id, level));
    }
    openSubs.value = subs;
    openSections.value = sections;
  }

  function collapseAll() {
    openSubs.value = new Set();
    openSections.value = new Set();
  }

  async function goTo(id: string) {
    activeId.value = id;
    if (!openSubs.value.has(id)) toggle(id);
    await nextTick();
    document.getElementById(`sub-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  onMounted(async () => {
    try {
      const [overviewRes, evaluationRes] = await Promise.all([
        evaluationsService.getOverview(evaluationId),
        evaluationsService.getById(evaluationId),
      ]);
      overview.value = overviewRes.data;
      evaluation.value = evaluationRes.data;
      const first = rows.value[0]?.subcategory.id;
      if (first) {
        activeId.value = first;
        toggle(first);
      }
    } catch (err) {
      errorMessage.value = getErrorMessage(err, 'No se pudo cargar la estructura de la evaluación');
    } finally {
      loading.value = false;
    }
  });
</script>
