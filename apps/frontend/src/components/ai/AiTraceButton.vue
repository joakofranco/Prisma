<template>
  <!-- «Cómo razonó la IA» (modo investigación, C8): solo para PRISMA_ADMIN. Muestra la traza que
       se guardó cuando la organización tenía el modo encendido; si no hay (estaba apagado), lo
       dice. En evidencia solo hay ids y puntajes, nunca texto de archivos (D15). -->
  <button
    v-if="authStore.isAdmin"
    type="button"
    class="text-xs font-medium px-2 py-1 rounded border text-violet-700 border-violet-300 hover:bg-violet-50 dark:text-violet-300 dark:border-violet-700 dark:hover:bg-violet-950"
    data-testid="ai-trace-button"
    @click="open"
  >
    Cómo razonó la IA
  </button>

  <BaseModal v-model="show" title="Cómo razonó la IA" size="lg">
    <div v-if="loading" class="py-6 text-center text-sm text-slate-500" data-testid="trace-loading">
      Cargando…
    </div>
    <p
      v-else-if="error"
      class="text-sm text-slate-600 dark:text-slate-300"
      data-testid="trace-error"
    >
      {{ error }}
    </p>
    <div v-else-if="trace" class="space-y-4 text-sm" data-testid="trace-detail">
      <dl class="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
        <dt class="text-slate-500">Tipo</dt>
        <dd data-testid="trace-kind">{{ kindLabel }}</dd>
        <dt class="text-slate-500">Semilla</dt>
        <dd data-testid="trace-seed">{{ trace.seed ?? '—' }}</dd>
        <template v-for="[key, value] in configEntries" :key="key">
          <dt class="text-slate-500">{{ key }}</dt>
          <dd class="break-all">{{ value }}</dd>
        </template>
      </dl>

      <section v-if="trace.prompt">
        <h4 class="font-semibold mb-1">Consulta enviada al modelo</h4>
        <pre
          class="whitespace-pre-wrap text-xs bg-slate-50 dark:bg-slate-900 rounded p-2 max-h-48 overflow-auto"
          data-testid="trace-prompt"
          >{{ trace.prompt }}</pre>
      </section>
      <section v-if="trace.thinking">
        <h4 class="font-semibold mb-1">Razonamiento</h4>
        <pre
          class="whitespace-pre-wrap text-xs bg-slate-50 dark:bg-slate-900 rounded p-2 max-h-48 overflow-auto"
          data-testid="trace-thinking"
          >{{ trace.thinking }}</pre>
      </section>
      <section v-if="trace.response">
        <h4 class="font-semibold mb-1">Respuesta</h4>
        <pre
          class="whitespace-pre-wrap text-xs bg-slate-50 dark:bg-slate-900 rounded p-2 max-h-48 overflow-auto"
          data-testid="trace-response"
          >{{ trace.response }}</pre>
      </section>
      <section v-if="trace.retrieval.length > 0">
        <h4 class="font-semibold mb-1">Fragmentos recuperados (ids y puntajes)</h4>
        <ul class="text-xs space-y-0.5" data-testid="trace-retrieval">
          <li v-for="(item, i) in trace.retrieval" :key="i" class="break-all">
            {{ describe(item) }}
          </li>
        </ul>
      </section>
    </div>
  </BaseModal>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import { useAuthStore } from '@/stores/auth';
  import type { AiTrace } from '@/types';

  const props = defineProps<{ load: () => Promise<{ data: AiTrace }> }>();

  const authStore = useAuthStore();
  const show = ref(false);
  const loading = ref(false);
  const trace = ref<AiTrace | null>(null);
  const error = ref('');

  const KIND_LABELS: Record<string, string> = {
    GUIDE: 'Guía de mejora',
    CITATIONS: 'Búsqueda de citas',
    CLASSIFY: 'Preclasificación de evidencia',
  };
  const kindLabel = computed(() =>
    trace.value ? (KIND_LABELS[trace.value.kind] ?? trace.value.kind) : '',
  );

  const configEntries = computed(() =>
    Object.entries(trace.value?.config ?? {}).map(([k, v]) => [k, String(v ?? '—')] as const),
  );

  function describe(item: Record<string, unknown>) {
    return Object.entries(item)
      .filter(([, v]) => v !== null && v !== undefined)
      .map(([k, v]) => `${k}: ${v}`)
      .join(' · ');
  }

  async function open() {
    show.value = true;
    loading.value = true;
    error.value = '';
    trace.value = null;
    try {
      const { data } = await props.load();
      trace.value = data;
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      error.value =
        status === 404
          ? 'No hay traza para este elemento: el modo investigación estaba apagado cuando se generó.'
          : 'No se pudo cargar la traza.';
    } finally {
      loading.value = false;
    }
  }
</script>
