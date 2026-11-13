import { describe, it, expect } from 'vitest';
import {
  buildActionPlan,
  type ActionPlan,
  type PlanContext,
  type PlanRole,
} from '@/utils/evaluationActionPlan';
import type { EvaluationStatus } from '@/types';

const STATUSES: EvaluationStatus[] = [
  'DRAFT',
  'IN_PROGRESS',
  'READY_FOR_AUDIT',
  'IN_AUDIT',
  'RETURNED',
  'APPROVED',
  'ARCHIVED',
];

const ORG_ROLES: PlanRole[] = ['ORG_RESPONSIBLE', 'INTERNAL_EVALUATOR'];

function plan(role: PlanRole, status: EvaluationStatus, extra: Partial<PlanContext> = {}) {
  return buildActionPlan({
    status,
    evaluationId: 'ev-1',
    hasRole: (...roles) => roles.includes(role),
    canDelete: false,
    showAuditorObservations:
      ORG_ROLES.includes(role) && status !== 'DRAFT' && status !== 'IN_AUDIT',
    evidenceCount: null,
    observations: null,
    ...extra,
  });
}

const primaryKeys = (p: ActionPlan) => p.primary.map((a) => a.key);
const secondaryKeys = (p: ActionPlan) => p.secondary.map((a) => a.key);
const secondary = (p: ActionPlan, key: string) => p.secondary.find((a) => a.key === key);

describe('buildActionPlan: acciones principales por rol y estado', () => {
  // Las mismas transiciones que antes ofrecía la tarjeta «Acciones de Flujo».
  const EXPECTED: Record<PlanRole, Record<EvaluationStatus, string[]>> = {
    PRISMA_ADMIN: {
      DRAFT: ['start'],
      IN_PROGRESS: ['respond', 'submit'],
      READY_FOR_AUDIT: ['start-audit'],
      IN_AUDIT: ['review', 'approve', 'return'],
      RETURNED: ['resume'],
      APPROVED: ['improvement', 'reports'],
      ARCHIVED: [],
    },
    ORG_RESPONSIBLE: {
      DRAFT: ['start'],
      IN_PROGRESS: ['submit'],
      READY_FOR_AUDIT: [],
      IN_AUDIT: [],
      RETURNED: ['resume'],
      APPROVED: ['improvement', 'reports'],
      ARCHIVED: [],
    },
    INTERNAL_EVALUATOR: {
      DRAFT: ['start'],
      IN_PROGRESS: ['respond', 'submit'],
      READY_FOR_AUDIT: [],
      IN_AUDIT: [],
      RETURNED: ['resume'],
      APPROVED: ['improvement'],
      ARCHIVED: [],
    },
    AUDITOR: {
      DRAFT: [],
      IN_PROGRESS: [],
      READY_FOR_AUDIT: ['start-audit'],
      IN_AUDIT: ['review', 'approve', 'return'],
      RETURNED: [],
      APPROVED: ['improvement'],
      ARCHIVED: [],
    },
    VIEWER: {
      DRAFT: [],
      IN_PROGRESS: [],
      READY_FOR_AUDIT: [],
      IN_AUDIT: [],
      RETURNED: [],
      APPROVED: ['improvement'],
      ARCHIVED: [],
    },
  };

  for (const role of Object.keys(EXPECTED) as PlanRole[]) {
    for (const status of STATUSES) {
      it(`${role} en ${status}: ${EXPECTED[role][status].join(', ') || 'sin acciones'}`, () => {
        expect(primaryKeys(plan(role, status))).toEqual(EXPECTED[role][status]);
      });
    }
  }

  it('las acciones de estado llevan al estado correcto', () => {
    const target = (role: PlanRole, status: EvaluationStatus, key: string) => {
      const a = plan(role, status).primary.find((x) => x.key === key);
      return a?.kind === 'status' ? a.status : undefined;
    };
    expect(target('PRISMA_ADMIN', 'DRAFT', 'start')).toBe('IN_PROGRESS');
    expect(target('PRISMA_ADMIN', 'IN_PROGRESS', 'submit')).toBe('READY_FOR_AUDIT');
    expect(target('AUDITOR', 'READY_FOR_AUDIT', 'start-audit')).toBe('IN_AUDIT');
    expect(target('AUDITOR', 'IN_AUDIT', 'approve')).toBe('APPROVED');
    expect(target('AUDITOR', 'IN_AUDIT', 'return')).toBe('RETURNED');
    expect(target('INTERNAL_EVALUATOR', 'RETURNED', 'resume')).toBe('IN_PROGRESS');
  });

  it('los botones que navegan apuntan a la pantalla correcta', () => {
    const to = (role: PlanRole, status: EvaluationStatus, key: string) => {
      const a = plan(role, status).primary.find((x) => x.key === key);
      return a?.kind === 'route' ? a.to : undefined;
    };
    expect(to('INTERNAL_EVALUATOR', 'IN_PROGRESS', 'respond')).toBe('/evaluations/ev-1/respond');
    expect(to('AUDITOR', 'IN_AUDIT', 'review')).toBe('/audit/ev-1');
    expect(to('VIEWER', 'APPROVED', 'improvement')).toBe('/improvement/ev-1');
    expect(to('ORG_RESPONSIBLE', 'APPROVED', 'reports')).toBe('/reports');
  });
});

