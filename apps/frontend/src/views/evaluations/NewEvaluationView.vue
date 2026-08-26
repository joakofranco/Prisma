<template>
  <div class="space-y-6">
    <div class="flex items-center gap-3">
      <router-link
        to="/evaluations"
        class="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400"
        title="Volver a evaluaciones"
      >
        <ArrowLeftIcon class="w-5 h-5" />
      </router-link>
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Nueva Evaluación</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Crear una nueva evaluación de madurez</p>
      </div>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 max-w-2xl">
      <form class="space-y-5" @submit.prevent="handleSubmit">
        <BaseInput
          v-model="form.name"
          label="Nombre de la Evaluación"
          placeholder="Ej: Evaluación Anual 2026"
          :error="errors.name"
          required
        />

        <BaseSelect
          v-model="form.organizationId"
          label="Organización"
          :options="orgOptions"
          placeholder="Seleccione una organización"
          :error="errors.organizationId"
          required
        />

        <BaseSelect
          v-model="form.catalogVersion"
          label="Versión del Catálogo MCU"
          :options="catalogOptions"
          placeholder="Seleccione la versión"
          :error="errors.catalogVersion"
          required
        />

        <div>
          <BaseSelect
            v-model="form.communityProfileId"
            label="Perfil Comunitario"
            :options="profileOptions"
            placeholder="Catálogo completo (todos los controles)"
          />
          <p class="mt-1 text-xs text-slate-400">
            Opcional: acota la evaluación a los controles de un perfil comunitario (p.ej. Gobierno,
            PYME) en vez del catálogo completo. Se aplica a toda la evaluación y auditoría
            posterior.
          </p>
        </div>

        <div class="flex items-center justify-end gap-3 pt-4 border-t border-slate-200 dark:border-slate-700">
          <BaseButton variant="secondary" type="button" @click="router.push('/evaluations')">
            Cancelar
          </BaseButton>
          <BaseButton variant="primary" type="submit" :loading="saving">
            Crear Evaluación
          </BaseButton>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, watch, onMounted } from 'vue';
  import { useRouter, useRoute } from 'vue-router';
  import { useEvaluationsStore } from '@/stores/evaluations';
  import { useOrganizationsStore } from '@/stores/organizations';
  import { catalogService, communityProfilesService } from '@/services/resources';
  import { useNotification } from '@/composables/useUtils';
  import { getErrorMessage } from '@/utils/helpers';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import { ArrowLeftIcon } from '@heroicons/vue/24/outline';

  const router = useRouter();
  const route = useRoute();
  const evalStore = useEvaluationsStore();
  const orgStore = useOrganizationsStore();
  const notification = useNotification();

  const saving = ref(false);

  const form = reactive({
    name: '',
    organizationId: (route.query.org as string) || '',
    catalogVersion: '',
    communityProfileId: '',
  });

  const errors = reactive({
    name: '',
    organizationId: '',
    catalogVersion: '',
  });

  const orgOptions = ref<{ value: string; label: string }[]>([]);
  const catalogOptions = ref<{ value: string; label: string }[]>([]);
  const profileOptions = ref<{ value: string; label: string }[]>([]);

  // Los perfiles son específicos de una versión de catálogo: si el usuario cambia la versión,
  // hay que recargar la lista (y el perfil que tenía elegido puede ya no aplicar).
  watch(
    () => form.catalogVersion,
    async (version) => {
      form.communityProfileId = '';
      profileOptions.value = [];
      if (!version) return;
      try {
        const { data } = await communityProfilesService.getAll(version);
        profileOptions.value = data.map((p) => ({
          value: p.id,
          label: `${p.name} (${p.controlCount} controles)`,
        }));
      } catch (err) {
        console.error(err);
      }
    },
  );

  async function handleSubmit() {
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    if (!form.name) {
      errors.name = 'El nombre es requerido';
      return;
    }
    if (!form.organizationId) {
      errors.organizationId = 'Seleccione una organización';
      return;
    }
    if (!form.catalogVersion) {
      errors.catalogVersion = 'Seleccione una versión';
      return;
    }

    saving.value = true;
    try {
      const eval_ = await evalStore.createEvaluation({
        name: form.name,
        organizationId: form.organizationId,
        catalogVersion: form.catalogVersion,
        communityProfileId: form.communityProfileId || null,
      });
      notification.success('Evaluación creada correctamente');
      router.push(`/evaluations/${eval_.id}`);
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al crear la evaluación'));
    } finally {
      saving.value = false;
    }
  }

  onMounted(async () => {
    // Cada fetch se maneja de forma independiente: si falla la carga de organizaciones (o de
    // versiones del catálogo), no debe impedir que el otro selector se popule. Antes, un error
    // sin capturar en fetchOrganizations cortaba la función y el select de versión del catálogo
    // quedaba siempre vacío, incluso el fallback.
    try {
      await orgStore.fetchOrganizations(1, 100);
      orgOptions.value = orgStore.organizations.map((o) => ({ value: o.id, label: o.name }));
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al cargar las organizaciones'));
    }

    try {
      const { data } = await catalogService.getVersions();
      catalogOptions.value = data.versions.map((v) => ({ value: v, label: v }));
    } catch {
      catalogOptions.value = [{ value: '5.0', label: 'MCU 5.0' }];
    }
  });
</script>
