<template>
  <BaseModal
    :model-value="modelValue"
    title="Invitar evaluador interno"
    size="lg"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <form class="space-y-4" data-testid="invite-form" @submit.prevent="submit">
      <p class="text-sm text-slate-600 dark:text-slate-300">
        Recibirá un correo para crear su contraseña y entrar a PRISMA.
      </p>

      <BaseAlert v-if="emailConfigured === false" variant="warning" data-testid="invite-no-email">
        PRISMA todavía no tiene un servidor de correo, así que la invitación no puede enviarse.
        {{
          isPlatformAdmin
            ? 'Configuralo en Configuración › Correo electrónico'
            : 'Pedile al administrador que lo configure'
        }}, o creá el usuario con contraseña temporal desde «Nuevo Usuario».
      </BaseAlert>

      <BaseInput
        v-model="form.email"
        label="Email"
        type="email"
        placeholder="usuario@organizacion.uy"
        :error="errors.email"
        required
      />
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <BaseInput
          v-model="form.firstName"
          label="Nombre"
          placeholder="Nombre"
          :error="errors.firstName"
          required
        />
        <BaseInput
          v-model="form.lastName"
          label="Apellido"
          placeholder="Apellido"
          :error="errors.lastName"
          required
        />
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div
          class="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-3 dark:border-slate-600 dark:bg-slate-800"
        >
          <p
            class="text-[11px] font-bold tracking-wide text-slate-500 uppercase dark:text-slate-400"
          >
            Rol
          </p>
          <p class="mt-1 text-sm font-semibold text-slate-900 dark:text-white">Evaluador interno</p>
          <p class="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
            Carga respuestas y evidencias
          </p>
        </div>
        <div
          v-if="!isPlatformAdmin"
          class="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-3 dark:border-slate-600 dark:bg-slate-800"
          data-testid="invite-own-organization"
        >
          <p
            class="text-[11px] font-bold tracking-wide text-slate-500 uppercase dark:text-slate-400"
          >
            Organización
          </p>
          <p class="mt-1 text-sm font-semibold text-slate-900 dark:text-white">
            {{ ownOrganizationName || 'Tu organización' }}
          </p>
          <p class="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
            Solo verá los datos de esta organización
          </p>
        </div>
        <div v-else>
          <BaseMultiSelect
            v-model="form.organizationIds"
            label="Organización"
            placeholder="Buscar y elegir la organización…"
            :options="organizationOptions"
            :max="1"
            :error="errors.organizationIds"
          />
          <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">
            Solo verá los datos de esta organización
          </p>
        </div>
      </div>

      <div
        class="rounded-lg border border-blue-200 bg-blue-50 px-3.5 py-3 text-sm text-blue-900 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-200"
      >
        <p class="font-semibold">Qué pasa al enviar</p>
        <ol class="mt-1 list-decimal space-y-0.5 pl-5">
          <li>Se crea la cuenta, todavía sin contraseña.</li>
          <li>
            Le llega un correo con un enlace para
            <strong>verificar su email y crear su contraseña</strong>.
          </li>
          <li>
            El enlace vence en <strong>72 horas</strong>; si vence, podés reenviar la invitación.
            Nadie más conoce su contraseña.
          </li>
        </ol>
      </div>
    </form>
    <template #footer>
      <BaseButton variant="secondary" @click="emit('update:modelValue', false)">
        Cancelar
      </BaseButton>
      <BaseButton
        variant="primary"
        :loading="sending"
        :disabled="emailConfigured === false"
        data-testid="invite-submit"
        @click="submit"
      >
        <EnvelopeIcon class="w-4 h-4" />
        Enviar invitación
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
  import { reactive, ref, watch } from 'vue';
  import { usersService } from '@/services/resources';
  import { useNotification } from '@/composables/useUtils';
  import { getErrorMessage } from '@/utils/helpers';
  import { emailSchema, personNameSchema } from '@/utils/validators';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseMultiSelect from '@/components/common/BaseMultiSelect.vue';
  import { EnvelopeIcon } from '@heroicons/vue/24/outline';
  import type { User } from '@/types';

  // RF-USR-05 (PRI-240): invitación por correo de un evaluador interno. El rol y, para un
  // responsable, la organización los fija el backend (UserService.invite); acá solo se muestran.
  const props = defineProps<{
    modelValue: boolean;
    isPlatformAdmin: boolean;
    /** Nombre de la organización del responsable (no aplica al administrador). */
    ownOrganizationName?: string;
    /** Organizaciones activas que puede elegir el administrador. */
    organizationOptions: { value: string; label: string }[];
    /** null = no se sabe (no se pudo consultar): se deja intentar y el backend decide. */
    emailConfigured: boolean | null;
  }>();

  const emit = defineEmits<{
    'update:modelValue': [value: boolean];
    invited: [user: User];
  }>();

  const notification = useNotification();
  const sending = ref(false);
  const form = reactive({
    email: '',
    firstName: '',
    lastName: '',
    organizationIds: [] as string[],
  });
  const errors = reactive({ email: '', firstName: '', lastName: '', organizationIds: '' });

  watch(
    () => props.modelValue,
    (open) => {
      if (!open) return;
      form.email = '';
      form.firstName = '';
      form.lastName = '';
      form.organizationIds = [];
      Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    },
  );

  async function submit() {
    if (props.emailConfigured === false || sending.value) return;
    Object.keys(errors).forEach((k) => (errors[k as keyof typeof errors] = ''));
    const email = emailSchema.safeParse(form.email);
    const first = personNameSchema('nombre').safeParse(form.firstName);
    const last = personNameSchema('apellido').safeParse(form.lastName);
    if (!email.success) errors.email = email.error.issues[0]!.message;
    if (!first.success) errors.firstName = first.error.issues[0]!.message;
    if (!last.success) errors.lastName = last.error.issues[0]!.message;
    if (props.isPlatformAdmin && form.organizationIds.length !== 1) {
      errors.organizationIds = 'Elegí la organización a la que se invita';
    }
    if (Object.values(errors).some(Boolean)) return;

    sending.value = true;
    try {
      const { data } = await usersService.invite({
        email: form.email.trim(),
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        ...(props.isPlatformAdmin ? { organizationId: form.organizationIds[0] } : {}),
      });
      emit('invited', data);
      emit('update:modelValue', false);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo enviar la invitación'));
    } finally {
      sending.value = false;
    }
  }
</script>
