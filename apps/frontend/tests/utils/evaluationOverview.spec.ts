import { describe, it, expect } from 'vitest';
import {
  countControlsByLevel,
  filterOverviewRows,
  flattenOverview,
  groupControlsByLevel,
  groupRowsByFunction,
  priorityLabel,
} from '@/utils/evaluationOverview';
import type { EvaluationOverview, OverviewControl, OverviewPriority } from '@/types';

function control(
  code: string,
  level: 1 | 2 | 3 | 4,
  description = `Desc ${code}`,
): OverviewControl {
  return { id: `id-${code}`, code, description, targetLevel: level, requirementCode: 'AD.1' };
}

function sub(code: string, name: string, priority: OverviewPriority, controls: OverviewControl[]) {
  return {
    id: `sub-${code}`,
    code,
    name,
    description: null,
    priority,
    requirements: [{ id: 'req-1', code: 'AD.1', description: 'Metodología de desarrollo seguro' }],
    controls,
  };
}

const overview: EvaluationOverview = {
  evaluationId: 'eval-1',
  evaluationName: 'Eva1',
  catalogVersion: '5.0',
  catalogLabel: 'MCU 5.0',
  communityProfileId: 'p1',
  communityProfileName: 'Perfil X',
  subcategoryCount: 3,
  requirementCount: 1,
  controlCount: 4,
  priorityCounts: { alta: 1, media: 1, baja: 0, sinPrioridad: 1 },
  functions: [
    {
      id: 'f1',
      code: 'PR',
      name: 'Proteger',
      categories: [
        {
          id: 'c1',
          code: 'PR.PS',
          name: 'Seguridad de los datos',
          subcategories: [
            sub('PR.PS-01', 'Desarrollo seguro', 'ALTA', [
              control('AD.1-1', 1),
              control('AD.1-2', 2),
            ]),
            sub('PR.PS-02', 'Gestión de cambios', 'MEDIA', [control('AD.1-3', 3)]),
          ],
        },
      ],
    },
    {
      id: 'f2',
      code: 'DE',
      name: 'Detectar',
      categories: [
        {
          id: 'c2',
          code: 'DE.CM',
          name: 'Monitoreo',
          subcategories: [sub('DE.CM-01', 'Monitoreo de redes', null, [control('DE.1-1', 1)])],
        },
      ],
    },
  ],
};

describe('evaluationOverview utils', () => {
  it('flattenOverview recorre funciones, categorías y subcategorías en orden', () => {
    const rows = flattenOverview(overview);

    expect(rows.map((r) => r.subcategory.code)).toEqual(['PR.PS-01', 'PR.PS-02', 'DE.CM-01']);
    expect(rows[0].functionName).toBe('Proteger');
    expect(rows[0].categoryName).toBe('Seguridad de los datos');
    expect(rows[2].functionName).toBe('Detectar');
  });

  it('groupControlsByLevel agrupa los controles por nivel 1-4', () => {
    const grouped = groupControlsByLevel([
      control('A', 1),
      control('B', 2),
      control('C', 2),
      control('D', 4),
    ]);

    expect(grouped[1].map((c) => c.code)).toEqual(['A']);
    expect(grouped[2].map((c) => c.code)).toEqual(['B', 'C']);
    expect(grouped[3]).toEqual([]);
    expect(grouped[4].map((c) => c.code)).toEqual(['D']);
  });

  it('countControlsByLevel cuenta cero en los niveles sin controles', () => {
    expect(countControlsByLevel([control('A', 1), control('B', 1)])).toEqual({
      1: 2,
      2: 0,
      3: 0,
      4: 0,
    });
  });

  it('filterOverviewRows sin filtros devuelve todo', () => {
    const rows = flattenOverview(overview);

    expect(filterOverviewRows(rows, '', 'ALL')).toHaveLength(3);
    expect(filterOverviewRows(rows, '   ', 'ALL')).toHaveLength(3);
  });

  it('filterOverviewRows filtra por prioridad, incluida la ausencia de prioridad', () => {
    const rows = flattenOverview(overview);

    expect(filterOverviewRows(rows, '', 'ALTA').map((r) => r.subcategory.code)).toEqual([
      'PR.PS-01',
    ]);
    expect(filterOverviewRows(rows, '', 'MEDIA').map((r) => r.subcategory.code)).toEqual([
      'PR.PS-02',
    ]);
    expect(filterOverviewRows(rows, '', 'BAJA')).toEqual([]);
    expect(filterOverviewRows(rows, '', 'NONE').map((r) => r.subcategory.code)).toEqual([
      'DE.CM-01',
    ]);
  });

  it('filterOverviewRows busca por código, nombre, requisito y control sin distinguir tildes', () => {
    const rows = flattenOverview(overview);

    expect(filterOverviewRows(rows, 'pr.ps-02', 'ALL')).toHaveLength(1);
    expect(filterOverviewRows(rows, 'gestion de cambios', 'ALL')).toHaveLength(1);
    expect(filterOverviewRows(rows, 'metodologia', 'ALL')).toHaveLength(3);
    expect(filterOverviewRows(rows, 'DE.1-1', 'ALL').map((r) => r.subcategory.code)).toEqual([
      'DE.CM-01',
    ]);
    expect(filterOverviewRows(rows, 'inexistente', 'ALL')).toEqual([]);
  });

  it('filterOverviewRows combina búsqueda y prioridad', () => {
    const rows = flattenOverview(overview);

    expect(filterOverviewRows(rows, 'pr.ps-01', 'MEDIA')).toEqual([]);
    expect(filterOverviewRows(rows, 'pr.ps-01', 'ALTA')).toHaveLength(1);
  });

  it('groupRowsByFunction conserva el orden y agrupa por función', () => {
    const groups = groupRowsByFunction(flattenOverview(overview));

    expect(groups.map((g) => g.name)).toEqual(['Proteger', 'Detectar']);
    expect(groups[0].rows).toHaveLength(2);
    expect(groups[1].rows).toHaveLength(1);
  });

  it('priorityLabel traduce cada prioridad', () => {
    expect(priorityLabel('ALTA')).toBe('Prioridad Alta');
    expect(priorityLabel('MEDIA')).toBe('Prioridad Media');
    expect(priorityLabel('BAJA')).toBe('Prioridad Baja');
    expect(priorityLabel(null)).toBe('Sin prioridad');
  });
});
