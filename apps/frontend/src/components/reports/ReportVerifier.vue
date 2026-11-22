<template>
  <section
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5"
    aria-labelledby="verifier-title"
    data-test="verifier"
  >
    <div class="flex items-start gap-3">
      <ShieldCheckIcon
        class="w-6 h-6 shrink-0 text-blue-700 dark:text-blue-400"
        aria-hidden="true"
      />
      <div>
        <h2 id="verifier-title" class="text-base font-semibold text-slate-900 dark:text-white">
          Verificar un documento
        </h2>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
          Subí un PDF descargado de PRISMA para comprobar que fue sellado por la plataforma, que lo
          firmó el usuario que lo generó y que no se modificó después.
        </p>
      </div>
    </div>

    <div class="mt-4 flex flex-col sm:flex-row gap-3 sm:items-center">
      <label for="verifier-file" class="sr-only">PDF a verificar</label>
      <input
        id="verifier-file"
        ref="fileInput"
        type="file"
        accept="application/pdf,.pdf"
        class="block flex-1 text-sm text-slate-700 dark:text-slate-200 file:mr-3 file:py-2 file:px-3 file:rounded-lg file:border-0 file:bg-slate-100 file:text-slate-700 dark:file:bg-slate-700 dark:file:text-slate-200"
        data-test="verifier-file"
        @change="onPick"
      />
      <BaseButton
        variant="primary"
        :loading="verifying"
        :disabled="!file"
        data-test="verifier-run"
        @click="run"
      >
        Verificar
      </BaseButton>
    </div>

    <p
      v-if="errorMessage"
      class="mt-3 text-sm text-red-700 dark:text-red-400"
      role="alert"
      data-test="verifier-error"
    >
      {{ errorMessage }}
    </p>

    <div v-if="result" class="mt-4 space-y-4" data-test="verifier-result">
      <div
        :class="[
          'px-4 py-3 rounded-lg border text-sm font-medium',
          result.valid
            ? 'bg-emerald-50 border-emerald-200 text-emerald-900 dark:bg-emerald-950 dark:border-emerald-800 dark:text-emerald-200'
            : 'bg-red-50 border-red-200 text-red-900 dark:bg-red-950 dark:border-red-800 dark:text-red-200',
        ]"
        role="status"
        data-test="verifier-verdict"
      >
        {{ result.valid ? 'Documento válido. ' : 'Documento no válido. ' }}{{ result.message }}
      </div>

      <ul class="grid grid-cols-1 md:grid-cols-2 gap-2" data-test="verifier-checks">
        <li
          v-for="check in checks"
          :key="check.key"
          class="flex items-start gap-2 text-sm rounded-lg bg-slate-50 dark:bg-slate-900 px-3 py-2"
          :data-test="`check-${check.key}`"
          :data-ok="check.ok"
        >
          <component
            :is="check.ok ? CheckCircleIcon : XCircleIcon"
            :class="[
              'w-5 h-5 shrink-0',
              check.ok
                ? 'text-emerald-600 dark:text-emerald-400'
                : 'text-red-600 dark:text-red-400',
            ]"
            aria-hidden="true"
          />
          <span class="text-slate-800 dark:text-slate-100">
            <span class="font-medium">{{ check.label }}</span>
            <span class="sr-only">{{ check.ok ? ': sí' : ': no' }}</span>
            <span v-if="check.detail" class="block text-xs text-slate-500 dark:text-slate-400">
              {{ check.detail }}
            </span>
          </span>
        </li>
      </ul>

      <div v-if="result.signatures.length > 0">
        <h3 class="text-xs font-semibold text-slate-600 dark:text-slate-300 mb-2">
          Firmas del documento
        </h3>
        <ol class="space-y-1" data-test="verifier-signatures">
          <li
            v-for="(signature, index) in result.signatures"
            :key="index"
            class="text-xs text-slate-600 dark:text-slate-300"
            data-test="verifier-signature"
          >
            {{ index + 1 }}. {{ roleLabel(signature.role) }}: {{ signature.signerName }}
            <template v-if="signature.signerEmail"> ({{ signature.signerEmail }})</template>
            <template v-if="signature.signedAt">
              · {{ formatDateTime(signature.signedAt) }}</template
            >
            · {{ signature.integrityValid && signature.trusted ? 'válida' : 'no válida' }}
          </li>
        </ol>
      </div>

      <p class="text-xs text-slate-500 dark:text-slate-400 break-all">
        Huella SHA-256: <span class="font-mono" data-test="verifier-hash">{{ result.sha256 }}</span>
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue';
  import { CheckCircleIcon, ShieldCheckIcon, XCircleIcon } from '@heroicons/vue/24/outline';
  import BaseButton from '@/components/common/BaseButton.vue';
  import { reportsService } from '@/services/resources';
  import { formatDateTime, getErrorMessage } from '@/utils/helpers';
  import type { ReportSignature, ReportVerification } from '@/types';

  const MAX_BYTES = 50 * 1024 * 1024;

  const fileInput = ref<HTMLInputElement | null>(null);
  const file = ref<File | null>(null);
  const verifying = ref(false);
  const result = ref<ReportVerification | null>(null);
  const errorMessage = ref('');

  function onPick(event: Event) {
    const picked = (event.target as HTMLInputElement).files?.[0] ?? null;
    file.value = picked;
    result.value = null;
    errorMessage.value = '';
  }

  function roleLabel(role: ReportSignature['role']) {
    if (role === 'PRISMA') return 'Sellado de PRISMA';
    return role === 'USER' ? 'Usuario' : 'Firmante desconocido';
  }

  const userSignature = computed(() => result.value?.signatures.find((s) => s.role === 'USER'));

  const checks = computed(() => {
    const r = result.value;
    if (!r) return [];
    return [
      {
        key: 'prisma',
        label: 'Sellado por PRISMA',
        ok: r.sealedByPrisma,
        detail: '',
      },
      {
        key: 'user',
        label: 'Firmado por el usuario',
        ok: r.signedByUser,
        detail: userSignature.value
          ? `${userSignature.value.signerName}${
              userSignature.value.signerEmail ? ` (${userSignature.value.signerEmail})` : ''
            }`
          : '',
      },
      {
        key: 'intact',
        label: 'Sin modificaciones posteriores',
        ok: r.intact,
        detail: '',
      },
      {
        key: 'registered',
        label: 'Emisión registrada en PRISMA',
        ok: r.registered && r.userMatchesRegistry,
        detail: r.registeredAt ? `Registrada el ${formatDateTime(r.registeredAt)}` : '',
      },
    ];
  });

  async function run() {
    if (!file.value) return;
    if (file.value.size > MAX_BYTES) {
      errorMessage.value = 'El PDF supera los 50 MB.';
      return;
    }
    verifying.value = true;
    errorMessage.value = '';
    result.value = null;
    try {
      const { data } = await reportsService.verify(file.value);
      result.value = data;
    } catch (error) {
      errorMessage.value = getErrorMessage(error, 'No se pudo verificar el documento');
    } finally {
      verifying.value = false;
    }
  }
</script>
