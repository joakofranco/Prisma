import { describe, it, expect } from 'vitest';
import {
  canFlagControls,
  isAuditWorkOpen,
  isSelfAssessmentEditable,
  selfAssessmentLockedReason,
  statusName,
} from '@/utils/evaluationRules';

const ALL = [
  'DRAFT',
  'IN_PROGRESS',
  'READY_FOR_AUDIT',
  'IN_AUDIT',
  'RETURNED',
  'APPROVED',
  'ARCHIVED',
] as const;

describe('evaluationRules: espejo de las reglas de estado del backend', () => {
  it('respuestas y evidencias: solo Borrador, En curso y Devuelta', () => {
    const editable = ALL.filter((s) => isSelfAssessmentEditable(s));
    expect(editable).toEqual(['DRAFT', 'IN_PROGRESS', 'RETURNED']);
  });

  it('marcar controles: mientras se responde y durante la auditoría', () => {
    const can = ALL.filter((s) => canFlagControls(s));
    expect(can).toEqual(['DRAFT', 'IN_PROGRESS', 'IN_AUDIT', 'RETURNED']);
  });

  it('observaciones, dictámenes y citas: solo En auditoría', () => {
    expect(ALL.filter((s) => isAuditWorkOpen(s))).toEqual(['IN_AUDIT']);
  });

  it('con el estado desconocido no se bloquea nada (decide el backend)', () => {
    for (const unknown of [null, undefined, '']) {
      expect(isSelfAssessmentEditable(unknown)).toBe(true);
      expect(canFlagControls(unknown)).toBe(true);
      expect(isAuditWorkOpen(unknown)).toBe(true);
    }
  });

  it('explica el bloqueo con el nombre del estado en español', () => {
    expect(statusName('READY_FOR_AUDIT')).toBe('Lista para auditoría');
    expect(selfAssessmentLockedReason('APPROVED')).toBe(
      'La evaluación está en estado «Aprobada»: ya no admite cambios.',
    );
  });
});
