<template>
  <BaseModal
    :model-value="modelValue"
    :title="`Guía del control ${code}`"
    size="md"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p
      class="text-sm text-slate-600 dark:text-slate-300 border-l-4 border-blue-500 pl-3"
      data-testid="guide-control-text"
    >
      {{ description }}
    </p>

    <dl class="mt-5 space-y-4">
      <div v-for="section in sections" :key="section.key" :data-testid="`guide-${section.key}`">
        <dt class="text-sm font-semibold text-slate-900 dark:text-white">{{ section.title }}</dt>
        <dd class="mt-1 text-sm text-slate-700 dark:text-slate-200 whitespace-pre-line">
          {{ section.text }}
        </dd>
      </div>
    </dl>

    <p class="mt-5 text-xs text-slate-500 dark:text-slate-400">
      Texto de apoyo para evaluador y evaluado. El ejemplo es ilustrativo, no la única forma válida
      de cumplir el control, y la lista de evidencias no es exhaustiva. No sustituye el texto
      oficial de Agesic.
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

  const sections = computed(() => [
    { key: 'interpretation', title: 'Interpretación', text: props.guide.interpretation },
    { key: 'example', title: 'Ejemplo de aplicación', text: props.guide.example },
    { key: 'evidence', title: 'Evidencia a considerar', text: props.guide.evidence },
  ]);

  function close() {
    emit('update:modelValue', false);
  }
</script>
