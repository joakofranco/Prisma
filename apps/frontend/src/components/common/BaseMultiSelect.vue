<template>
  <div>
    <Combobox
      v-slot="{ open }"
      multiple
      :model-value="modelValue"
      :disabled="disabled"
      @update:model-value="onUpdate"
    >
      <!-- Ver el comentario de BaseSelect.vue: la etiqueta es siempre un ComboboxLabel para que el
           campo tenga nombre accesible. -->
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
        <div
          :class="[
            'flex flex-wrap items-center gap-1.5 min-h-[2.5rem] pl-2 pr-8 py-1.5 rounded-lg border transition-colors focus-within:ring-2',
            error
              ? 'border-red-400 dark:border-red-400 focus-within:ring-red-500'
              : 'border-slate-300 dark:border-slate-500 focus-within:ring-blue-500 focus-within:border-blue-500',
            disabled
              ? 'bg-slate-100 dark:bg-slate-800 cursor-not-allowed'
              : 'bg-white dark:bg-slate-700 cursor-text',
          ]"
          @click="focusInput"
        >
          <span
            v-for="opt in selectedOptions"
            :key="String(opt.value)"
            class="inline-flex items-center gap-1 rounded-full bg-blue-50 dark:bg-blue-950 text-blue-800 dark:text-blue-200 pl-2.5 pr-1 py-0.5 text-xs font-medium"
          >
            {{ opt.label }}
            <button
              v-if="!disabled"
              type="button"
              class="rounded-full p-0.5 hover:bg-blue-100 dark:hover:bg-blue-900"
              :aria-label="`Quitar ${opt.label}`"
              @click.stop="remove(opt.value)"
            >
              <XMarkIcon class="w-3 h-3" />
            </button>
            <span v-else class="pr-1.5" />
          </span>
          <ComboboxInput
            :id="inputId"
            ref="inputRef"
            class="flex-1 min-w-[8ch] bg-transparent border-0 p-0 text-sm text-slate-900 dark:text-white placeholder:text-slate-500 dark:placeholder:text-slate-300 focus:outline-none focus:ring-0"
            :placeholder="selectedOptions.length === 0 ? placeholder : ''"
            :display-value="() => ''"
            :aria-required="required ? 'true' : undefined"
            :aria-invalid="error ? 'true' : undefined"
            :aria-describedby="error ? errorId : undefined"
            autocomplete="off"
            @change="query = ($event.target as HTMLInputElement).value"
            @click.stop="!open && openMenu()"
            @keydown.backspace="onBackspace"
          />
        </div>
        <ComboboxButton
          ref="buttonRef"
          class="absolute right-0 top-0 h-10 flex items-center px-2"
          @click="update()"
        >
          <ChevronUpDownIcon class="w-4 h-4 text-slate-500 dark:text-slate-300" />
        </ComboboxButton>

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
              :disabled="isDisabled(option)"
              as="template"
            >
              <li
                :class="[
                  'flex items-center justify-between gap-2 select-none px-3 py-2',
                  isDisabled(option) ? 'cursor-not-allowed opacity-50' : 'cursor-pointer',
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

  type Option = { value: string; label: string; disabled?: boolean };

  const props = withDefaults(
    defineProps<{
      modelValue: string[];
      options: Option[];
      label?: string;
      placeholder?: string;
      disabled?: boolean;
      required?: boolean;
      error?: string;
      id?: string;
      ariaLabel?: string;
      /** Máximo de elementos. Con 1, elegir otro REEMPLAZA al actual; con más, al llegar al tope las
       * demás opciones quedan deshabilitadas. Sin valor, sin tope. */
      max?: number;
    }>(),
    {
      label: '',
      placeholder: '',
      disabled: false,
      required: false,
      error: '',
      id: '',
      ariaLabel: '',
      max: undefined,
    },
  );

  const emit = defineEmits<{
    'update:modelValue': [value: string[]];
  }>();

  const autoId = useId();
  const inputId = computed(() => props.id || autoId);
  const errorId = computed(() => `${inputId.value}-error`);

  const query = ref('');

  // Elementos elegidos, en el orden en que se eligieron. Un valor que ya no está entre las opciones
  // se sigue mostrando (con su valor como texto) para que no desaparezca sin que se note.
  const selectedOptions = computed<Option[]>(() =>
    props.modelValue.map(
      (v) => props.options.find((o) => o.value === v) ?? { value: v, label: v },
    ),
  );

  const filteredOptions = computed(() =>
    query.value ? props.options.filter((o) => matchesQuery(o.label, query.value)) : props.options,
  );

  const limitReached = computed(
    () => props.max !== undefined && props.max > 1 && props.modelValue.length >= props.max,
  );

  function isDisabled(option: Option): boolean {
    return !!option.disabled || (limitReached.value && !props.modelValue.includes(option.value));
  }

  function onUpdate(value: string[]) {
    // Con tope 1, elegir otro valor reemplaza al actual (en vez de obligar a quitar el anterior).
    const next = props.max === 1 && value.length > 1 ? [value[value.length - 1]] : value;
    emit('update:modelValue', next);
    query.value = '';
  }

  function remove(value: string) {
    emit(
      'update:modelValue',
      props.modelValue.filter((v) => v !== value),
    );
  }

  // Backspace con el cuadro de búsqueda vacío quita el último elemento (como en los selectores de
  // etiquetas habituales).
  function onBackspace() {
    if (query.value === '' && props.modelValue.length > 0 && !props.disabled) {
      remove(props.modelValue[props.modelValue.length - 1]);
    }
  }

  // ---- posición del menú (ver BaseSelect.vue) ----
  const anchor = ref<HTMLElement | null>(null);
  const inputRef = ref<{ el: HTMLInputElement | null } | null>(null);
  const buttonRef = ref<{ el: HTMLElement | null } | null>(null);
  const { left, top, bottom, width, update } = useElementBounding(anchor);
  useEventListener(window, 'scroll', update, { capture: true, passive: true });
  useEventListener(window, 'resize', update, { passive: true });

  function focusInput() {
    if (!props.disabled) inputRef.value?.el?.focus();
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
