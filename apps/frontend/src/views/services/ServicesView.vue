<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Servicios</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Acceso a los servicios de la infraestructura de PRISMA. Solo disponible para el
          Administrador de Plataforma y desde la VPN.
        </p>
      </div>
      <BaseButton
        v-if="!blocked && !loading"
        variant="secondary"
        data-testid="services-host-open"
        @click="openHost"
      >
        <GlobeAltIcon class="w-4 h-4 mr-2" />
        Cambiar host de todos
      </BaseButton>
    </div>

    <div v-if="loading" class="py-16 text-center text-sm text-slate-500 dark:text-slate-400">
      Cargando servicios...
    </div>

    <div
      v-else-if="blocked"
      class="flex flex-col items-center text-center gap-3 py-14 px-6 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm"
      data-testid="services-vpn-required"
    >
      <div
        class="w-14 h-14 rounded-full bg-amber-50 dark:bg-amber-950 flex items-center justify-center"
      >
        <ShieldExclamationIcon class="w-7 h-7 text-amber-600 dark:text-amber-300" />
      </div>
      <h2 class="text-lg font-semibold text-slate-900 dark:text-white">
        Conectate a la VPN para ver los servicios
      </h2>
      <p class="text-sm text-slate-500 dark:text-slate-400 max-w-md">
        {{ blockedMessage }}
      </p>
      <BaseButton variant="primary" @click="load">Reintentar</BaseButton>
    </div>

    <BaseAlert v-else-if="loadError" variant="danger" data-testid="services-load-error">
      {{ loadError }}
    </BaseAlert>

    <template v-else>
      <div
        class="grid grid-cols-[repeat(auto-fill,minmax(min(100%,20rem),1fr))] gap-4"
        data-testid="services-grid"
      >
        <article
          v-for="service in orderedServices"
          :key="service.id"
          class="group flex flex-col gap-3 p-4 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm hover:shadow-md hover:border-blue-300 dark:hover:border-blue-700 transition"
          :data-testid="`service-card-${service.key}`"
        >
          <div class="flex items-start gap-3">
            <div
              class="w-12 h-12 shrink-0 rounded-xl bg-white ring-1 ring-slate-200 dark:ring-slate-600 shadow-sm flex items-center justify-center"
            >
              <img
                v-if="SERVICE_ICONS[service.key]"
                :src="SERVICE_ICONS[service.key]"
                :alt="`Logo de ${service.name}`"
                class="w-7 h-7 select-none"
              />
              <ServerStackIcon v-else class="w-7 h-7 text-slate-500" />
            </div>
            <div class="min-w-0 flex-1">
              <h3 class="text-base font-semibold text-slate-900 dark:text-white">
                {{ service.name }}
              </h3>
              <span
                class="inline-block mt-0.5 mb-1 px-2 py-0.5 rounded-full text-[11px] font-medium bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300"
                :data-testid="`service-category-${service.key}`"
              >
                {{ categoryLabel(service.category) }}
              </span>
              <p class="text-sm text-slate-500 dark:text-slate-400">{{ service.description }}</p>
            </div>
          </div>

          <p
            class="text-xs font-mono text-slate-500 dark:text-slate-400 truncate"
            :title="service.url"
            :data-testid="`service-url-${service.key}`"
          >
            {{ service.url }}
          </p>

          <div class="flex items-center gap-2 mt-auto pt-1">
            <a
              :href="service.url"
              target="_blank"
              rel="noopener noreferrer"
              class="inline-flex items-center justify-center gap-2 px-4 py-2 rounded-lg text-sm font-medium bg-blue-600 text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 dark:focus:ring-offset-slate-800 transition-colors"
              :data-testid="`service-open-${service.key}`"
            >
              Abrir
              <ArrowTopRightOnSquareIcon class="w-4 h-4" />
            </a>
            <button
              type="button"
              class="p-2 rounded-lg text-slate-500 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700 transition-colors"
              :title="`Editar la URL de ${service.name}`"
              :aria-label="`Editar la URL de ${service.name}`"
              :data-testid="`service-edit-${service.key}`"
              @click="openEdit(service)"
            >
              <PencilSquareIcon class="w-5 h-5" />
            </button>
            <button
              v-if="service.url !== service.defaultUrl"
              type="button"
              class="p-2 rounded-lg text-slate-500 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700 transition-colors"
              :title="`Restablecer la URL de ${service.name}`"
              :aria-label="`Restablecer la URL de ${service.name}`"
              :data-testid="`service-reset-${service.key}`"
              @click="resetService(service)"
            >
              <ArrowUturnLeftIcon class="w-5 h-5" />
            </button>
          </div>
        </article>
      </div>
    </template>

    <BaseModal v-model="editOpen" :title="`Editar la URL de ${editing?.name ?? ''}`" size="md">
      <form class="space-y-3" novalidate @submit.prevent="saveEdit">
        <BaseAlert v-if="editError" variant="danger" data-testid="service-edit-error">
          {{ editError }}
        </BaseAlert>
        <BaseInput
          id="service-url"
          v-model="editUrl"
          label="URL"
          placeholder="http://localhost:3000"
          :error="editUrlError"
          data-testid="service-url-input"
          required
        />
        <p class="text-xs text-slate-500 dark:text-slate-400">
          Por defecto: <span class="font-mono">{{ editing?.defaultUrl }}</span>
        </p>
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="editOpen = false">Cancelar</BaseButton>
        <BaseButton
          variant="primary"
          :loading="saving"
          :disabled="!!editUrlError || editUrl.trim() === editing?.url"
          data-testid="service-edit-save"
          @click="saveEdit"
        >
          Guardar
        </BaseButton>
      </template>
    </BaseModal>

    <BaseModal v-model="hostOpen" title="Cambiar el host de todos los servicios" size="md">
      <form class="space-y-3" novalidate @submit.prevent="saveHost">
        <p class="text-sm text-slate-600 dark:text-slate-300">
          Reemplaza solo el host de cada URL: se conservan el esquema, el puerto y la ruta. Útil al
          mover PRISMA a otra máquina (por ejemplo, la IP del servidor en la VPN).
        </p>
        <BaseAlert v-if="hostError" variant="danger" data-testid="services-host-error">
          {{ hostError }}
        </BaseAlert>
        <BaseInput
          id="services-host"
          v-model="hostValue"
          label="Host"
          placeholder="10.8.0.1"
          :error="hostValueError"
          data-testid="services-host-input"
          required
        />
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="hostOpen = false">Cancelar</BaseButton>
        <BaseButton
          variant="primary"
          :loading="saving"
          :disabled="!!hostValueError"
          data-testid="services-host-save"
          @click="saveHost"
        >
          Aplicar a todos
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue';
  import {
    ArrowTopRightOnSquareIcon,
    ArrowUturnLeftIcon,
    GlobeAltIcon,
    PencilSquareIcon,
    ServerStackIcon,
    ShieldExclamationIcon,
  } from '@heroicons/vue/24/outline';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import { platformServicesService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import type { PlatformService } from '@/types';
  import { getErrorMessage } from '@/utils/helpers';
  import {
    SERVICE_ICONS,
    categoryLabel,
    sortServices,
    validateServiceUrl,
    validateServicesHost,
  } from '@/utils/platformServices';

  const notification = useNotificationStore();

  const services = ref<PlatformService[]>([]);
  const loading = ref(true);
  const loadError = ref('');
  const blocked = ref(false);
  const blockedMessage = ref('');
  const saving = ref(false);

  const orderedServices = computed(() => sortServices(services.value));

  const editOpen = ref(false);
  const editing = ref<PlatformService | null>(null);
  const editUrl = ref('');
  const editError = ref('');
  const editUrlError = computed(() => validateServiceUrl(editUrl.value));

  const hostOpen = ref(false);
  const hostValue = ref('');
  const hostError = ref('');
  const hostValueError = computed(() => validateServicesHost(hostValue.value));

  async function load() {
    loading.value = true;
    loadError.value = '';
    blocked.value = false;
    try {
      const { data } = await platformServicesService.getAll();
      services.value = data;
    } catch (err) {
      const data = (err as { response?: { status?: number; data?: { code?: string } } }).response;
      if (data?.status === 403 && data.data?.code === 'VPN_REQUIRED') {
        blocked.value = true;
        blockedMessage.value = getErrorMessage(err);
      } else {
        loadError.value = getErrorMessage(err, 'No se pudieron cargar los servicios');
      }
    } finally {
      loading.value = false;
    }
  }

  function replace(updated: PlatformService) {
    services.value = services.value.map((s) => (s.id === updated.id ? updated : s));
  }

  function openEdit(service: PlatformService) {
    editing.value = service;
    editUrl.value = service.url;
    editError.value = '';
    editOpen.value = true;
  }

  async function saveEdit() {
    if (!editing.value || editUrlError.value || editUrl.value.trim() === editing.value.url) return;
    saving.value = true;
    editError.value = '';
    try {
      const { data } = await platformServicesService.updateUrl(
        editing.value.id,
        editUrl.value.trim(),
      );
      replace(data);
      editOpen.value = false;
      notification.success(`URL de ${data.name} actualizada`);
    } catch (err) {
      editError.value = getErrorMessage(err, 'No se pudo guardar la URL');
    } finally {
      saving.value = false;
    }
  }

  async function resetService(service: PlatformService) {
    try {
      const { data } = await platformServicesService.reset(service.id);
      replace(data);
      notification.success(`URL de ${data.name} restablecida`);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo restablecer la URL'));
    }
  }

  function openHost() {
    hostValue.value = '';
    hostError.value = '';
    hostOpen.value = true;
  }

  async function saveHost() {
    if (hostValueError.value) return;
    saving.value = true;
    hostError.value = '';
    try {
      const { data } = await platformServicesService.replaceHost(hostValue.value.trim());
      services.value = data;
      hostOpen.value = false;
      notification.success('Host actualizado en todos los servicios');
    } catch (err) {
      hostError.value = getErrorMessage(err, 'No se pudo cambiar el host');
    } finally {
      saving.value = false;
    }
  }

  onMounted(load);
</script>
