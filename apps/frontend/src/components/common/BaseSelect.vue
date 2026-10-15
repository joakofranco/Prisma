<template>
  <div>
    <Combobox
      v-slot="{ open }"
      :model-value="modelValue"
      :disabled="disabled"
      @update:model-value="onSelect"
    >
      <!-- Headless UI nombra al campo con el ComboboxLabel (si no hay, con el botón de la flecha, que
           no tiene texto: el campo quedaba SIN nombre accesible, WCAG 4.1.2). Por eso la etiqueta
           es siempre un ComboboxLabel: visible, o solo para lectores de pantalla si no hay `label`. -->
      <ComboboxLabel
        v-if="label"
        :for="inputId"
        class="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1"
      >
        {{ label }}
        <span v-if="required" class="text-red-600 dark:text-red-400" aria-hidden="true">*</span>
      </ComboboxLabel>
      <ComboboxLabel v-else-if="ariaLabel || placeholder" :for="inputId" class="sr-only">
        {{ ariaLabel || placeholder }}
      </ComboboxLabel>
      <div ref="anchor" class="relative">
        <ComboboxInput
          :id="inputId"
          :class="[
            'w-full rounded-lg border transition-colors focus:outline-none focus:ring-2 text-slate-900 dark:text-white placeholder:text-slate-500 dark:placeholder:text-slate-300',
            size === 'sm' ? 'pl-2 pr-7 py-1 text-xs' : 'pl-3 pr-9 py-2 text-sm',
            clearableNow ? (size === 'sm' ? 'pr-12' : 'pr-14') : '',
            searchable ? '' : 'cursor-pointer caret-transparent',
            error
              ? 'border-red-400 dark:border-red-400 focus:ring-red-500 focus:border-red-500'
              : 'border-slate-300 dark:border-slate-500 focus:ring-blue-500 focus:border-blue-500',
            disabled
              ? 'bg-slate-100 dark:bg-slate-800 cursor-not-allowed'
              : 'bg-white dark:bg-slate-700',
          ]"
          :placeholder="placeholder"
          :display-value="displayValue"
          :readonly="!searchable"
          :aria-required="required ? 'true' : undefined"
          :aria-invalid="error ? 'true' : undefined"
          :aria-describedby="error ? errorId : undefined"
          autocomplete="off"
          @change="query = ($event.target as HTMLInputElement).value"
          @focus="query = ''"
          @click="!open && openMenu()"
        />
        <button
          v-if="clearableNow"
          type="button"
          :class="[
            'absolute inset-y-0 flex items-center px-1 text-slate-500 hover:text-slate-700 dark:text-slate-300 dark:hover:text-white',
            size === 'sm' ? 'right-6' : 'right-8',
          ]"
          aria-label="Quitar selección"
          @click.stop="clear"
        >
          <XMarkIcon class="w-4 h-4" />
        </button>
        <ComboboxButton
          ref="buttonRef"
          class="absolute inset-y-0 right-0 flex items-center px-2"
          :class="disabled ? 'cursor-not-allowed' : ''"
          @click="refreshPosition"
        >
          <ChevronUpDownIcon class="w-4 h-4 text-slate-500 dark:text-slate-300" />
        </ComboboxButton>

        <!-- El menú se dibuja en <body> con posición fija: dentro de un modal con scroll (o de una
             tarjeta con overflow) un menú absoluto quedaba recortado o desplazaba el modal. -->
        <Teleport to="body">
          <ComboboxOptions
            :style="menuStyle"
            class="fixed z-[60] overflow-auto rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 py-1 text-sm shadow-lg focus:outline-none"
          >
            <ComboboxOption
              v-for="option in filteredOptions"
              v-slot="{ active, selected }"
              :key="String(option.value)"
              :value="option.value"
              :disabled="option.disabled"
              as="template"
            >
              <li
                :class="[
                  'flex items-center justify-between gap-2 select-none px-3 py-2',
                  option.disabled ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
                  active
                    ? 'bg-blue-50 text-blue-800 dark:bg-blue-950 dark:text-blue-200'
                    : 'text-slate-700 dark:text-slate-200',
                  selected ? 'font-medium' : '',
                ]"
              >
                <span class="truncate">{{ option.label }}</span>
                <CheckIcon
                  v-if="selected"
                  class="w-4 h-4 shrink-0 text-blue-600 dark:text-blue-300"
                />
              </li>
            </ComboboxOption>
            <div
              v-if="filteredOptions.length === 0"
              class="px-3 py-2 text-slate-600 dark:text-slate-300"
            >
              {{ query ? `Sin resultados para "${query}"` : 'No hay opciones' }}
            </div>
          </ComboboxOptions>
        </Teleport>
      </div>
    </Combobox>
    <p v-if="error" :id="errorId" class="mt-1 text-xs text-red-600 dark:text-red-400">
      {{ error }}
    </p>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, useId } from 'vue';
  import { useElementBounding, useEventListener } from '@vueuse/core';
  import {
    Combobox,
    ComboboxLabel,
    ComboboxInput,
    ComboboxButton,
    ComboboxOptions,
    ComboboxOption,
  } from '@headlessui/vue';
  import { CheckIcon, ChevronUpDownIcon, XMarkIcon } from '@heroicons/vue/24/outline';
  import { matchesQuery } from '@/utils/search';

  type Value = string | number;

  const props = withDefaults(
    defineProps<{
      modelValue: Value;
      options: { value: Value; label: string; disabled?: boolean }[];
      label?: string;
      placeholder?: string;
      disabled?: boolean;
      required?: boolean;
      error?: string;
      id?: string;
      /** Nombre accesible cuando no hay `label` visible (p.ej. un filtro). Si falta, el placeholder. */
      ariaLabel?: string;
      /** Permite buscar escribiendo dentro del propio desplegable (por defecto sí). */
      searchable?: boolean;
      /** Muestra una "x" para volver a "sin selección" (emite ''). */
      clearable?: boolean;
      /** `sm` = compacto, para tablas y editores en línea. */
      size?: 'md' | 'sm';
    }>(),
    {
      label: '',
      placeholder: '',
      disabled: false,
      required: false,
      error: '',
      id: '',
      ariaLabel: '',
      searchable: true,
      clearable: false,
      size: 'md',
    },
  );

  const emit = defineEmits<{
    'update:modelValue': [value: Value];
  }>();

  // Ver el comentario en BaseInput.vue: sin id la etiqueta no quedaba asociada al campo.
  const autoId = useId();
  const inputId = computed(() => props.id || autoId);
  const errorId = computed(() => `${inputId.value}-error`);

  // Texto que se está tipeando para filtrar. Vacío = se ofrecen TODAS las opciones (como un
  // <select>): solo filtra mientras se tipea, nunca contra el texto de la opción ya elegida.
  const query = ref('');

  const filteredOptions = computed(() =>
    props.searchable && query.value
      ? props.options.filter((o) => matchesQuery(o.label, query.value))
      : props.options,
  );

  function displayValue(value: unknown): string {
    return props.options.find((o) => o.value === value)?.label ?? '';
  }

  const clearableNow = computed(
    () => props.clearable && !props.disabled && props.modelValue !== '' && props.modelValue != null,
  );

  function onSelect(value: Value) {
    emit('update:modelValue', value);
    query.value = '';
  }

  function clear() {
    emit('update:modelValue', '');
    query.value = '';
  }

  // ---- posición del menú (fixed, anclado al campo) ----
  const anchor = ref<HTMLElement | null>(null);
  const buttonRef = ref<{ el: HTMLElement | null } | null>(null);
  const { left, top, bottom, width, update } = useElementBounding(anchor);
  // El campo puede estar dentro de un contenedor con scroll propio (el cuerpo de un modal): el
  // evento no burbujea hasta window, por eso se escucha en fase de captura.
  useEventListener(window, 'scroll', update, { capture: true, passive: true });
  useEventListener(window, 'resize', update, { passive: true });

  function refreshPosition() {
    update();
  }

  function openMenu() {
    if (props.disabled) return;
    update();
    buttonRef.value?.el?.click();
  }

  const menuStyle = computed(() => {
    const margin = 8;
    const below = window.innerHeight - bottom.value - margin;
    const above = top.value - margin;
    const openUp = below < 200 && above > below;
    const space = Math.max(120, (openUp ? above : below) - 4);
    return {
      left: `${left.value}px`,
      minWidth: `${width.value}px`,
      maxWidth: '90vw',
      maxHeight: `${Math.min(space, 288)}px`,
      ...(openUp
        ? { bottom: `${window.innerHeight - top.value + 4}px` }
        : { top: `${bottom.value + 4}px` }),
    };
  });
</script>
