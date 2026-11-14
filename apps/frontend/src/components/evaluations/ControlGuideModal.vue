<template>
  <BaseModal
    :model-value="modelValue"
    :title="`Guía del control ${code}`"
    size="md"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div
      class="rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 p-4"
    >
      <div class="flex flex-wrap items-center gap-2">
        <span
          class="text-[11px] font-semibold uppercase tracking-wide text-blue-600 dark:text-blue-400"
        >
          Control a evaluar
        </span>
        <span
          class="rounded-full bg-blue-100 px-2 py-0.5 text-xs font-semibold text-blue-700 dark:bg-blue-900 dark:text-blue-300"
        >
          {{ code }}
        </span>
      </div>
      <p
        class="mt-2 text-sm font-semibold text-slate-900 dark:text-white"
        data-testid="guide-control-text"
      >
        {{ description }}
      </p>
    </div>

    <div class="mt-4 space-y-3">
      <section
        v-for="section in sections"
        :key="section.key"
        :class="['rounded-xl border p-4', section.tone.box]"
        :data-testid="`guide-${section.key}`"
      >
        <div class="flex items-center gap-3">
          <span
            :class="[
              'flex h-8 w-8 shrink-0 items-center justify-center rounded-full',
              section.tone.icon,
            ]"
          >
            <component :is="section.icon" class="h-4 w-4" />
          </span>
          <div>
            <p :class="['text-[11px] font-semibold uppercase tracking-wide', section.tone.eyebrow]">
              {{ section.eyebrow }}
            </p>
            <h4 class="text-sm font-bold text-slate-900 dark:text-white">{{ section.title }}</h4>
          </div>
        </div>
        <p
          class="mt-3 text-sm leading-relaxed text-slate-700 dark:text-slate-200 whitespace-pre-line"
        >
          {{ section.text }}
        </p>
      </section>
    </div>

    <p
      class="mt-4 flex gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-500 dark:border-slate-700 dark:bg-slate-900/40 dark:text-slate-400"
    >
      <InformationCircleIcon class="mt-0.5 h-4 w-4 shrink-0" />
      <span>
        Texto de apoyo para evaluador y evaluado. El ejemplo es ilustrativo, no la única forma
        válida de cumplir el control, y la lista de evidencias no es exhaustiva. No sustituye el
        texto oficial de Agesic.
      </span>
    </p>

    <template #footer>
      <BaseButton variant="secondary" data-testid="close-control-guide" @click="close">
        Cerrar
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import {
    BookOpenIcon,
    DocumentCheckIcon,
    InformationCircleIcon,
    LightBulbIcon,
  } from '@heroicons/vue/24/outline';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import type { ControlGuide } from '@/types';

  const props = defineProps<{
    modelValue: boolean;
    code: string;
    description: string;
    guide: ControlGuide;
  }>();

  const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

  const tones = {
    blue: {
      box: 'border-blue-200 bg-blue-50 dark:border-blue-900 dark:bg-blue-950/40',
      icon: 'bg-blue-100 text-blue-600 dark:bg-blue-900 dark:text-blue-300',
      eyebrow: 'text-blue-600 dark:text-blue-400',
    },
    emerald: {
      box: 'border-emerald-200 bg-emerald-50 dark:border-emerald-900 dark:bg-emerald-950/40',
      icon: 'bg-emerald-100 text-emerald-600 dark:bg-emerald-900 dark:text-emerald-300',
      eyebrow: 'text-emerald-600 dark:text-emerald-400',
    },
    amber: {
      box: 'border-amber-200 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/40',
      icon: 'bg-amber-100 text-amber-600 dark:bg-amber-900 dark:text-amber-300',
      eyebrow: 'text-amber-600 dark:text-amber-400',
    },
  };

  const sections = computed(() => [
    {
      key: 'interpretation',
      eyebrow: 'Qué se evalúa',
      title: 'Interpretación',
      text: props.guide.interpretation,
      icon: BookOpenIcon,
      tone: tones.blue,
    },
    {
      key: 'example',
      eyebrow: 'Una forma de cumplirlo',
      title: 'Ejemplo de aplicación',
      text: props.guide.example,
      icon: LightBulbIcon,
      tone: tones.emerald,
    },
    {
      key: 'evidence',
      eyebrow: 'Qué puede pedirse o presentarse',
      title: 'Evidencia a considerar',
      text: props.guide.evidence,
      icon: DocumentCheckIcon,
      tone: tones.amber,
    },
  ]);

  function close() {
    emit('update:modelValue', false);
  }
</script>
