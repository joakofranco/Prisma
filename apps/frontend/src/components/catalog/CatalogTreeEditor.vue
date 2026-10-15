<template>
  <div class="space-y-3">
    <div
      v-for="(func, fi) in modelValue"
      :key="func.clientId"
      class="border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden bg-white dark:bg-slate-800"
    >
      <div class="flex items-center gap-2 px-3 py-2.5 bg-slate-50 dark:bg-slate-900">
        <button
          type="button"
          class="p-1 rounded hover:bg-slate-200 shrink-0 dark:hover:bg-slate-700"
          :title="expanded.has(func.clientId) ? 'Contraer' : 'Expandir'"
          @click="toggle(func.clientId)"
        >
          <ChevronDownIcon
            :class="[
              'w-4 h-4 transition-transform',
              expanded.has(func.clientId) ? 'rotate-180' : '',
            ]"
          />
        </button>
        <span class="text-[11px] font-semibold text-slate-500 shrink-0 dark:text-slate-400">F{{ fi + 1 }}</span>
        <input v-model="func.code" placeholder="Código" :class="[inputCls, 'w-24']" />
        <input
          v-model="func.name"
          placeholder="Nombre de la función"
          :class="[inputCls, 'flex-1']"
        />
        <button
          type="button"
          class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 hover:text-red-600 shrink-0 dark:hover:bg-red-950 dark:hover:text-red-300 dark:text-slate-400"
          title="Eliminar función"
          @click="removeAt(modelValue, fi)"
        >
          <TrashIcon class="w-4 h-4" />
        </button>
      </div>

      <div v-if="expanded.has(func.clientId)" class="p-3 space-y-3">
        <textarea
          v-model="func.description"
          placeholder="Descripción de la función (opcional)"
          rows="1"
          :class="[inputCls, 'w-full']"
        />

        <div class="pl-4 border-l-2 border-slate-100 space-y-2 dark:border-slate-700">
          <div
            v-for="(cat, ci) in func.categories"
            :key="cat.clientId"
            class="border border-slate-100 rounded-lg p-2.5 bg-slate-50 dark:bg-slate-900/60 space-y-2 dark:border-slate-700"
          >
            <div class="flex items-center gap-2">
              <span class="text-[11px] font-semibold text-slate-500 shrink-0 dark:text-slate-400">C{{ ci + 1 }}</span>
              <input v-model="cat.code" placeholder="Código" :class="[inputCls, 'w-24']" />
              <input
                v-model="cat.name"
                placeholder="Nombre de la categoría"
                :class="[inputCls, 'flex-1']"
              />
              <button
                type="button"
                class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 hover:text-red-600 shrink-0 dark:hover:bg-red-950 dark:hover:text-red-300 dark:text-slate-400"
                title="Eliminar categoría"
                @click="removeAt(func.categories, ci)"
              >
                <TrashIcon class="w-4 h-4" />
              </button>
            </div>

            <div class="pl-4 border-l-2 border-slate-100 space-y-2 dark:border-slate-700">
              <div
                v-for="(sub, si) in cat.subcategories"
                :key="sub.clientId"
                class="border border-slate-100 rounded-lg p-2.5 bg-white dark:bg-slate-800 space-y-2 dark:border-slate-700"
              >
                <div class="flex items-center gap-2">
                  <span class="text-[11px] font-semibold text-slate-500 shrink-0 dark:text-slate-400">
                    SC{{ si + 1 }}
                  </span>
                  <input v-model="sub.code" placeholder="Código" :class="[inputCls, 'w-24']" />
                  <input
                    v-model="sub.name"
                    placeholder="Nombre de la subcategoría"
                    :class="[inputCls, 'flex-1']"
                  />
                  <button
                    type="button"
                    class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 hover:text-red-600 shrink-0 dark:hover:bg-red-950 dark:hover:text-red-300 dark:text-slate-400"
                    title="Eliminar subcategoría"
                    @click="removeAt(cat.subcategories, si)"
                  >
                    <TrashIcon class="w-4 h-4" />
                  </button>
                </div>

                <div class="pl-4 border-l-2 border-slate-100 space-y-2 dark:border-slate-700">
                  <div
                    v-for="(req, ri) in sub.requirements"
                    :key="req.clientId"
                    class="border border-slate-100 rounded-lg p-2.5 bg-slate-50 dark:bg-slate-900/40 space-y-2 dark:border-slate-700"
                  >
                    <div class="flex items-center gap-2">
                      <span class="text-[11px] font-semibold text-slate-500 shrink-0 dark:text-slate-400">
                        R{{ ri + 1 }}
                      </span>
                      <input v-model="req.code" placeholder="Código" :class="[inputCls, 'w-24']" />
                      <input
                        v-model="req.description"
                        placeholder="Descripción del requisito"
                        :class="[inputCls, 'flex-1']"
                      />
                      <button
                        type="button"
                        class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 hover:text-red-600 shrink-0 dark:hover:bg-red-950 dark:hover:text-red-300 dark:text-slate-400"
                        title="Eliminar requisito"
                        @click="removeAt(sub.requirements, ri)"
                      >
                        <TrashIcon class="w-4 h-4" />
                      </button>
                    </div>

                    <div class="pl-4 border-l-2 border-slate-100 space-y-1.5 dark:border-slate-700">
                      <div
                        v-for="(ctrl, cti) in req.controls"
                        :key="ctrl.clientId"
                        class="flex items-center gap-2"
                      >
                        <span class="text-[11px] font-semibold text-slate-500 shrink-0 dark:text-slate-400">
                          CT{{ cti + 1 }}
                        </span>
                        <input
                          v-model="ctrl.code"
                          placeholder="Código"
                          :class="[inputCls, 'w-24']"
                        />
                        <input
                          v-model="ctrl.description"
                          placeholder="Descripción del control"
                          :class="[inputCls, 'flex-1']"
                        />
                        <BaseSelect
                          v-model="ctrl.targetLevel"
                          :options="levelOptions"
                          :searchable="false"
                          size="sm"
                          aria-label="Nivel objetivo"
                          class="w-28"
                        />
                        <button
                          type="button"
                          class="p-1.5 rounded-lg hover:bg-red-50 text-slate-500 hover:text-red-600 shrink-0 dark:hover:bg-red-950 dark:hover:text-red-300 dark:text-slate-400"
                          title="Eliminar control"
                          @click="removeAt(req.controls, cti)"
                        >
                          <TrashIcon class="w-4 h-4" />
                        </button>
                      </div>
                      <button type="button" :class="addBtnCls" @click="addControl(req)">
                        <PlusIcon class="w-3.5 h-3.5" /> Control
                      </button>
                    </div>
                  </div>
                  <button type="button" :class="addBtnCls" @click="addRequirement(sub)">
                    <PlusIcon class="w-3.5 h-3.5" /> Requisito
                  </button>
                </div>
              </div>
              <button type="button" :class="addBtnCls" @click="addSubcategory(cat)">
                <PlusIcon class="w-3.5 h-3.5" /> Subcategoría
              </button>
            </div>
          </div>
          <button type="button" :class="addBtnCls" @click="addCategory(func)">
            <PlusIcon class="w-3.5 h-3.5" /> Categoría
          </button>
        </div>
      </div>
    </div>

    <button type="button" :class="addBtnCls" @click="addFunction">
      <PlusIcon class="w-3.5 h-3.5" /> Función
    </button>
  </div>
