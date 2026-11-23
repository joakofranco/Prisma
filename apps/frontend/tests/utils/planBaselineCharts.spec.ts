import { describe, it, expect } from 'vitest';
import { complianceSlices, shiftBars } from '@/utils/planBaseline';

describe('complianceSlices', () => {
  it('agrupa por cumplimiento, junta los sin fecha y omite los vacíos', () => {
    const slices = complianceSlices([
      { compliance: 'ON_TIME' },
      { compliance: 'ON_TIME' },
      { compliance: 'DELAYED' },
      { compliance: 'NO_DATE' },
      { compliance: 'NO_CLOSE_DATE' },
    ]);

    expect(slices.map((s) => [s.label, s.count])).toEqual([
      ['En plazo', 2],
      ['Atrasadas', 1],
      ['Sin fecha', 2],
    ]);
  });
});

describe('shiftBars', () => {
  const base = { controlCode: null, action: 'Acción', replanned: true };

  it('ordena por corrimiento absoluto, ignora las no movidas y limita', () => {
    const items = [
      { ...base, id: '1', dueDateShiftDays: 3 },
      { ...base, id: '2', dueDateShiftDays: -10 },
      { ...base, id: '3', dueDateShiftDays: 0 },
      { ...base, id: '4', dueDateShiftDays: null },
      { ...base, id: '5', replanned: false, dueDateShiftDays: 9 },
    ];

    const { bars, hidden } = shiftBars(items, 1);

    expect(bars.map((b) => b.id)).toEqual(['2']);
    expect(hidden).toBe(1);
  });

  it('usa el código del control y recorta las acciones largas', () => {
    const { bars } = shiftBars([
      { ...base, id: '1', controlCode: 'PS.1-1', dueDateShiftDays: 2 },
      { ...base, id: '2', action: 'a'.repeat(40), dueDateShiftDays: 1 },
    ]);

    expect(bars[0].label).toBe('PS.1-1');
    expect(bars[1].label).toHaveLength(28);
    expect(bars[1].label.endsWith('…')).toBe(true);
  });
});
