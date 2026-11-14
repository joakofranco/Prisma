<template>
  <div
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 w-full"
  >
    <div class="flex items-start justify-between gap-4 mb-1">
      <div>
        <h2 class="text-lg font-semibold text-slate-900 dark:text-white">Alertas del sistema</h2>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Correo con el que Alertmanager avisa cuando un servicio cae, se satura o hay indicios de
          un ataque (denegación de servicio, fuerza bruta).
        </p>
      </div>
      <span
        v-if="!loading"
        :class="[
          'shrink-0 inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
          settings.configured
            ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300'
            : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300',
        ]"
      >
        {{ settings.configured ? 'Configurado' : 'Valores iniciales' }}
      </span>
    </div>

    <div v-if="loading" class="py-10 text-center text-sm text-slate-500 dark:text-slate-400">
      Cargando...
    </div>

    <form v-else class="space-y-4 mt-5" @submit.prevent="handleSubmit">
      <BaseAlert v-if="usesTestMailbox" variant="warning" data-testid="alerts-mailpit-warning">
        <strong>Las alertas se están enviando a un buzón de pruebas (Mailpit).</strong> No llegan a
        ningún correo real. Configurá el servidor SMTP de la cuenta del sistema y los destinatarios
        para recibirlas.
      </BaseAlert>
      <BaseAlert variant="info">
        Los correos de cambio y recuperación de contraseña se configuran en la pestaña «Correo
        electrónico». Acá solo se configuran las alertas del sistema.
      </BaseAlert>

      <BaseInput
        id="alert-recipients"
        v-model="form.recipients"
        label="Destinatarios de las alertas"
        placeholder="equipo@miorganizacion.com, ops@miorganizacion.com"
        required
        :error="errors.recipients"
      />
      <p class="text-xs text-slate-500 dark:text-slate-400 -mt-2">
        Hasta 10 direcciones, separadas por coma.
      </p>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div class="sm:col-span-2">
          <BaseInput
            id="alert-host"
            v-model="form.smtpHost"
            label="Servidor SMTP"
            placeholder="smtp.miproveedor.com"
            required
            :error="errors.smtpHost"
          />
        </div>
        <BaseInput
          id="alert-port"
          v-model="form.smtpPort"
          label="Puerto"
          type="number"
          placeholder="587"
          required
          :error="errors.smtpPort"
        />
      </div>

      <BaseInput
        id="alert-from"
        v-model="form.from"
        label="Email remitente"
        type="email"
        placeholder="alertas@miorganizacion.com"
        required
        :error="errors.from"
      />

      <div class="flex flex-wrap gap-6 pt-1">
        <label class="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-200">
          <input
            id="alert-auth"
            v-model="form.authEnabled"
            type="checkbox"
            class="rounded border-slate-300 dark:border-slate-600"
          />
          Requiere autenticación
        </label>
        <label class="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-200">
          <input
            id="alert-tls"
            v-model="form.requireTls"
            type="checkbox"
            class="rounded border-slate-300 dark:border-slate-600"
          />
          Exigir TLS (STARTTLS)
        </label>
      </div>

      <div v-if="form.authEnabled" class="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-1">
        <BaseInput
          id="alert-username"
          v-model="form.username"
          label="Usuario SMTP"
          :error="errors.username"
        />
        <BaseInput
          id="alert-password"
          v-model="form.password"
          label="Contraseña SMTP"
          type="password"
          :placeholder="
            settings.passwordSet
              ? 'Guardada: dejala vacía para conservarla'
              : 'Contraseña de la cuenta'
          "
          :error="errors.password"
        />
      </div>

      <BaseAlert v-if="warning" variant="warning" data-testid="alerts-save-warning">
        {{ warning }}
      </BaseAlert>

      <div class="flex justify-end pt-2">
        <BaseButton type="submit" variant="primary" :loading="saving">
          Guardar configuración
        </BaseButton>
      </div>
    </form>

    <div
      v-if="!loading"
      class="mt-6 pt-5 border-t border-slate-200 dark:border-slate-700 space-y-3"
    >
      <h3 class="text-sm font-semibold text-slate-900 dark:text-white">Enviar alerta de prueba</h3>
      <p class="text-xs text-slate-500 dark:text-slate-400">
        Comprueba la conexión con el servidor SMTP guardado y manda una alerta de prueba a los
        destinatarios guardados. Llega en menos de un minuto. Guardá los cambios antes de probar.
      </p>
      <div class="flex items-center gap-3">
        <BaseButton
          type="button"
          variant="secondary"
          :loading="testing"
          data-testid="alerts-test-button"
          @click="handleTest"
        >
          Enviar alerta de prueba
        </BaseButton>
      </div>
      <BaseAlert
        v-if="testResult"
        :variant="testResult.alertSent && testResult.smtpReachable ? 'success' : 'warning'"
        data-testid="alerts-test-result"
      >
        {{ testResult.message }}
      </BaseAlert>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { alertSettingsService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import { getErrorMessage } from '@/utils/helpers';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import type { AlertSettings, AlertTestResult } from '@/types';

  const notification = useNotificationStore();

  const loading = ref(true);
  const saving = ref(false);
  const testing = ref(false);
  const warning = ref('');
  const testResult = ref<AlertTestResult | null>(null);

  const settings = ref<AlertSettings>({
    recipients: '',
    smtpHost: '',
    smtpPort: 587,
    from: '',
    authEnabled: false,
    username: null,
    passwordSet: false,
    requireTls: false,
    configured: false,
    updatedAt: '',
    warning: null,
  });

  const form = reactive({
    recipients: '',
    smtpHost: '',
    smtpPort: '' as string | number,
    from: '',
    authEnabled: false,
    username: '',
    password: '',
    requireTls: false,
  });

  const errors = reactive({
    recipients: '',
    smtpHost: '',
    smtpPort: '',
    from: '',
    username: '',
    password: '',
  });

  const usesTestMailbox = computed(() => settings.value.smtpHost.toLowerCase() === 'mailpit');

  function applySettings(data: AlertSettings) {
    settings.value = data;
    form.recipients = data.recipients;
    form.smtpHost = data.smtpHost;
    form.smtpPort = data.smtpPort;
    form.from = data.from;
    form.authEnabled = data.authEnabled;
    form.username = data.username || '';
    form.password = '';
    form.requireTls = data.requireTls;
  }

  onMounted(async () => {
    try {
      const { data } = await alertSettingsService.get();
      applySettings(data);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo cargar la configuración de alertas'));
    } finally {
      loading.value = false;
    }
  });

  const EMAIL = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
  const HOST = /^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$|^\[[0-9A-Fa-f:.]+\]$/;

  function validate(): boolean {
    (Object.keys(errors) as (keyof typeof errors)[]).forEach((k) => (errors[k] = ''));
    let ok = true;
    const recipients = form.recipients
      .split(/[,;\s]+/)
      .map((r) => r.trim())
      .filter(Boolean);
    if (recipients.length === 0) {
      errors.recipients = 'Indicá al menos un destinatario';
      ok = false;
    } else if (recipients.length > 10) {
      errors.recipients = 'Máximo 10 destinatarios';
      ok = false;
    } else if (recipients.some((r) => !EMAIL.test(r))) {
      errors.recipients = 'Hay una dirección de correo inválida';
      ok = false;
    }
    if (!HOST.test(form.smtpHost.trim())) {
      errors.smtpHost = 'Indicá solo el nombre del servidor o su IP, sin http:// ni puerto';
      ok = false;
    }
    if (!form.smtpPort || Number(form.smtpPort) < 1 || Number(form.smtpPort) > 65535) {
      errors.smtpPort = 'Puerto inválido';
      ok = false;
    }
    if (!EMAIL.test(form.from.trim())) {
      errors.from = 'El email remitente no es válido';
      ok = false;
    }
    if (form.authEnabled && !form.username.trim()) {
      errors.username = 'El usuario es obligatorio';
      ok = false;
    }
    if (form.authEnabled && !form.password && !settings.value.passwordSet) {
      errors.password = 'La contraseña es obligatoria';
      ok = false;
    }
    return ok;
  }

  async function handleSubmit() {
    if (!validate()) return;
    saving.value = true;
    warning.value = '';
    try {
      const { data } = await alertSettingsService.update({
        recipients: form.recipients.trim(),
        smtpHost: form.smtpHost.trim(),
        smtpPort: Number(form.smtpPort),
        from: form.from.trim(),
        authEnabled: form.authEnabled,
        username: form.authEnabled ? form.username.trim() : null,
        password: form.authEnabled && form.password ? form.password : null,
        requireTls: form.requireTls,
      });
      applySettings(data);
      warning.value = data.warning || '';
      testResult.value = null;
      if (!data.warning) notification.success('Configuración de alertas guardada correctamente');
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo guardar la configuración de alertas'));
    } finally {
      saving.value = false;
    }
  }

  async function handleTest() {
    testing.value = true;
    testResult.value = null;
    try {
      const { data } = await alertSettingsService.test();
      testResult.value = data;
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo enviar la alerta de prueba'));
    } finally {
      testing.value = false;
    }
  }
</script>
