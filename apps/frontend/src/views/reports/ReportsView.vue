<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Reportes</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
        Descargá el reporte de cualquier evaluación aprobada o archivada, en el formato que
        necesites.
      </p>
    </div>

    <div class="flex flex-col sm:flex-row gap-3 sm:items-center">
      <div class="relative flex-1 sm:max-w-md">
        <label for="reports-search" class="sr-only">Buscar organización o evaluación</label>
        <MagnifyingGlassIcon
          class="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-500 dark:text-slate-400"
          aria-hidden="true"
        />
        <input
          id="reports-search"
          v-model="search"
          type="search"
          placeholder="Buscar organización o evaluación"
          class="w-full h-10 pl-9 pr-3 rounded-lg border border-slate-300 dark:border-slate-500 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white placeholder:text-slate-500 dark:placeholder:text-slate-300 focus:outline-none focus:ring-2 focus:ring-blue-500"
          data-testid="reports-search"
        />
      </div>
      <div
        class="inline-flex gap-1 p-1 rounded-lg bg-slate-200 dark:bg-slate-700 self-start"
        role="group"
        aria-label="Filtrar por estado"
      >
        <button
          v-for="f in filters"
          :key="f.value"
          type="button"
          :aria-pressed="statusFilter === f.value"
          :class="[
            'h-8 px-3 rounded-md text-sm transition-colors',
            statusFilter === f.value
              ? 'bg-white dark:bg-slate-900 text-slate-900 dark:text-white font-semibold shadow-sm'
              : 'text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white',
          ]"
          :data-testid="`filter-${f.value}`"
          @click="statusFilter = f.value"
        >
          {{ f.label }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <template v-else>
      <div
        v-if="groups.length === 0"
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-10 text-center text-sm text-slate-500 dark:text-slate-400"
        data-testid="reports-empty"
      >
        {{
          reportable.length === 0
            ? 'No hay evaluaciones aprobadas ni archivadas con reporte disponible.'
            : 'Ninguna evaluación coincide con la búsqueda o el filtro.'
        }}
      </div>

      <section
        v-for="group in groups"
        :key="group.key"
        :aria-labelledby="`org-${group.key}`"
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700"
        data-testid="org-group"
      >
        <div
          class="flex items-center gap-4 px-5 py-4 border-b border-slate-200 dark:border-slate-700"
        >
          <span
            class="w-11 h-11 shrink-0 rounded-lg bg-blue-50 dark:bg-blue-950 text-blue-700 dark:text-blue-300 flex items-center justify-center font-bold"
            aria-hidden="true"
          >
            {{ group.initials }}
          </span>
          <div class="flex-1 min-w-0">
            <h2
              :id="`org-${group.key}`"
              class="text-base font-bold text-slate-900 dark:text-white truncate"
            >
              {{ group.name }}
            </h2>
            <p
              v-if="group.details"
              class="text-sm text-slate-500 dark:text-slate-400 mt-0.5"
              data-testid="org-details"
            >
              {{ group.details }}
            </p>
          </div>
          <span class="hidden sm:block text-sm text-slate-500 dark:text-slate-400 shrink-0">
            {{ group.evaluations.length }}
            {{ group.evaluations.length === 1 ? 'reporte disponible' : 'reportes disponibles' }}
          </span>
        </div>

        <ul class="divide-y divide-slate-100 dark:divide-slate-700">
          <li
            v-for="ev in group.evaluations"
            :key="ev.id"
            class="grid grid-cols-1 md:grid-cols-[minmax(0,2fr)_auto_minmax(0,1fr)_minmax(0,1fr)_auto] gap-2 md:gap-4 items-center px-5 py-3"
            data-testid="report-row"
          >
            <span class="text-sm font-semibold text-slate-900 dark:text-white">{{ ev.name }}</span>
            <span>
              <span
                class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium badge-themed"
                :style="getStatusBadgeStyle(ev.status)"
              >
                {{ getStatusLabel(ev.status) }}
              </span>
            </span>
            <span class="text-sm text-slate-600 dark:text-slate-300">
              {{ ev.approvedAt ? `Aprobada el ${formatDate(ev.approvedAt)}` : '' }}
            </span>
            <span class="text-sm text-slate-600 dark:text-slate-300">
              Madurez: {{ ev.globalMaturity ? `Nivel ${ev.globalMaturity}` : 'sin nivel' }}
            </span>

            <div class="relative justify-self-start md:justify-self-end">
              <button
                type="button"
                :class="[
                  'h-9 px-4 inline-flex items-center gap-1.5 rounded-lg text-sm font-semibold transition-colors',
                  openMenu === ev.id
                    ? 'bg-blue-600 text-white'
                    : 'border border-slate-300 dark:border-slate-500 text-slate-900 dark:text-white hover:bg-slate-50 dark:hover:bg-slate-700',
                ]"
                aria-haspopup="menu"
                :aria-expanded="openMenu === ev.id"
                :aria-controls="`menu-${ev.id}`"
                :disabled="downloading === ev.id"
                data-testid="download-button"
                @click.stop="toggleMenu(ev.id)"
              >
                <ArrowDownTrayIcon class="w-4 h-4" aria-hidden="true" />
                {{ downloading === ev.id ? 'Descargando…' : 'Descargar' }}
                <ChevronDownIcon class="w-4 h-4" aria-hidden="true" />
              </button>

              <div
                v-if="openMenu === ev.id"
                :id="`menu-${ev.id}`"
                role="menu"
                :aria-label="`Formatos de ${ev.name}`"
                class="absolute right-0 z-20 mt-2 w-80 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-xl p-1.5"
                data-testid="formats-menu"
                @click.stop
                @keydown.esc="openMenu = null"
              >
                <p class="px-3 pt-2 pb-1 text-xs font-semibold text-slate-500 dark:text-slate-400">
                  {{ ev.name }} · {{ getStatusLabel(ev.status) }}
                </p>
                <button
                  v-for="format in formats"
                  :key="format.key"
                  type="button"
                  role="menuitem"
                  class="w-full flex items-start gap-3 p-3 rounded-lg text-left hover:bg-slate-50 dark:hover:bg-slate-700 focus:bg-slate-50 dark:focus:bg-slate-700 focus:outline-none"
                  :data-testid="`format-${format.key}`"
                  @click="download(ev, format)"
                >
                  <span
                    :class="[
                      'w-10 h-9 shrink-0 rounded-md flex items-center justify-center text-[11px] font-bold',
                      format.badgeClass,
                    ]"
                    aria-hidden="true"
                  >
                    {{ format.badge }}
                  </span>
                  <span class="flex flex-col">
                    <span class="text-sm font-semibold text-slate-900 dark:text-white">
                      {{ format.label }}
                    </span>
                    <span class="text-xs text-slate-500 dark:text-slate-400">
                      {{ format.hint }}
                    </span>
                  </span>
                </button>
                <p
                  class="px-3 pt-2 pb-1 mt-1 border-t border-slate-100 dark:border-slate-700 text-xs text-slate-500 dark:text-slate-400"
                >
                  Descargar no modifica la evaluación.
                </p>
              </div>
            </div>
          </li>
        </ul>
      </section>

      <p v-if="reportable.length > 0" class="text-xs text-slate-500 dark:text-slate-400">
        Las evaluaciones descartadas sin aprobar no generan reportes.
      </p>
    </template>

    <section
      v-if="inAuditEvaluations.length > 0"
      aria-labelledby="preliminary-title"
      class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
    >
      <h2 id="preliminary-title" class="text-lg font-semibold text-slate-900 dark:text-white mb-1">
        Informe Preliminar de Auditoría
      </h2>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
        Hallazgos, observaciones y evidencias faltantes de evaluaciones en auditoría, antes de
        aprobar.
      </p>
      <div class="space-y-3">
        <div
          v-for="eval_ in inAuditEvaluations"
          :key="eval_.id"
          class="flex items-center justify-between p-4 bg-slate-50 dark:bg-slate-900 rounded-lg"
        >
          <div>
            <p class="text-sm font-medium text-slate-900 dark:text-white">
              {{ eval_.name }}
            </p>
            <p class="text-xs text-slate-500 dark:text-slate-400">
              {{ eval_.organizationName }} — {{ formatDate(eval_.createdAt) }}
            </p>
          </div>
          <div class="flex gap-2">
            <BaseButton
              variant="ghost"
              size="sm"
              :loading="generatingPreliminaryPdf === eval_.id"
              @click="generatePreliminaryPDF(eval_.id)"
            >
              <ArrowDownTrayIcon class="w-4 h-4" /> PDF
            </BaseButton>
            <BaseButton
              variant="ghost"
              size="sm"
              :loading="generatingPreliminaryExcel === eval_.id"
              @click="generatePreliminaryExcel(eval_.id)"
            >
              <DocumentTextIcon class="w-4 h-4" /> Excel
            </BaseButton>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
  import { evaluationsService, organizationsService, reportsService } from '@/services/resources';
  import {
    formatDate,
    getErrorMessage,
    getStatusBadgeStyle,
    getStatusLabel,
  } from '@/utils/helpers';
  import { saveBlob } from '@/utils/files';
  import { useNotification } from '@/composables/useUtils';
  import BaseButton from '@/components/common/BaseButton.vue';
  import {
    ArrowDownTrayIcon,
    ChevronDownIcon,
    DocumentTextIcon,
    MagnifyingGlassIcon,
  } from '@heroicons/vue/24/outline';
  import type { Evaluation, Organization } from '@/types';

  type StatusFilter = 'ALL' | 'APPROVED' | 'ARCHIVED';

  interface ReportFormat {
    key: 'executive' | 'pdf' | 'excel' | 'technical';
    label: string;
    hint: string;
    badge: string;
    badgeClass: string;
    run: (id: string) => Promise<{ data: Blob }>;
    fileName: (id: string) => string;
    mime: string;
    success: string;
    error: string;
  }

  const notification = useNotification();

  const loading = ref(true);
  const evaluations = ref<Evaluation[]>([]);
  const organizations = ref<Map<string, Organization>>(new Map());
  const inAuditEvaluations = ref<Evaluation[]>([]);
  const search = ref('');
  const statusFilter = ref<StatusFilter>('ALL');
  const openMenu = ref<string | null>(null);
  const downloading = ref<string | null>(null);
  const generatingPreliminaryPdf = ref<string | null>(null);
  const generatingPreliminaryExcel = ref<string | null>(null);

  const filters: { value: StatusFilter; label: string }[] = [
    { value: 'ALL', label: 'Todas' },
    { value: 'APPROVED', label: 'Aprobadas' },
    { value: 'ARCHIVED', label: 'Archivadas' },
  ];

  const formats: ReportFormat[] = [
    {
      key: 'executive',
      label: 'Reporte ejecutivo',
      hint: 'Resumen de 1–2 páginas para la gerencia',
      badge: 'PDF',
      badgeClass: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
      run: (id) => reportsService.generateExecutivePDF(id),
      fileName: (id) => `reporte-ejecutivo-${id}.pdf`,
      mime: 'application/pdf',
      success: 'Reporte ejecutivo descargado',
      error: 'Error al generar el reporte ejecutivo',
    },
    {
      key: 'pdf',
      label: 'Reporte completo',
      hint: 'Detalle por subcategoría, listo para imprimir',
      badge: 'PDF',
      badgeClass: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
      run: (id) => reportsService.generatePDF(id),
      fileName: (id) => `reporte-prisma-${id}.pdf`,
      mime: 'application/pdf',
      success: 'Reporte PDF descargado',
      error: 'Error al generar el reporte PDF',
    },
    {
      key: 'excel',
      label: 'Reporte completo',
      hint: 'Planilla Excel para filtrar y analizar',
      badge: 'Excel',
      badgeClass: 'bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300',
      run: (id) => reportsService.generateExcel(id),
      fileName: (id) => `reporte-prisma-${id}.xlsx`,
      mime: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      success: 'Reporte Excel descargado',
      error: 'Error al generar el reporte Excel',
    },
    // RF-REP-02 (PRI-310): desglose control por control.
    {
      key: 'technical',
      label: 'Reporte técnico',
      hint: 'Detalle por control: cumplimiento, veredicto, observaciones y evidencias',
      badge: 'PDF',
      badgeClass: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
      run: (id) => reportsService.generateTechnicalPDF(id),
      fileName: (id) => `reporte-tecnico-${id}.pdf`,
      mime: 'application/pdf',
      success: 'Reporte técnico descargado',
      error: 'Error al generar el reporte técnico',
    },
  ];

  // PRI-367: tienen reporte las aprobadas y las archivadas que fueron aprobadas antes de
  // archivarse (tienen fecha de aprobación). Las descartadas sin aprobar no (mismo criterio que
  // ReportService en el backend).
  const reportable = computed(() =>
    evaluations.value.filter(
      (e) => e.status === 'APPROVED' || (e.status === 'ARCHIVED' && !!e.approvedAt),
    ),
  );

  function initials(name: string) {
    return (
      name
        .split(/\s+/)
        .filter((w) => w.length > 2 || /^[A-ZÁÉÍÓÚÑ]/.test(w))
        .slice(0, 2)
        .map((w) => w[0]?.toUpperCase() ?? '')
        .join('') || name.slice(0, 2).toUpperCase()
    );
  }

  function orgDetails(org: Organization | undefined) {
    if (!org) return '';
    return [
      org.rut ? `RUT ${org.rut}` : '',
      org.sector,
      org.size,
      org.responsibleName ? `Responsable: ${org.responsibleName}` : '',
    ]
      .filter(Boolean)
      .join(' · ');
  }

  const groups = computed(() => {
    const term = search.value.trim().toLowerCase();
    const byOrg = new Map<string, Evaluation[]>();
    for (const ev of reportable.value) {
      if (statusFilter.value !== 'ALL' && ev.status !== statusFilter.value) continue;
      const orgName = ev.organizationName ?? '';
      if (term && !ev.name.toLowerCase().includes(term) && !orgName.toLowerCase().includes(term)) {
        continue;
      }
      const list = byOrg.get(ev.organizationId) ?? [];
      list.push(ev);
      byOrg.set(ev.organizationId, list);
    }
    return [...byOrg.entries()]
      .map(([orgId, list]) => {
        const org = organizations.value.get(orgId);
        const name = org?.name ?? list[0].organizationName ?? 'Organización';
        return {
          key: orgId,
          name,
          initials: initials(name),
          details: orgDetails(org),
          evaluations: [...list].sort((a, b) =>
            (b.approvedAt ?? '').localeCompare(a.approvedAt ?? ''),
          ),
        };
      })
      .sort((a, b) => a.name.localeCompare(b.name, 'es'));
  });

  function toggleMenu(id: string) {
    openMenu.value = openMenu.value === id ? null : id;
  }

  function closeMenu() {
    openMenu.value = null;
  }

  // Con responseType 'blob', el cuerpo de un error también llega como Blob: hay que leerlo para
  // mostrar el mensaje del backend en lugar de uno genérico.
  async function reportErrorMessage(err: unknown, fallback: string): Promise<string> {
    const data = (err as { response?: { data?: unknown } })?.response?.data;
    if (data instanceof Blob) {
      try {
        const parsed = JSON.parse(await data.text()) as { message?: string };
        if (parsed.message) return parsed.message;
      } catch {
        // No era JSON: se usa el mensaje genérico.
      }
      return fallback;
    }
    return getErrorMessage(err, fallback);
  }

  async function download(ev: Evaluation, format: ReportFormat) {
    closeMenu();
    downloading.value = ev.id;
    try {
      const { data } = await format.run(ev.id);
      saveBlob(new Blob([data], { type: format.mime }), format.fileName(ev.id));
      notification.success(format.success);
    } catch (err) {
      notification.error(await reportErrorMessage(err, format.error));
    } finally {
      downloading.value = null;
    }
  }

  async function generatePreliminaryPDF(evaluationId: string) {
    generatingPreliminaryPdf.value = evaluationId;
    try {
      const { data } = await reportsService.generatePreliminaryPDF(evaluationId);
      saveBlob(new Blob([data]), `informe-preliminar-${evaluationId}.pdf`);
      notification.success('Informe preliminar PDF descargado');
    } catch {
      notification.error('Error al generar el informe preliminar PDF');
    } finally {
      generatingPreliminaryPdf.value = null;
    }
  }

  async function generatePreliminaryExcel(evaluationId: string) {
    generatingPreliminaryExcel.value = evaluationId;
    try {
      const { data } = await reportsService.generatePreliminaryExcel(evaluationId);
      saveBlob(new Blob([data]), `informe-preliminar-${evaluationId}.xlsx`);
      notification.success('Informe preliminar Excel descargado');
    } catch {
      notification.error('Error al generar el informe preliminar Excel');
    } finally {
      generatingPreliminaryExcel.value = null;
    }
  }

  async function fetchByStatus(status: string): Promise<Evaluation[]> {
    try {
      const { data } = await evaluationsService.getAll({ page: 1, pageSize: 100, status });
      return data.data;
    } catch (err) {
      console.error(err);
      return [];
    }
  }

  async function fetchOrganizations() {
    // Si no se pueden leer, el listado muestra igual el nombre de cada organización (viene en la
    // evaluación), solo sin RUT / sector / tamaño / responsable.
    try {
      const { data } = await organizationsService.getAll({ page: 1, pageSize: 100 });
      organizations.value = new Map(data.data.map((o) => [o.id, o]));
    } catch (err) {
      console.error(err);
    }
  }

  onMounted(async () => {
    document.addEventListener('click', closeMenu);
    const [approved, archived, inAudit] = await Promise.all([
      fetchByStatus('APPROVED'),
      fetchByStatus('ARCHIVED'),
      fetchByStatus('IN_AUDIT'),
      fetchOrganizations(),
    ]);
    evaluations.value = [...approved, ...archived];
    inAuditEvaluations.value = inAudit;
    loading.value = false;
  });

  onBeforeUnmount(() => {
    document.removeEventListener('click', closeMenu);
  });
</script>