</template>

<script setup lang="ts">
  import { reactive } from 'vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import { ChevronDownIcon, TrashIcon, PlusIcon } from '@heroicons/vue/24/outline';
  import type {
    CatalogImportFunction,
    CatalogImportCategory,
    CatalogImportSubcategory,
    CatalogImportRequirement,
  } from '@/types';

  // v-model directo sobre el array (no defineModel con objeto envoltorio): los componentes que
  // arman este árbol (parseo de JSON/CSV, "agregar función" acá mismo) todos mutan la MISMA
  // referencia que expone CatalogImportPanel, así que no hace falta re-emitir en cada cambio de
  // un input individual -- typear cada código/nombre no dispara un evento por tecla.
  const modelValue = defineModel<CatalogImportFunction[]>({ required: true });

  const levelOptions = [1, 2, 3, 4].map((lvl) => ({ value: lvl, label: `Nivel ${lvl}` }));

  const inputCls =
    'px-2 py-1.5 rounded-md border border-slate-300 dark:border-slate-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder:text-slate-500 dark:placeholder:text-slate-300 text-xs focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500';
  const addBtnCls =
    'inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700 hover:underline dark:text-blue-400 dark:hover:text-blue-300';

  const expanded = reactive(new Set<string>());

  function toggle(id: string) {
    if (expanded.has(id)) expanded.delete(id);
    else expanded.add(id);
  }

  function clientId(): string {
    return crypto.randomUUID();
  }

  function removeAt<T>(list: T[], index: number) {
    list.splice(index, 1);
  }

  function addFunction() {
    const id = clientId();
    modelValue.value.push({
      clientId: id,
      code: '',
      name: '',
      description: '',
      categories: [],
    });
    expanded.add(id);
  }

  function addCategory(func: CatalogImportFunction) {
    func.categories.push({
      clientId: clientId(),
      code: '',
      name: '',
      description: '',
      subcategories: [],
    });
  }

  function addSubcategory(cat: CatalogImportCategory) {
    cat.subcategories.push({
      clientId: clientId(),
      code: '',
      name: '',
      description: '',
      requirements: [],
    });
  }

  function addRequirement(sub: CatalogImportSubcategory) {
    sub.requirements.push({ clientId: clientId(), code: '', description: '', controls: [] });
  }

  function addControl(req: CatalogImportRequirement) {
    req.controls.push({ clientId: clientId(), code: '', description: '', targetLevel: 1 });
  }

  defineExpose({ addFunction });
</script>
