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
              // Altura acotada a la ventana (100dvh = alto visible real, también en móvil con barra de
              // direcciones): cabecera y pie SIEMPRE visibles, y solo el cuerpo se desplaza. Antes el
              // panel crecía con su contenido, se salía por arriba y por abajo y los botones de
              // guardar quedaban fuera de pantalla.
              'relative bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full flex flex-col max-h-[calc(100dvh-2rem)]',
              // Proporcional a la ventana (nunca un ancho fijo en px): en móvil ocupa todo; desde `md`,
              // sm = avisos/confirmaciones cortos (40%), md = formularios medianos (60%) y lg =
              // formularios y contenidos grandes (90%). El `min-w` solo evita que un diálogo corto
              // quede ilegible en tabletas angostas.
              size === 'sm'
                ? 'md:w-[40%] md:min-w-[22rem]'
                : size === 'lg'
                  ? 'md:w-[90%]'
                  : 'md:w-[60%] md:min-w-[28rem]',
            ]"
          >
            <div
              v-if="title"
              class="shrink-0 flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700"
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

            <div
              class="flex-1 min-h-0 overflow-y-auto px-6 py-4 text-slate-700 dark:text-slate-200"
            >
              <slot />
            </div>

            <div
              v-if="$slots.footer"
              class="shrink-0 px-6 py-4 border-t border-slate-200 dark:border-slate-700 flex items-center justify-end gap-3"
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
  import { watch, onBeforeUnmount } from 'vue';
  import { onKeyStroke } from '@vueuse/core';
  import { XMarkIcon } from '@heroicons/vue/24/outline';
  import { lockBodyScroll, unlockBodyScroll } from '@/composables/useBodyScrollLock';

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

  // Mientras el modal está abierto la página de fondo no se desplaza (C19).
  let locked = false;
  watch(
    () => props.modelValue,
    (open) => {
      if (open && !locked) {
        lockBodyScroll();
        locked = true;
      } else if (!open && locked) {
        unlockBodyScroll();
        locked = false;
      }
    },
    { immediate: true },
  );
  onBeforeUnmount(() => {
    if (locked) unlockBodyScroll();
  });

  // ESC cierra el modal (WCAG 2.1.1), salvo los persistentes.
  onKeyStroke('Escape', () => {
    if (props.modelValue) close();
  });
</script>
