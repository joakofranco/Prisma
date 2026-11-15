import type { AuditVerdict, MaturityCatalog } from '@/types';

export const VERDICT_LABELS: Record<AuditVerdict, string> = {
  VALIDATED: 'Validado',
  NOT_VALIDATED: 'No validado',
  NEEDS_MORE_EVIDENCE: 'Requiere más evidencia',
};

export const VERDICT_OPTIONS = (Object.keys(VERDICT_LABELS) as AuditVerdict[]).map((value) => ({
  value,
  label: VERDICT_LABELS[value],
}));

export interface VerdictControl {
  id: string;
  code: string;
  description: string;
}

/** Controles del catálogo (ya podado por el perfil comunitario), sin repetir los que aparecen en
 * más de una subcategoría. */
export function flattenControls(catalog: MaturityCatalog): VerdictControl[] {
  const seen = new Set<string>();
  const out: VerdictControl[] = [];
  for (const fn of catalog.functions ?? []) {
    for (const category of fn.categories ?? []) {
      for (const subcategory of category.subcategories ?? []) {
        for (const requirement of subcategory.requirements ?? []) {
          for (const control of requirement.controls ?? []) {
            if (seen.has(control.id)) continue;
            seen.add(control.id);
            out.push({ id: control.id, code: control.code, description: control.description });
          }
        }
      }
    }
  }
  return out;
}

/** Filtra por texto (código o descripción) y por dictamen: 'ALL', 'NONE' (sin dictamen) o un valor. */
export function filterControls(
  controls: VerdictControl[],
  verdicts: Record<string, AuditVerdict>,
  search: string,
  filter: 'ALL' | 'NONE' | AuditVerdict,
): VerdictControl[] {
  const q = search.trim().toLowerCase();
  return controls.filter((c) => {
    if (q && !`${c.code} ${c.description}`.toLowerCase().includes(q)) return false;
    if (filter === 'ALL') return true;
    if (filter === 'NONE') return !verdicts[c.id];
    return verdicts[c.id] === filter;
  });
}

export function countByVerdict(
  controls: VerdictControl[],
  verdicts: Record<string, AuditVerdict>,
): Record<AuditVerdict | 'NONE', number> {
  const counts = { VALIDATED: 0, NOT_VALIDATED: 0, NEEDS_MORE_EVIDENCE: 0, NONE: 0 };
  for (const c of controls) counts[verdicts[c.id] ?? 'NONE']++;
  return counts;
}
