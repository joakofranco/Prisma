import type {
  EvaluationOverview,
  MaturityLevel,
  OverviewControl,
  OverviewPriority,
  OverviewSubcategory,
} from '@/types';

export const OVERVIEW_LEVELS: MaturityLevel[] = [1, 2, 3, 4];

export type PriorityFilter = 'ALL' | 'ALTA' | 'MEDIA' | 'BAJA' | 'NONE';

export interface OverviewRow {
  subcategory: OverviewSubcategory;
  functionId: string;
  functionName: string;
  categoryId: string;
  categoryName: string;
  categoryCode: string;
}

export function flattenOverview(overview: EvaluationOverview): OverviewRow[] {
  const rows: OverviewRow[] = [];
  for (const fn of overview.functions) {
    for (const category of fn.categories) {
      for (const subcategory of category.subcategories) {
        rows.push({
          subcategory,
          functionId: fn.id,
          functionName: fn.name,
          categoryId: category.id,
          categoryName: category.name,
          categoryCode: category.code,
        });
      }
    }
  }
  return rows;
}

export function groupControlsByLevel(
  controls: OverviewControl[],
): Record<MaturityLevel, OverviewControl[]> {
  const grouped: Record<MaturityLevel, OverviewControl[]> = { 1: [], 2: [], 3: [], 4: [] };
  for (const control of controls) {
    grouped[control.targetLevel]?.push(control);
  }
  return grouped;
}

export function countControlsByLevel(controls: OverviewControl[]): Record<MaturityLevel, number> {
  const grouped = groupControlsByLevel(controls);
  return {
    1: grouped[1].length,
    2: grouped[2].length,
    3: grouped[3].length,
    4: grouped[4].length,
  };
}

function normalize(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase();
}

function matchesPriority(priority: OverviewPriority, filter: PriorityFilter): boolean {
  if (filter === 'ALL') return true;
  if (filter === 'NONE') return priority === null;
  return priority === filter;
}

function matchesQuery(row: OverviewRow, query: string): boolean {
  if (!query) return true;
  const sub = row.subcategory;
  const haystack = [
    sub.code,
    sub.name,
    sub.description ?? '',
    row.categoryName,
    row.functionName,
    ...sub.requirements.flatMap((r) => [r.code, r.description]),
    ...sub.controls.flatMap((c) => [c.code, c.description]),
  ]
    .map(normalize)
    .join(' ');
  return haystack.includes(query);
}

export function filterOverviewRows(
  rows: OverviewRow[],
  query: string,
  priority: PriorityFilter,
): OverviewRow[] {
  const normalizedQuery = normalize(query.trim());
  return rows.filter(
    (row) =>
      matchesPriority(row.subcategory.priority, priority) && matchesQuery(row, normalizedQuery),
  );
}

export function groupRowsByFunction(
  rows: OverviewRow[],
): { id: string; name: string; rows: OverviewRow[] }[] {
  const groups: { id: string; name: string; rows: OverviewRow[] }[] = [];
  for (const row of rows) {
    let group = groups.find((g) => g.id === row.functionId);
    if (!group) {
      group = { id: row.functionId, name: row.functionName, rows: [] };
      groups.push(group);
    }
    group.rows.push(row);
  }
  return groups;
}

export function priorityLabel(priority: OverviewPriority): string {
  if (priority === 'ALTA') return 'Prioridad Alta';
  if (priority === 'MEDIA') return 'Prioridad Media';
  if (priority === 'BAJA') return 'Prioridad Baja';
  return 'Sin prioridad';
}
