import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { Organization } from '@/types';
import { organizationsService } from '@/services/resources';

export const useOrganizationsStore = defineStore('organizations', () => {
  const organizations = ref<Organization[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const currentPage = ref(1);
  const search = ref('');

  async function fetchOrganizations(page = 1, pageSize = 10, searchTerm = '') {
    loading.value = true;
    try {
      const { data } = await organizationsService.getAll({
        page,
        pageSize,
        search: searchTerm || undefined,
      });
      organizations.value = data.data;
      total.value = data.total;
      currentPage.value = page;
      search.value = searchTerm;
    } catch (err) {
      console.error('Error fetching organizations:', err);
      throw err;
    } finally {
      loading.value = false;
    }
  }

  async function createOrganization(org: Partial<Organization>) {
    const { data } = await organizationsService.create(org);
    organizations.value.unshift(data);
    total.value++;
    return data;
  }

  async function updateOrganization(id: string, org: Partial<Organization>) {
    const { data } = await organizationsService.update(id, org);
    const index = organizations.value.findIndex((o) => o.id === id);
    if (index !== -1) organizations.value[index] = data;
    return data;
  }

  async function deleteOrganization(id: string) {
    await organizationsService.delete(id);
    organizations.value = organizations.value.filter((o) => o.id !== id);
    total.value--;
  }

  return {
    organizations,
    total,
    loading,
    currentPage,
    search,
    fetchOrganizations,
    createOrganization,
    updateOrganization,
    deleteOrganization,
  };
});
