<template>
  <BaseModal
    :model-value="modelValue"
    :title="title"
    size="sm"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <p class="text-sm text-slate-600 dark:text-slate-300">
      {{ message }}
    </p>
    <template #footer>
      <BaseButton
        variant="secondary"
        @click="
          $emit('update:modelValue', false);
          $emit('cancel');
        "
      >
        Cancelar
      </BaseButton>
      <BaseButton
        :variant="variant"
        @click="
          $emit('update:modelValue', false);
          $emit('confirm');
        "
      >
        {{ confirmText }}
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
  import BaseModal from './BaseModal.vue';
  import BaseButton from './BaseButton.vue';

  defineProps<{
    modelValue: boolean;
    title: string;
    message: string;
    confirmText?: string;
    variant?: 'danger' | 'primary' | 'warning';
  }>();

  defineEmits<{
    'update:modelValue': [value: boolean];
    confirm: [];
    cancel: [];
  }>();
</script>
