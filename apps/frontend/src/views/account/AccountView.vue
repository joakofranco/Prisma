<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Mi Cuenta</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Datos de tu cuenta y seguridad</p>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 max-w-lg">
      <div class="flex items-center justify-between mb-1">
        <h2 class="text-lg font-semibold text-slate-900 dark:text-white">Perfil</h2>
        <button
          v-if="!editingProfile"
          type="button"
          class="text-sm text-blue-600 hover:text-blue-700 font-medium"
          @click="startEditingProfile"
        >
          Corregir nombre
        </button>
      </div>

      <form v-if="editingProfile" class="space-y-4 mt-4" @submit.prevent="handleProfileSubmit">
        <div class="grid grid-cols-2 gap-4">
          <BaseInput
            v-model="profileForm.firstName"
            label="Nombre"
            :error="profileErrors.firstName"
            required
          />
          <BaseInput
            v-model="profileForm.lastName"
            label="Apellido"
            :error="profileErrors.lastName"
            required
          />
        </div>
        <div class="flex justify-end gap-3">
          <BaseButton type="button" variant="secondary" @click="editingProfile = false">
            Cancelar
          </BaseButton>
          <BaseButton type="submit" variant="primary" :loading="savingProfile">
            Guardar
          </BaseButton>
        </div>
      </form>

      <dl v-else class="mt-4 space-y-3 text-sm">
        <div class="flex justify-between gap-4">
          <dt class="text-slate-500 dark:text-slate-400">Nombre</dt>
          <dd class="text-slate-900 dark:text-white font-medium">{{ userName }}</dd>
        </div>
        <div class="flex justify-between gap-4">
          <dt class="text-slate-500 dark:text-slate-400">Email</dt>
          <dd class="text-slate-900 dark:text-white font-medium">{{ authStore.user?.email }}</dd>
        </div>
        <div class="flex justify-between gap-4">
          <dt class="text-slate-500 dark:text-slate-400">Roles</dt>
          <dd class="flex flex-wrap justify-end gap-1">
            <span
              v-for="role in authStore.roles"
              :key="role"
              class="inline-block px-2 py-0.5 text-xs font-medium rounded-full bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300"
            >
              {{ ROLE_LABELS[role] || role }}
            </span>
          </dd>
        </div>
      </dl>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 max-w-lg">
      <h2 class="text-lg font-semibold text-slate-900 dark:text-white mb-1">Cambiar contraseña</h2>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1 mb-5">
        Por seguridad, te pedimos tu contraseña actual antes de guardar la nueva.
      </p>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <BaseInput
          v-model="form.currentPassword"
          label="Contraseña actual"
          type="password"
          :error="errors.currentPassword"
          required
        />
        <BaseInput
          v-model="form.newPassword"
          label="Contraseña nueva"
          type="password"
          placeholder="Mínimo 8 caracteres"
          :error="errors.newPassword"
          required
        />
        <BaseInput
          v-model="form.confirmPassword"
          label="Confirmar contraseña nueva"
          type="password"
          :error="errors.confirmPassword"
          required
        />

        <div class="flex justify-end pt-2">
          <BaseButton type="submit" variant="primary" :loading="saving">
            Guardar nueva contraseña
          </BaseButton>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, reactive, ref } from 'vue';
  import { useAuthStore } from '@/stores/auth';
  import { accountService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import { getErrorMessage } from '@/utils/helpers';
  import { ROLE_LABELS } from '@/utils/constants';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseButton from '@/components/common/BaseButton.vue';

  const authStore = useAuthStore();
  const notification = useNotificationStore();

  const userName = computed(() => {
    const user = authStore.user;
    if (!user) return '';
    return `${user.firstName} ${user.lastName}`;
  });

  const editingProfile = ref(false);
  const savingProfile = ref(false);
  const profileForm = reactive({ firstName: '', lastName: '' });
  const profileErrors = reactive({ firstName: '', lastName: '' });

  function startEditingProfile() {
    profileForm.firstName = authStore.user?.firstName || '';
    profileForm.lastName = authStore.user?.lastName || '';
    profileErrors.firstName = '';
    profileErrors.lastName = '';
    editingProfile.value = true;
  }

  async function handleProfileSubmit() {
    profileErrors.firstName = '';
    profileErrors.lastName = '';
    if (!profileForm.firstName.trim()) {
      profileErrors.firstName = 'El nombre es requerido';
      return;
    }
    if (!profileForm.lastName.trim()) {
      profileErrors.lastName = 'El apellido es requerido';
      return;
    }

    savingProfile.value = true;
    try {
      await accountService.updateProfile({
        firstName: profileForm.firstName.trim(),
        lastName: profileForm.lastName.trim(),
      });
      // Ver el comentario en patchProfile(): el JWT no se pone al día solo por guardar --
      // reflejamos el cambio en el estado local para que se vea al toque en esta pantalla.
      authStore.patchProfile(profileForm.firstName.trim(), profileForm.lastName.trim());
      notification.success('Nombre actualizado correctamente');
      editingProfile.value = false;
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo actualizar el nombre'));
    } finally {
      savingProfile.value = false;
    }
  }

  const saving = ref(false);
  const form = reactive({
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  });
  const errors = reactive({
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  });

  function validate(): boolean {
    (Object.keys(errors) as (keyof typeof errors)[]).forEach((k) => (errors[k] = ''));
    let ok = true;
    if (!form.currentPassword) {
      errors.currentPassword = 'Ingresá tu contraseña actual';
      ok = false;
    }
    if (!form.newPassword || form.newPassword.length < 8) {
      errors.newPassword = 'La contraseña nueva debe tener al menos 8 caracteres';
      ok = false;
    }
    if (form.newPassword !== form.confirmPassword) {
      errors.confirmPassword = 'Las contraseñas no coinciden';
      ok = false;
    }
    return ok;
  }

  async function handleSubmit() {
    if (!validate()) return;

    saving.value = true;
    try {
      await accountService.changePassword({
        currentPassword: form.currentPassword,
        newPassword: form.newPassword,
      });
      notification.success('Contraseña actualizada correctamente');
      form.currentPassword = '';
      form.newPassword = '';
      form.confirmPassword = '';
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo cambiar la contraseña'));
    } finally {
      saving.value = false;
    }
  }
</script>
