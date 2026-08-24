import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { User } from '@/types';
import { usersService } from '@/services/resources';

export const useUsersStore = defineStore('users', () => {
  const users = ref<User[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const currentPage = ref(1);
  const search = ref('');

  async function fetchUsers(page = 1, pageSize = 10, searchTerm = '') {
    loading.value = true;
    try {
      const { data } = await usersService.getAll({
        page,
        pageSize,
        search: searchTerm || undefined,
      });
      users.value = data.data;
      total.value = data.total;
      currentPage.value = page;
      search.value = searchTerm;
    } catch (err) {
      console.error('Error fetching users:', err);
      throw err;
    } finally {
      loading.value = false;
    }
  }

  async function createUser(userData: Partial<User> & { password?: string }) {
    const { data } = await usersService.create(userData);
    users.value.unshift(data);
    total.value++;
    return data;
  }

  async function updateUser(id: string, userData: Partial<User>) {
    const { data } = await usersService.update(id, userData);
    const index = users.value.findIndex((u) => u.id === id);
    if (index !== -1) users.value[index] = data;
    return data;
  }

  async function deleteUser(id: string) {
    await usersService.delete(id);
    users.value = users.value.filter((u) => u.id !== id);
    total.value--;
  }

  return {
    users,
    total,
    loading,
    currentPage,
    search,
    fetchUsers,
    createUser,
    updateUser,
    deleteUser,
  };
});
