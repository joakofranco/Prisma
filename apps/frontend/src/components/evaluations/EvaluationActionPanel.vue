<template>
  <div data-testid="action-panel">
    <!-- Qué sigue: quién tiene el turno, qué se le pide y los botones principales. -->
    <div
      class="px-6 py-5 flex flex-col lg:flex-row lg:items-center gap-5"
      :class="PANEL_BG[plan.tone]"
      data-testid="action-panel-next"
    >
      <div class="flex-1 min-w-0">
        <p
          class="text-[11px] font-extrabold uppercase tracking-wide"
          :class="PANEL_EYEBROW[plan.tone]"
          data-testid="action-panel-eyebrow"
        >
          {{ plan.eyebrow }}
        </p>
        <h4
          class="mt-1 text-lg font-bold text-slate-900 dark:text-white"
          data-testid="action-panel-title"
        >
          {{ plan.title }}
        </h4>
        <p class="mt-1 text-sm text-slate-600 dark:text-slate-300 max-w-xl">{{ plan.text }}</p>
        <div v-if="plan.showProgress && progress" class="mt-3 w-60" data-testid="action-progress">
          <p class="text-xs text-slate-600 dark:text-slate-300 mb-1">
            Progreso <strong>{{ progress.percent }}%</strong>
            <span v-if="progress.total > 0">
              · {{ progress.answered }} de {{ progress.total }} controles</span
            >
          </p>
          <div class="h-2 rounded-full bg-slate-200 dark:bg-slate-700" aria-hidden="true">
            <div
              class="h-2 rounded-full bg-emerald-500"
              :style="{ width: `${progress.percent}%` }"
            />
          </div>
        </div>
      </div>

      <div class="flex flex-wrap items-center gap-3 lg:justify-end">
        <button
          v-for="action in plan.primary"
          :key="action.key"
          type="button"
          :disabled="busy"
          class="inline-flex items-center gap-3 rounded-xl px-6 py-3 text-left text-white shadow-lg bg-linear-to-b transition hover:brightness-110 focus:outline-none focus:ring-2 focus:ring-offset-2 disabled:opacity-60 disabled:cursor-not-allowed"
          :class="PRIMARY_TONE[action.tone]"
          :data-testid="`action-${action.key}`"
          @click="onPrimary(action)"
        >
          <component :is="ICONS[action.icon]" class="w-6 h-6 shrink-0" aria-hidden="true" />
          <span class="block leading-tight">
            <span class="block text-base font-bold">{{ action.label }}</span>
            <span class="block text-xs font-medium opacity-90">{{ action.hint }}</span>
          </span>
        </button>

        <div
          v-if="plan.waitingLabel"
          class="inline-flex items-center gap-2 rounded-xl border border-dashed border-slate-400 bg-white/70 px-5 py-3 text-sm font-semibold text-slate-600 dark:border-slate-500 dark:bg-slate-800/70 dark:text-slate-300"
          data-testid="action-waiting"
        >
          <span
            v-if="plan.turn === 'waiting' || plan.turn === 'info'"
            class="h-2.5 w-2.5 rounded-full bg-amber-500 ring-4 ring-amber-500/20"
            aria-hidden="true"
          />
          <LockClosedIcon v-else class="w-4 h-4" aria-hidden="true" />
          {{ plan.waitingLabel }}
        </div>
      </div>
    </div>

    <!-- Documentación y revisión: Evidencias y observaciones del auditor, a la vista. -->
    <div
      v-if="plan.tools.length > 0"
      class="px-6 py-4 border-t border-slate-200 dark:border-slate-700"
      data-testid="action-panel-tools"
    >
      <p
        class="mb-2 text-[11px] font-extrabold uppercase tracking-wide text-slate-500 dark:text-slate-400"
      >
        Documentación y revisión
      </p>
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <button
          v-for="tool in plan.tools"
          :key="tool.key"
          type="button"
          :disabled="tool.disabled"
          class="flex items-center gap-3 rounded-xl border-2 px-4 py-3 text-left transition disabled:opacity-50 disabled:cursor-not-allowed"
          :class="toolClass(tool)"
          :data-testid="`tool-${tool.key}`"
          @click="onTool(tool)"
        >
          <component :is="ICONS[tool.icon]" class="w-7 h-7 shrink-0" aria-hidden="true" />
          <span class="flex-1 min-w-0">
            <span class="block text-sm font-bold text-slate-900 dark:text-white">{{
              tool.label
            }}</span>
            <span class="block text-xs text-slate-600 dark:text-slate-300">{{ tool.hint }}</span>
          </span>
          <span
            v-if="tool.badge"
            class="shrink-0 rounded-full px-2.5 py-0.5 text-xs font-extrabold text-white"
            :class="tool.key === 'evidence' ? 'bg-blue-600' : 'bg-orange-600'"
            :data-testid="`tool-${tool.key}-badge`"
          >
            {{ tool.badge }}
          </span>
          <ChevronRightIcon class="w-5 h-5 shrink-0 text-slate-400" aria-hidden="true" />
        </button>
      </div>
    </div>

    <!-- Otras acciones: las que se usan menos. -->
    <div
      class="px-6 py-3 flex flex-wrap items-center gap-2 border-t border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-900"
      data-testid="action-panel-secondary"
    >
      <span class="text-xs text-slate-500 dark:text-slate-400">Otras acciones:</span>
      <button
        v-for="item in plan.secondary"
        :key="item.key"
        type="button"
        :disabled="item.disabled || (busy && item.kind === 'status')"
        class="inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold transition disabled:opacity-40 disabled:cursor-not-allowed"
        :class="
          item.kind === 'delete'
            ? 'border-red-200 text-red-700 hover:bg-red-50 dark:border-red-900 dark:text-red-400 dark:hover:bg-red-950'
            : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-100 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700'
        "
        :data-testid="`secondary-${item.key}`"
        @click="onSecondary(item)"
      >
        <component :is="ICONS[item.icon]" class="w-4 h-4" aria-hidden="true" />
        {{ item.label }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useRouter } from 'vue-router';
  import {
    ArchiveBoxIcon,
    ArrowDownTrayIcon,
    ArrowPathIcon,
    ArrowTrendingUpIcon,
    ArrowUturnLeftIcon,
    ChatBubbleLeftEllipsisIcon,
    CheckCircleIcon,
    ChevronRightIcon,
    LockClosedIcon,
    PaperAirplaneIcon,
    PaperClipIcon,
    PencilSquareIcon,
    PlayIcon,
    RectangleStackIcon,
    ShieldCheckIcon,
    TrashIcon,
  } from '@heroicons/vue/24/outline';
  import type {
    ActionIcon,
    ActionPlan,
    ActionTone,
    PanelTone,
    PrimaryAction,
    SecondaryAction,
    ToolTile,
  } from '@/utils/evaluationActionPlan';
  import type { EvaluationStatus } from '@/types';

  // Panel «Qué sigue» del detalle de una evaluación. No decide nada: dibuja el plan que arma
  // buildActionPlan() y avisa a la pantalla cuando hay que cambiar el estado o eliminar.
  defineProps<{
    plan: ActionPlan;
    progress?: { percent: number; answered: number; total: number } | null;
    /** Hay un cambio de estado en curso: se bloquean los botones que lo disparan. */
    busy?: boolean;
  }>();

  const emit = defineEmits<{
    (e: 'status', status: EvaluationStatus): void;
    (e: 'delete'): void;
  }>();

  const router = useRouter();

  const ICONS: Record<ActionIcon, unknown> = {
    play: PlayIcon,
    pencil: PencilSquareIcon,
    send: PaperAirplaneIcon,
    shield: ShieldCheckIcon,
    check: CheckCircleIcon,
    undo: ArrowUturnLeftIcon,
    refresh: ArrowPathIcon,
    trend: ArrowTrendingUpIcon,
    download: ArrowDownTrayIcon,
    structure: RectangleStackIcon,
    clip: PaperClipIcon,
    chat: ChatBubbleLeftEllipsisIcon,
    archive: ArchiveBoxIcon,
    trash: TrashIcon,
  };

  const PANEL_BG: Record<PanelTone, string> = {
    blue: 'bg-blue-50 dark:bg-blue-950',
    purple: 'bg-violet-50 dark:bg-violet-950',
    amber: 'bg-amber-50 dark:bg-amber-950',
    orange: 'bg-orange-50 dark:bg-orange-950',
    green: 'bg-emerald-50 dark:bg-emerald-950',
    slate: 'bg-slate-100 dark:bg-slate-900',
  };
  const PANEL_EYEBROW: Record<PanelTone, string> = {
    blue: 'text-blue-700 dark:text-blue-300',
    purple: 'text-violet-700 dark:text-violet-300',
    amber: 'text-amber-800 dark:text-amber-300',
    orange: 'text-orange-700 dark:text-orange-300',
    green: 'text-emerald-700 dark:text-emerald-300',
    slate: 'text-slate-600 dark:text-slate-400',
  };
  const PRIMARY_TONE: Record<ActionTone, string> = {
    blue: 'from-blue-500 to-blue-700 shadow-blue-600/40 focus:ring-blue-500',
    green: 'from-emerald-500 to-emerald-700 shadow-emerald-600/40 focus:ring-emerald-500',
    red: 'from-red-500 to-red-700 shadow-red-600/40 focus:ring-red-500',
    purple: 'from-violet-500 to-violet-700 shadow-violet-600/40 focus:ring-violet-500',
    amber: 'from-amber-600 to-amber-700 shadow-amber-600/40 focus:ring-amber-500',
  };

  function toolClass(tool: ToolTile): string {
    const evidence = tool.key === 'evidence';
    const base = evidence
      ? 'bg-blue-50 dark:bg-blue-950 border-blue-200 dark:border-blue-800 hover:border-blue-400'
      : 'bg-orange-50 dark:bg-orange-950 border-orange-200 dark:border-orange-800 hover:border-orange-400';
    const highlight = evidence
      ? 'border-blue-600 dark:border-blue-500 shadow-md'
      : 'border-orange-600 dark:border-orange-500 shadow-md';
    return tool.highlight ? `${base} ${highlight}` : base;
  }

  function onPrimary(action: PrimaryAction) {
    if (action.kind === 'status') emit('status', action.status);
    else router.push(action.to);
  }

  function onSecondary(item: SecondaryAction) {
    if (item.kind === 'status' && item.status) emit('status', item.status);
    else if (item.kind === 'delete') emit('delete');
    else if (item.to) router.push(item.to);
  }

  function onTool(tool: ToolTile) {
    if (tool.kind === 'route' && tool.to) {
      router.push(tool.to);
    } else {
      document
        .getElementById('auditor-observations')
        ?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    }
  }
</script>
