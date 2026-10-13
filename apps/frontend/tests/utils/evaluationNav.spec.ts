import { describe, it, expect } from 'vitest';
import { pickEntryIndex } from '../../src/utils/evaluationNav';

interface Q {
  id: string;
  answered: boolean;
  skipped?: boolean;
}

const pick = (items: Q[]) =>
  pickEntryIndex(
    items,
    (q) => q.answered || !!q.skipped,
    (q) => !!q.skipped,
  );

describe('pickEntryIndex', () => {
  it('va al primer control sin responder (tick blanco)', () => {
    const items = [
      { id: 'c1', answered: true },
      { id: 'c2', answered: true },
      { id: 'c3', answered: false },
      { id: 'c4', answered: false },
    ];
    expect(pick(items)).toBe(2);
  });

  it('va al primero cuando todos están sin responder', () => {
    expect(
      pick([
        { id: 'c1', answered: false },
        { id: 'c2', answered: false },
      ]),
    ).toBe(0);
  });

  it('va al primero cuando están todos respondidos (tick verde)', () => {
    expect(
      pick([
        { id: 'c1', answered: true },
        { id: 'c2', answered: true },
      ]),
    ).toBe(0);
  });

  it('un control saltado por el modelo acumulativo cuenta como respondido', () => {
    const items = [
      { id: 'c1', answered: true },
      { id: 'c2', answered: false, skipped: true },
      { id: 'c3', answered: false },
    ];
    expect(pick(items)).toBe(2);
  });

  it('con todo respondido prefiere el primero que se pregunta, no uno saltado', () => {
    const items = [
      { id: 'c1', answered: false, skipped: true },
      { id: 'c2', answered: true },
    ];
    expect(pick(items)).toBe(1);
  });

  it('si todos están saltados cae al primero (el click nunca queda muerto)', () => {
    const items = [
      { id: 'c1', answered: false, skipped: true },
      { id: 'c2', answered: false, skipped: true },
    ];
    expect(pick(items)).toBe(0);
  });

  it('devuelve -1 sin preguntas', () => {
    expect(pick([])).toBe(-1);
  });
});
