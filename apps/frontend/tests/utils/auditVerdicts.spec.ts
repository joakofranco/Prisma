import { describe, it, expect } from 'vitest';
import {
  VERDICT_LABELS,
  flattenControls,
  filterControls,
  countByVerdict,
} from '../../src/utils/auditVerdicts';
import type { AuditVerdict, MaturityCatalog } from '../../src/types';

const control = (id: string, code: string, description: string) => ({
  id,
  code,
  description,
  targetLevel: 1 as const,
});

const catalog = {
  id: 'c',
  version: '5.0',
  functions: [
    {
      id: 'f',
      name: 'F',
      description: '',
      categories: [
        {
          id: 'cat',
          name: 'C',
          description: '',
          subcategories: [
            {
              id: 's1',
              name: 'S1',
              description: '',
              requirements: [
                {
                  id: 'r1',
                  code: 'R1',
                  description: '',
                  controls: [control('a', 'AD.1', 'Políticas'), control('b', 'AD.2', 'Roles')],
                },
              ],
            },
            {
              id: 's2',
              name: 'S2',
              description: '',
              // Mismo requisito enlazado a otra subcategoría: el control 'a' se repite.
              requirements: [
                {
                  id: 'r1',
                  code: 'R1',
                  description: '',
                  controls: [control('a', 'AD.1', 'Políticas')],
                },
              ],
            },
          ],
        },
      ],
    },
  ],
} as unknown as MaturityCatalog;

describe('auditVerdicts', () => {
  it('tiene los tres dictámenes pedidos por el RF-AUD-06', () => {
    expect(Object.values(VERDICT_LABELS)).toEqual([
      'Validado',
      'No validado',
      'Requiere más evidencia',
    ]);
  });

  it('flattenControls no repite un control enlazado a varias subcategorías', () => {
    expect(flattenControls(catalog).map((c) => c.id)).toEqual(['a', 'b']);
  });

  it('filterControls filtra por texto y por dictamen', () => {
    const controls = flattenControls(catalog);
    const verdicts: Record<string, AuditVerdict> = { a: 'VALIDATED' };

    expect(filterControls(controls, verdicts, 'rol', 'ALL').map((c) => c.id)).toEqual(['b']);
    expect(filterControls(controls, verdicts, '', 'NONE').map((c) => c.id)).toEqual(['b']);
    expect(filterControls(controls, verdicts, '', 'VALIDATED').map((c) => c.id)).toEqual(['a']);
    expect(filterControls(controls, verdicts, 'ad.2', 'VALIDATED')).toEqual([]);
  });

  it('countByVerdict cuenta también los controles sin dictamen', () => {
    const controls = flattenControls(catalog);
    expect(countByVerdict(controls, { a: 'NOT_VALIDATED' })).toEqual({
      VALIDATED: 0,
      NOT_VALIDATED: 1,
      NEEDS_MORE_EVIDENCE: 0,
      NONE: 1,
    });
  });
});
