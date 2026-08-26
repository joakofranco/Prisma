import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { Evaluation, EvaluationResponse, MaturityResult } from '@/types';
import { evaluationsService } from '@/services/resources';

export const useEvaluationsStore = defineStore('evaluations', () => {
  const evaluations = ref<Evaluation[]>([]);
  const total = ref(0);
  const loading = ref(false);
  const currentPage = ref(1);
  const currentEvaluation = ref<Evaluation | null>(null);
  const responses = ref<EvaluationResponse[]>([]);
  const results = ref<MaturityResult[]>([]);

  async function fetchEvaluations(
    page = 1,
    pageSize = 10,
    filters?: { status?: string; organizationId?: string },
  ) {
    loading.value = true;
    try {
      const { data } = await evaluationsService.getAll({ page, pageSize, ...filters });
      evaluations.value = data.data;
      total.value = data.total;
      currentPage.value = page;
    } catch (err) {
      console.error('Error fetching evaluations:', err);
      throw err;
    } finally {
      loading.value = false;
    }
  }

  async function fetchEvaluation(id: string) {
    loading.value = true;
    try {
      const { data } = await evaluationsService.getById(id);
      currentEvaluation.value = data;
      return data;
    } catch (err) {
      console.error('Error fetching evaluation:', err);
      throw err;
    } finally {
      loading.value = false;
    }
  }

  async function createEvaluation(evalData: {
    name: string;
    organizationId: string;
    catalogVersion: string;
    communityProfileId?: string | null;
  }) {
    const { data } = await evaluationsService.create(evalData);
    evaluations.value.unshift(data);
    total.value++;
    return data;
  }

  async function updateStatus(id: string, status: string) {
    const { data } = await evaluationsService.updateStatus(id, status);
    const index = evaluations.value.findIndex((e) => e.id === id);
    if (index !== -1) evaluations.value[index] = data;
    if (currentEvaluation.value?.id === id) currentEvaluation.value = data;
    return data;
  }

  async function fetchResponses(evaluationId: string) {
    const { data } = await evaluationsService.getResponses(evaluationId);
    responses.value = data;
    return data;
  }

  async function saveResponse(evaluationId: string, responseData: Partial<EvaluationResponse>) {
    const { data } = await evaluationsService.saveResponse(evaluationId, responseData);
    const index = responses.value.findIndex((r) => r.controlId === responseData.controlId);
    if (index !== -1) {
      responses.value[index] = data;
    } else {
      responses.value.push(data);
    }
    return data;
  }

  async function fetchResults(evaluationId: string) {
    const { data } = await evaluationsService.getResults(evaluationId);
    results.value = data;
    return data;
  }

  async function calculateMaturity(evaluationId: string) {
    await evaluationsService.calculateMaturity(evaluationId);
    await fetchResults(evaluationId);
  }

  async function deleteEvaluation(id: string) {
    await evaluationsService.delete(id);
    evaluations.value = evaluations.value.filter((e) => e.id !== id);
    total.value--;
  }

  return {
    evaluations,
    total,
    loading,
    currentPage,
    currentEvaluation,
    responses,
    results,
    fetchEvaluations,
    fetchEvaluation,
    createEvaluation,
    updateStatus,
    fetchResponses,
    saveResponse,
    fetchResults,
    calculateMaturity,
    deleteEvaluation,
  };
});
