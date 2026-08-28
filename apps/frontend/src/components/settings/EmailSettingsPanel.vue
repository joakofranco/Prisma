<template>
  <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 max-w-2xl">
    <div class="flex items-start justify-between gap-4 mb-1">
      <div>
        <h2 class="text-lg font-semibold text-slate-900 dark:text-white">Correo electrónico (SMTP)</h2>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Servidor que usa Keycloak para enviar el email de "¿Olvidaste tu contraseña?" y demás
          notificaciones de la cuenta.
        </p>
      </div>
      <span
        v-if="!loading"
        :class="[
          'shrink-0 inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
          settings.configured ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400',
        ]"
      >
        {{ settings.configured ? 'Configurado' : 'Sin configurar' }}
      </span>
    </div>

    <div v-if="loading" class="py-10 text-center text-sm text-slate-500 dark:text-slate-400">Cargando...</div>

    <form v-else class="space-y-4 mt-5" @submit.prevent="handleSubmit">
      <BaseAlert variant="info">
        Para probar la configuración: cerrá sesión y hacé clic en "¿Olvidaste tu contraseña?" en la
        pantalla de login, con tu propio email.
      </BaseAlert>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div class="sm:col-span-2">
          <BaseInput
            id="smtp-host"
            v-model="form.host"
            label="Servidor SMTP"
            placeholder="smtp.gmail.com"
            required
            :error="errors.host"
          />
        </div>
        <BaseInput
          id="smtp-port"
          v-model="form.port"
          label="Puerto"
          type="number"
          placeholder="587"
          required
          :error="errors.port"
        />
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <BaseInput
          id="smtp-from"
          v-model="form.from"
          label="Email remitente"
          type="email"
          placeholder="no-reply@miorganizacion.com"
          required
          :error="errors.from"
        />
        <BaseInput
          id="smtp-from-display-name"
          v-model="form.fromDisplayName"
          label="Nombre del remitente (opcional)"
          placeholder="PRISMA"
        />
      </div>

      <div class="flex flex-wrap gap-6 pt-1">
        <label class="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-200">
          <input v-model="form.authEnabled" type="checkbox" class="rounded border-slate-300 dark:border-slate-600" />
          Requiere autenticación
        </label>
        <label class="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-200">
          <input v-model="form.starttls" type="checkbox" class="rounded border-slate-300 dark:border-slate-600" />
          STARTTLS
        </label>
        <label class="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-200">
          <input v-model="form.ssl" type="checkbox" class="rounded border-slate-300 dark:border-slate-600" />
          SSL/TLS
        </label>
      </div>

      <div v-if="form.authEnabled" class="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-1">
        <BaseInput
          id="smtp-username"
          v-model="form.username"
          label="Usuario SMTP"
          :error="errors.username"
        />
        <BaseInput
          id="smtp-password"
          v-model="form.password"
          label="Contraseña SMTP"
          type="password"
          placeholder="Reingresá la contraseña para guardar"
          :error="errors.password"
        />
      </div>
      <p v-if="form.authEnabled" class="text-xs text-slate-500 dark:text-slate-400 -mt-2">
        Por seguridad, Keycloak no permite recuperar la contraseña ya guardada: hay que volver a
        escribirla cada vez que se guarda un cambio acá, aunque no sea lo que se está editando.
      </p>

      <div class="flex justify-end pt-2">
        <BaseButton type="submit" variant="primary" :loading="saving">
          Guardar configuración
        </BaseButton>
      </div>
    </form>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, reactive, ref } from 'vue';
  import { emailSettingsService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import { getErrorMessage } from '@/utils/helpers';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import type { EmailSettings } from '@/types';

  const notification = useNotificationStore();

  const loading = ref(true);
  const saving = ref(false);
  const settings = ref<EmailSettings>({
    host: '',
    port: null,
    from: '',
    fromDisplayName: null,
    authEnabled: false,
    username: null,
    starttls: false,
    ssl: false,
    configured: false,
  });

  const form = reactive({
    host: '',
    port: '' as string | number,
    from: '',
    fromDisplayName: '',
    authEnabled: false,
    username: '',
    password: '',
    starttls: true,
    ssl: false,
  });

  const errors = reactive({ host: '', port: '', from: '', username: '', password: '' });

  function applySettings(data: EmailSettings) {
    settings.value = data;
    form.host = data.host;
    form.port = data.port ?? '';
    form.from = data.from;
    form.fromDisplayName = data.fromDisplayName || '';
    form.authEnabled = data.authEnabled;
    form.username = data.username || '';
    form.password = '';
    form.starttls = data.starttls;
    form.ssl = data.ssl;
  }

  onMounted(async () => {
    try {
      const { data } = await emailSettingsService.get();
      applySettings(data);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo cargar la configuración de email'));
    } finally {
      loading.value = false;
    }
  });

  function validate(): boolean {
    (Object.keys(errors) as (keyof typeof errors)[]).forEach((k) => (errors[k] = ''));
    let ok = true;
    if (!form.host.trim()) {
      errors.host = 'El servidor es obligatorio';
      ok = false;
    }
    if (!form.port || Number(form.port) < 1 || Number(form.port) > 65535) {
      errors.port = 'Puerto inválido';
      ok = false;
    }
    if (!form.from.trim()) {
      errors.from = 'El email remitente es obligatorio';
      ok = false;
    }
    if (form.authEnabled && !form.username.trim()) {
      errors.username = 'El usuario es obligatorio';
      ok = false;
    }
    if (form.authEnabled && !form.password.trim()) {
      errors.password = 'Reingresá la contraseña para guardar';
      ok = false;
    }
    return ok;
  }

  async function handleSubmit() {
    if (!validate()) return;

    saving.value = true;
    try {
      const { data } = await emailSettingsService.update({
        host: form.host.trim(),
        port: Number(form.port),
        from: form.from.trim(),
        fromDisplayName: form.fromDisplayName.trim() || null,
        authEnabled: form.authEnabled,
        username: form.authEnabled ? form.username.trim() : null,
        password: form.authEnabled ? form.password : null,
        starttls: form.starttls,
        ssl: form.ssl,
      });
      applySettings(data);
      notification.success('Configuración de email guardada correctamente');
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo guardar la configuración de email'));
    } finally {
      saving.value = false;
    }
  }
</script>