describe('buildActionPlan: turno y textos', () => {
  it('le dice quién tiene el turno', () => {
    expect(plan('INTERNAL_EVALUATOR', 'IN_PROGRESS').eyebrow).toBe('Tu turno · Evaluador interno');
    expect(plan('PRISMA_ADMIN', 'DRAFT').eyebrow).toBe('Tu turno · Administrador');
    expect(plan('AUDITOR', 'IN_AUDIT').eyebrow).toBe('Tu turno · Auditor');
  });

  it('cuando no le toca, es una vista con cartel de espera y sin botones', () => {
    const p = plan('ORG_RESPONSIBLE', 'READY_FOR_AUDIT');
    expect(p.eyebrow).toBe('Vista Responsable');
    expect(p.turn).toBe('waiting');
    expect(p.primary).toEqual([]);
    expect(p.waitingLabel).toBe('Esperando auditor…');

    expect(plan('ORG_RESPONSIBLE', 'IN_AUDIT').waitingLabel).toBe('Auditor revisando…');
    expect(plan('AUDITOR', 'RETURNED').waitingLabel).toBe('Esperando correcciones…');
    expect(plan('VIEWER', 'DRAFT').waitingLabel).toBe('Sin acciones para tu rol');
  });

  it('archivada es solo lectura para todos', () => {
    for (const role of ['PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'AUDITOR', 'VIEWER'] as PlanRole[]) {
      const p = plan(role, 'ARCHIVED');
      expect(p.turn, role).toBe('locked');
      expect(p.waitingLabel, role).toBe('Solo lectura');
      expect(p.primary, role).toEqual([]);
    }
  });

  it('en En curso el responsable sabe que no responde él', () => {
    const p = plan('ORG_RESPONSIBLE', 'IN_PROGRESS');
    expect(p.title).toContain('evaluador responde');
    expect(p.text).toContain('No podés responder controles');
    // En cambio el evaluador sí.
    expect(plan('INTERNAL_EVALUATOR', 'IN_PROGRESS').title).toContain('respondé los controles');
  });

  it('el progreso solo se muestra en En curso', () => {
    for (const status of STATUSES) {
      expect(plan('PRISMA_ADMIN', status).showProgress, status).toBe(status === 'IN_PROGRESS');
    }
  });

  it('cada estado tiene su color', () => {
    expect(plan('PRISMA_ADMIN', 'RETURNED').tone).toBe('orange');
    expect(plan('PRISMA_ADMIN', 'APPROVED').tone).toBe('green');
    expect(plan('PRISMA_ADMIN', 'READY_FOR_AUDIT').tone).toBe('purple');
  });
});

describe('buildActionPlan: otras acciones', () => {
  it('Estructura la ven todos y siempre', () => {
    for (const role of ['PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'AUDITOR', 'VIEWER'] as PlanRole[]) {
      for (const status of STATUSES) {
        expect(secondaryKeys(plan(role, status)), `${role}/${status}`).toContain('overview');
      }
    }
  });

  it('Responder: solo ADMIN y evaluador; deshabilitado fuera de Borrador/En curso/Devuelta', () => {
    expect(secondary(plan('ORG_RESPONSIBLE', 'RETURNED'), 'respond')).toBeUndefined();
    expect(secondary(plan('AUDITOR', 'RETURNED'), 'respond')).toBeUndefined();
    // En curso es botón principal, no secundario.
    expect(secondary(plan('INTERNAL_EVALUATOR', 'IN_PROGRESS'), 'respond')).toBeUndefined();
    expect(secondary(plan('INTERNAL_EVALUATOR', 'RETURNED'), 'respond')?.disabled).toBe(false);
    expect(secondary(plan('INTERNAL_EVALUATOR', 'DRAFT'), 'respond')?.disabled).toBe(false);
    for (const status of ['READY_FOR_AUDIT', 'IN_AUDIT', 'APPROVED', 'ARCHIVED'] as const) {
      expect(secondary(plan('PRISMA_ADMIN', status), 'respond')?.disabled, status).toBe(true);
    }
  });

  it('Auditoría: solo ADMIN y auditor; deshabilitado al archivar y no se repite en En auditoría', () => {
    expect(secondary(plan('INTERNAL_EVALUATOR', 'READY_FOR_AUDIT'), 'audit')).toBeUndefined();
    expect(secondary(plan('VIEWER', 'READY_FOR_AUDIT'), 'audit')).toBeUndefined();
    expect(secondary(plan('AUDITOR', 'IN_AUDIT'), 'audit')).toBeUndefined();
    expect(secondary(plan('AUDITOR', 'APPROVED'), 'audit')?.disabled).toBe(false);
    expect(secondary(plan('PRISMA_ADMIN', 'ARCHIVED'), 'audit')?.disabled).toBe(true);
  });

  it('Plan de mejora: secundario en estados intermedios, principal al aprobarse, ausente en Borrador y Archivada', () => {
    for (const status of ['IN_PROGRESS', 'READY_FOR_AUDIT', 'IN_AUDIT', 'RETURNED'] as const) {
      expect(secondaryKeys(plan('VIEWER', status)), status).toContain('improvement');
    }
    expect(secondaryKeys(plan('VIEWER', 'APPROVED'))).not.toContain('improvement');
    expect(secondaryKeys(plan('VIEWER', 'DRAFT'))).not.toContain('improvement');
    expect(secondaryKeys(plan('VIEWER', 'ARCHIVED'))).not.toContain('improvement');
  });

  it('Archivar: solo ADMIN y responsable, en Borrador, En curso, Devuelta y Aprobada', () => {
    for (const status of STATUSES) {
      const can = ['DRAFT', 'IN_PROGRESS', 'RETURNED', 'APPROVED'].includes(status);
      expect(secondaryKeys(plan('PRISMA_ADMIN', status)).includes('archive'), status).toBe(can);
      expect(secondaryKeys(plan('ORG_RESPONSIBLE', status)).includes('archive'), status).toBe(can);
      expect(secondaryKeys(plan('INTERNAL_EVALUATOR', status)), status).not.toContain('archive');
      expect(secondaryKeys(plan('AUDITOR', status)), status).not.toContain('archive');
    }
    const archive = secondary(plan('PRISMA_ADMIN', 'DRAFT'), 'archive');
    expect(archive?.kind).toBe('status');
    expect(archive?.status).toBe('ARCHIVED');
  });

  it('Eliminar solo aparece si el contexto lo permite', () => {
    expect(secondaryKeys(plan('PRISMA_ADMIN', 'DRAFT'))).not.toContain('delete');
    const p = plan('PRISMA_ADMIN', 'DRAFT', { canDelete: true });
    expect(secondary(p, 'delete')?.kind).toBe('delete');
  });
});

describe('buildActionPlan: documentación y revisión', () => {
  const tool = (p: ActionPlan, key: string) => p.tools.find((t) => t.key === key);

  it('Evidencias está en todos los estados y roles; se bloquea al archivar', () => {
    for (const role of ['PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'AUDITOR', 'VIEWER'] as PlanRole[]) {
      for (const status of STATUSES) {
        const t = tool(plan(role, status), 'evidence');
        expect(t, `${role}/${status}`).toBeDefined();
        expect(t!.to).toBe('/evidence/ev-1');
        expect(t!.disabled, `${role}/${status}`).toBe(status === 'ARCHIVED');
      }
    }
  });

  it('muestra el contador de evidencias cuando se conoce', () => {
    expect(tool(plan('VIEWER', 'IN_PROGRESS'), 'evidence')?.badge).toBeUndefined();
    expect(tool(plan('VIEWER', 'IN_PROGRESS', { evidenceCount: 23 }), 'evidence')?.badge).toBe(
      '23',
    );
    expect(tool(plan('VIEWER', 'IN_PROGRESS', { evidenceCount: 0 }), 'evidence')?.badge).toBe('0');
  });

  it('Observaciones: la organización las ve en los estados donde el bloque existe', () => {
    for (const status of [
      'IN_PROGRESS',
      'READY_FOR_AUDIT',
      'RETURNED',
      'APPROVED',
      'ARCHIVED',
    ] as const) {
      expect(tool(plan('ORG_RESPONSIBLE', status), 'observations'), status).toBeDefined();
    }
    for (const status of ['DRAFT', 'IN_AUDIT'] as const) {
      expect(tool(plan('ORG_RESPONSIBLE', status), 'observations'), status).toBeUndefined();
    }
    expect(tool(plan('VIEWER', 'RETURNED'), 'observations')).toBeUndefined();
  });

  it('Observaciones en Devuelta va primero, resaltada y con las abiertas', () => {
    const p = plan('ORG_RESPONSIBLE', 'RETURNED', { observations: { total: 4, open: 3 } });
    expect(p.tools.map((t) => t.key)).toEqual(['observations', 'evidence']);
    expect(p.tools[0].highlight).toBe(true);
    expect(p.tools[0].badge).toBe('3 abiertas');
    expect(p.tools[0].kind).toBe('scroll');
  });

  it('sin observaciones del primer ciclo, la tarjeta queda deshabilitada (el bloque está oculto)', () => {
    const p = plan('ORG_RESPONSIBLE', 'IN_PROGRESS', { observations: { total: 0, open: 0 } });
    const t = tool(p, 'observations')!;
    expect(t.disabled).toBe(true);
    expect(t.hint).toContain('Aún no hay observaciones');
    // En Devuelta el bloque se ve igual (con «no registró observaciones»): sigue habilitada.
    const returned = plan('ORG_RESPONSIBLE', 'RETURNED', { observations: { total: 0, open: 0 } });
    expect(tool(returned, 'observations')?.disabled).toBe(false);
  });

  it('el auditor y el admin van a la pantalla de Auditoría para cargar observaciones', () => {
    for (const role of ['AUDITOR', 'PRISMA_ADMIN'] as PlanRole[]) {
      const t = tool(plan(role, 'IN_AUDIT'), 'observations');
      expect(t?.kind, role).toBe('route');
      expect(t?.to, role).toBe('/audit/ev-1');
    }
    expect(tool(plan('AUDITOR', 'RETURNED'), 'observations')).toBeUndefined();
  });
});
