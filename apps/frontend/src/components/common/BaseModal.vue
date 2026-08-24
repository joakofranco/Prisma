<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition ease-out duration-200"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition ease-in duration-150"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div v-if="modelValue" class="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div class="fixed inset-0 bg-black/50" @click="close" />
        <Transition
          enter-active-class="transition ease-out duration-200"
          enter-from-class="opacity-0 scale-95"
          enter-to-class="opacity-100 scale-100"
          leave-active-class="transition ease-in duration-150"
          leave-from-class="opacity-100 scale-100"
          leave-to-class="opacity-0 scale-95"
        >
          <div
            v-if="modelValue"
            :class="[
              'relative bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full',
              size === 'sm' ? 'max-w-md' : size === 'lg' ? 'max-w-3xl' : 'max-w-xl',
            ]"
          >
            <div
              v-if="title"
              class="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700"
            >
              <h3 class="text-lg font-semibold text-slate-900 dark:text-white">
                {{ title }}
              </h3>
              <button
                class="p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                title="Cerrar"
                @click="close"
              >
                <XMarkIcon class="w-5 h-5 text-slate-500 dark:text-slate-400" />
              </button>
            </div>

            <div class="px-6 py-4 text-slate-700 dark:text-slate-200">
              <slot />
            </div>

            <div
              v-if="$slots.footer"
              class="px-6 py-4 border-t border-slate-200 dark:border-slate-700 flex items-center justify-end gap-3"
            >
              <slot name="footer" />
            </div>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
  import { XMarkIcon } from '@heroicons/vue/24/outline';

  const props = withDefaults(
    defineProps<{
      modelValue: boolean;
      title?: string;
      size?: 'sm' | 'md' | 'lg';
      persistent?: boolean;
    }>(),
    {
      title: '',
      size: 'md',
      persistent: false,
    },
  );

  const emit = defineEmits<{
    'update:modelValue': [value: boolean];
  }>();

  function close() {
    if (!props.persistent) {
      emit('update:modelValue', false);
    }
  }
</script>
