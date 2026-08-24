<template>
  <div>
    <label v-if="label" :for="id" class="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
      {{ label }}
      <span v-if="required" class="text-red-500">*</span>
    </label>
    <Combobox :model-value="modelValue" :disabled="disabled" @update:model-value="onSelect">
      <div class="relative">
        <ComboboxInput
          :id="id"
          :class="[
            'w-full pl-3 pr-9 py-2 rounded-lg border text-sm transition-colors focus:outline-none focus:ring-2 text-slate-900 dark:text-white',
            error
              ? 'border-red-300 dark:border-red-500 focus:ring-red-500 focus:border-red-500'
              : 'border-slate-300 dark:border-slate-600 focus:ring-blue-500 focus:border-blue-500',
            disabled
              ? 'bg-slate-100 dark:bg-slate-800 cursor-not-allowed'
              : 'bg-white dark:bg-slate-700',
          ]"
          :placeholder="placeholder"
          :display-value="displayValue"
          autocomplete="off"
          @change="query = ($event.target as HTMLInputElement).value"
          @focus="query = ''"
        />
        <ComboboxButton class="absolute inset-y-0 right-0 flex items-center px-2.5" @click="query = ''">
          <ChevronUpDownIcon class="w-4 h-4 text-slate-400" />
        </ComboboxButton>
        <ComboboxOptions
          v-if="filteredOptions.length > 0"
          class="absolute z-10 mt-1 max-h-60 w-full overflow-auto rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 py-1 text-sm shadow-lg focus:outline-none"
        >
          <ComboboxOption
            v-for="option in filteredOptions"
            v-slot="{ active, selected }"
            :key="option.value"
            :value="option.value"
            as="template"
          >
            <li
              :class="[
                'cursor-pointer select-none px-3 py-2',
                active
                  ? 'bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300'
                  : 'text-slate-700 dark:text-slate-200',
                selected ? 'font-medium' : '',
              ]"
            >
              {{ option.label }}
            </li>
          </ComboboxOption>
        </ComboboxOptions>
        <ComboboxOptions
          v-else-if="query !== ''"
          class="absolute z-10 mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 py-2 px-3 text-sm text-slate-400 shadow-lg"
        >
          Sin resultados para "{{ query }}"
        </ComboboxOptions>
      </div>
    </Combobox>
    <p v-if="error" class="mt-1 text-xs text-red-600">
      {{ error }}
    </p>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed } from 'vue';
  import {
    Combobox,
    ComboboxInput,
    ComboboxButton,
    ComboboxOptions,
    ComboboxOption,
  } from '@headlessui/vue';
  import { ChevronUpDownIcon } from '@heroicons/vue/24/outline';

  const props = withDefaults(
    defineProps<{
      modelValue: string;
      options: { value: string; label: string }[];
      label?: string;
      placeholder?: string;
      disabled?: boolean;
      required?: boolean;
      error?: string;
      id?: string;
    }>(),
    {
      label: '',
      placeholder: '',
      disabled: false,
      required: false,
      error: '',
      id: '',
    },
  );

  const emit = defineEmits<{
    'update:modelValue': [value: string];
  }>();

  // Vacío = mostrar TODAS las opciones (comportamiento de dropdown, como un <select>) -- solo se
  // usa para filtrar mientras el usuario está tipeando (@change), nunca para reflejar el valor ya
  // elegido: al enfocar (click) se limpia de nuevo para volver a ofrecer la lista completa en vez
  // de quedar "filtrado" contra el texto de la opción ya seleccionada.
  const query = ref('');

  // Quita diacríticos (tildes) tras normalizar a NFD, para que la búsqueda ignore acentos.
  const DIACRITICS_RANGE = new RegExp('[\\u0300-\\u036f]', 'g');

  function normalize(text: string): string {
    return text.normalize('NFD').replace(DIACRITICS_RANGE, '').toLowerCase();
  }

  const filteredOptions = computed(() => {
    if (query.value === '') return props.options;
    const q = normalize(query.value);
    return props.options.filter((option) => normalize(option.label).includes(q));
  });

  function displayValue(value: unknown): string {
    return props.options.find((option) => option.value === value)?.label || '';
  }

  function onSelect(value: unknown) {
    emit('update:modelValue', value as string);
    query.value = '';
  }
</script>
