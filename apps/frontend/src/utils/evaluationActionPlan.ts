import type { EvaluationStatus } from '@/types';

/**
 * Plan de acciones del detalle de una evaluación: qué le toca hacer al usuario según el estado de
 * la evaluación y su rol. Es lógica pura (sin Vue) para poder probar la matriz rol × estado.
 *
 * Los permisos son los mismos que ya tenía la pantalla (tarjeta «Acciones de Flujo»); el backend
 * los exige igual en cada transición.
 */

export type PlanRole =
  'PRISMA_ADMIN' | 'ORG_RESPONSIBLE' | 'INTERNAL_EVALUATOR' | 'AUDITOR' | 'VIEWER';

export type ActionTone = 'blue' | 'green' | 'red' | 'purple' | 'amber';
export type PanelTone = 'blue' | 'purple' | 'amber' | 'orange' | 'green' | 'slate';
export type ActionIcon =
  | 'play'
  | 'pencil'
  | 'send'
  | 'shield'
  | 'check'
  | 'undo'
  | 'refresh'
  | 'trend'
  | 'download'
  | 'structure'
  | 'clip'
  | 'chat'
  | 'archive'
  | 'trash';

/** Acción principal (botón grande). Cambia el estado, o lleva a otra pantalla. */
export type PrimaryAction =
  | {
      kind: 'status';
      key: string;
      label: string;
      hint: string;
      tone: ActionTone;
      icon: ActionIcon;
      status: EvaluationStatus;
    }
  | {
      kind: 'route';
      key: string;
      label: string;
      hint: string;
      tone: ActionTone;
      icon: ActionIcon;
      to: string;
    };

/** Acción secundaria (botón chico de la fila «Otras acciones»). */
export interface SecondaryAction {
  key: string;
  label: string;
  icon: ActionIcon;
  /** route: navega; status: cambia el estado (Archivar); delete: elimina la evaluación. */
  kind: 'route' | 'status' | 'delete';
  to?: string;
  status?: EvaluationStatus;
  disabled?: boolean;
}

/** Tarjeta grande de «Documentación y revisión» (Evidencias / Observaciones del auditor). */
export interface ToolTile {
  key: 'evidence' | 'observations';
  label: string;
  hint: string;
  icon: ActionIcon;
  badge?: string;
  /** Resalta la tarjeta: es lo que conviene mirar en este momento. */
  highlight: boolean;
  disabled: boolean;
  /** route: navega; scroll: baja hasta el bloque de observaciones de la misma pantalla. */
  kind: 'route' | 'scroll';
  to?: string;
}

export interface ActionPlan {
  tone: PanelTone;
  /** yours: le toca actuar · waiting: espera a otro · info/locked: solo consulta. */
  turn: 'yours' | 'waiting' | 'info' | 'locked';
  /** Texto chico sobre el título: «Tu turno · Evaluador interno», «Vista Responsable»… */
  eyebrow: string;
  title: string;
  text: string;
  showProgress: boolean;
  primary: PrimaryAction[];
  /** Cartel que reemplaza a los botones cuando no hay nada para hacer. */
  waitingLabel: string | null;
  tools: ToolTile[];
  secondary: SecondaryAction[];
}

export interface PlanContext {
  status: EvaluationStatus;
  evaluationId: string;
  hasRole: (...roles: PlanRole[]) => boolean;
  /** El servidor dice que la evaluación no tiene datos y el rol puede borrarla. */
  canDelete: boolean;
  /** Es usuario de la organización y el estado permite ver las observaciones del auditor. */
  showAuditorObservations: boolean;
  /** Cantidad de evidencias cargadas (null = todavía no se sabe). */
  evidenceCount: number | null;
  /** Observaciones del auditor (null = todavía no se sabe / no aplica). */
  observations: { total: number; open: number } | null;
}

const RESPONDABLE: EvaluationStatus[] = ['DRAFT', 'IN_PROGRESS', 'RETURNED'];
const ARCHIVABLE: EvaluationStatus[] = ['DRAFT', 'IN_PROGRESS', 'RETURNED', 'APPROVED'];
/** En estos estados el bloque de observaciones se oculta si el auditor no dejó ninguna. */
const HIDE_WHEN_EMPTY: EvaluationStatus[] = ['IN_PROGRESS', 'READY_FOR_AUDIT'];

const PANEL_TONE: Record<EvaluationStatus, PanelTone> = {
  DRAFT: 'blue',
  IN_PROGRESS: 'blue',
  READY_FOR_AUDIT: 'purple',
  IN_AUDIT: 'amber',
  RETURNED: 'orange',
  APPROVED: 'green',
  ARCHIVED: 'slate',
};

/** Nombre del rol «principal» del usuario, para el texto «Tu turno · …». */
export function roleLabel(hasRole: PlanContext['hasRole']): string {
  if (hasRole('PRISMA_ADMIN')) return 'Administrador';
  if (hasRole('ORG_RESPONSIBLE')) return 'Responsable';
  if (hasRole('INTERNAL_EVALUATOR')) return 'Evaluador interno';
  if (hasRole('AUDITOR')) return 'Auditor';
  return 'Visualizador';
}

export function buildActionPlan(ctx: PlanContext): ActionPlan {
  const { status, evaluationId: id, hasRole: has } = ctx;

  const isOrgActor = has('PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'INTERNAL_EVALUATOR');
  const canRespond = has('PRISMA_ADMIN', 'INTERNAL_EVALUATOR');
  const canAudit = has('PRISMA_ADMIN', 'AUDITOR');
  const canManage = has('PRISMA_ADMIN', 'ORG_RESPONSIBLE');

  // ---- Acciones principales -------------------------------------------------------------------
  const primary: PrimaryAction[] = [];
  switch (status) {
    case 'DRAFT':
      if (isOrgActor)
        primary.push({
          kind: 'status',
          key: 'start',
          label: 'Iniciar evaluación',
          hint: 'Habilita cargar respuestas',
          tone: 'blue',
          icon: 'play',
          status: 'IN_PROGRESS',
        });
      break;
    case 'IN_PROGRESS':
      if (canRespond)
        primary.push({
          kind: 'route',
          key: 'respond',
          label: 'Responder controles',
          hint: 'Continuar donde dejaste',
          tone: 'blue',
          icon: 'pencil',
          to: `/evaluations/${id}/respond`,
        });
      if (isOrgActor)
        primary.push({
          kind: 'status',
          key: 'submit',
          label: 'Enviar a auditoría',
          hint: 'Queda visible para un auditor',
          tone: 'green',
          icon: 'send',
          status: 'READY_FOR_AUDIT',
        });
      break;
    case 'READY_FOR_AUDIT':
      if (canAudit)
        primary.push({
          kind: 'status',
          key: 'start-audit',
          label: 'Iniciar auditoría',
          hint: 'La tomás como auditor',
          tone: 'purple',
          icon: 'shield',
          status: 'IN_AUDIT',
        });
      break;
    case 'IN_AUDIT':
      if (canAudit) {
        primary.push(
          {
            kind: 'route',
            key: 'review',
            label: 'Ir a revisar',
            hint: 'Observaciones y evidencias',
            tone: 'amber',
            icon: 'shield',
            to: `/audit/${id}`,
          },
          {
            kind: 'status',
            key: 'approve',
            label: 'Aprobar',
            hint: 'Sella y habilita reportes',
            tone: 'green',
            icon: 'check',
            status: 'APPROVED',
          },
          {
            kind: 'status',
            key: 'return',
            label: 'Devolver',
            hint: 'Con tus observaciones',
            tone: 'red',
            icon: 'undo',
            status: 'RETURNED',
          },
        );
      }
      break;
    case 'RETURNED':
      if (isOrgActor)
        primary.push({
          kind: 'status',
          key: 'resume',
          label: 'Reanudar y corregir',
          hint: 'Vuelve a En curso',
          tone: 'amber',
          icon: 'refresh',
          status: 'IN_PROGRESS',
        });
      break;
    case 'APPROVED':
      primary.push({
        kind: 'route',
        key: 'improvement',
        label: 'Plan de mejora',
        hint: 'Cerrar las brechas',
        tone: 'green',
        icon: 'trend',
        to: `/improvement/${id}`,
      });
      if (canManage)
        primary.push({
          kind: 'route',
          key: 'reports',
          label: 'Descargar reportes',
          hint: 'PDF / PDF-A',
          tone: 'blue',
          icon: 'download',
          to: '/reports',
        });
      break;
    case 'ARCHIVED':
      break;
  }

  // ---- Textos: qué se le pide a este usuario en este estado -----------------------------------
  const yours = primary.length > 0 && status !== 'APPROVED';
  const who = roleLabel(has);
  let title: string;
  let text: string;
  let waitingLabel: string | null = null;
  let turn: ActionPlan['turn'] = yours ? 'yours' : 'info';

  switch (status) {
    case 'DRAFT':
      if (yours) {
        title = 'Paso 1: iniciá la evaluación';
        text =
          'Todavía no se puede responder nada. Al iniciarla se habilita la carga de respuestas de los controles.';
      } else {
        title = 'La evaluación está en preparación';
        text = 'Todavía no comenzó. Podés consultar su estructura y las evidencias.';
        waitingLabel = 'Sin acciones para tu rol';
      }
      break;
    case 'IN_PROGRESS':
      if (canRespond) {
        title = 'Paso 2: respondé los controles y enviá a auditoría';
        text =
          'Cargá las respuestas y adjuntá evidencias. Cuando termines, enviala para que un auditor la revise.';
      } else if (yours) {
        title = 'El evaluador responde; vos enviás a auditoría';
        text =
          'El evaluador interno carga las respuestas. Cuando estén completas, enviá la evaluación a auditoría. No podés responder controles.';
      } else {
        title = 'La organización está respondiendo';
        text = 'Todavía no fue enviada a auditoría. No hace falta que hagas nada por ahora.';
        waitingLabel = 'En curso…';
      }
      break;
    case 'READY_FOR_AUDIT':
      if (yours) {
        title = 'Hay una evaluación lista para revisar';
        text = 'Tomala para empezar la auditoría. Mientras tanto nadie más podrá auditarla.';
      } else {
        title = 'Enviada: esperando a un auditor';
        text =
          'La evaluación ya no se puede editar. Cuando un auditor la tome o la devuelva, lo verás acá.';
        waitingLabel = 'Esperando auditor…';
        turn = 'waiting';
      }
      break;
    case 'IN_AUDIT':
      if (yours) {
        title = 'Revisá y decidí: aprobar o devolver';
        text =
          'Revisá respuestas y evidencias, dejá tus observaciones y resolvé. Al devolver, el responsable las verá.';
      } else {
        title = 'En revisión por el auditor';
        text =
          'No hay nada para hacer por ahora. El resultado aparecerá acá: aprobada o devuelta con observaciones.';
        waitingLabel = 'Auditor revisando…';
        turn = 'waiting';
      }
      break;
    case 'RETURNED':
      if (yours) {
        title = 'El auditor devolvió la evaluación';
        text = canRespond
          ? 'Leé las observaciones, corregí las respuestas y volvé a enviarla. «Reanudar» la pasa otra vez a En curso.'
          : 'Leé las observaciones y reanudá la evaluación para que el evaluador corrija. «Reanudar» la pasa otra vez a En curso.';
      } else {
        title = 'Devuelta a la organización';
        text = canAudit
          ? 'Ya dejaste tus observaciones. Esperá a que la organización corrija y vuelva a enviarla.'
          : 'La organización está corrigiendo las observaciones del auditor.';
        waitingLabel = 'Esperando correcciones…';
        turn = 'waiting';
      }
      break;
    case 'APPROVED':
      title = '¡Aprobada! Siguiente paso: plan de mejora';
      text = canManage
        ? 'Está sellada y ya no se puede modificar. Planificá mejoras o descargá los reportes.'
        : 'Está sellada y ya no se puede modificar. Usá el resultado para planificar mejoras.';
      turn = 'info';
      break;
    case 'ARCHIVED':
      title = 'Evaluación archivada · solo lectura';
      text =
        'Conserva su historial completo (respuestas, evidencias y auditoría), pero ya no admite cambios.';
      waitingLabel = 'Solo lectura';
      turn = 'locked';
      break;
  }

  let eyebrow: string;
  if (status === 'APPROVED') eyebrow = 'Evaluación certificada';
  else if (status === 'ARCHIVED') eyebrow = 'Archivada';
  else if (yours) eyebrow = `Tu turno · ${who}`;
  else eyebrow = `Vista ${who}`;

  // ---- Otras acciones -------------------------------------------------------------------------
  const secondary: SecondaryAction[] = [
    {
      key: 'overview',
      label: 'Estructura',
      icon: 'structure',
      kind: 'route',
      to: `/evaluations/${id}/overview`,
    },
  ];
  const respondIsPrimary = primary.some((a) => a.key === 'respond');
  if (canRespond && !respondIsPrimary)
    secondary.push({
      key: 'respond',
      label: 'Responder',
      icon: 'pencil',
      kind: 'route',
      to: `/evaluations/${id}/respond`,
      disabled: !RESPONDABLE.includes(status),
    });
  if (canAudit && status !== 'IN_AUDIT')
    secondary.push({
      key: 'audit',
      label: 'Auditoría',
      icon: 'shield',
      kind: 'route',
      to: `/audit/${id}`,
      disabled: status === 'ARCHIVED',
    });
  if (status !== 'DRAFT' && status !== 'ARCHIVED' && status !== 'APPROVED')
    secondary.push({
      key: 'improvement',
      label: 'Plan de mejora',
      icon: 'trend',
      kind: 'route',
      to: `/improvement/${id}`,
    });
  if (canManage && ARCHIVABLE.includes(status))
    secondary.push({
      key: 'archive',
      label: 'Archivar',
      icon: 'archive',
      kind: 'status',
      status: 'ARCHIVED',
    });
  if (ctx.canDelete)
    secondary.push({ key: 'delete', label: 'Eliminar (sin datos)', icon: 'trash', kind: 'delete' });

  // ---- Documentación y revisión ---------------------------------------------------------------
  const archived = status === 'ARCHIVED';
  let evidenceHint: string;
  if (archived) evidenceHint = 'Consulta bloqueada en estado archivado';
  else if (status === 'APPROVED') evidenceHint = 'Respaldo de la certificación';
  else if (status === 'RETURNED' && isOrgActor)
    evidenceHint = 'Adjuntá lo que falta para cada observación';
  else if ((status === 'READY_FOR_AUDIT' || status === 'IN_AUDIT') && canAudit)
    evidenceHint = 'Revisá la documentación de cada control';
  else if (isOrgActor && RESPONDABLE.includes(status))
    evidenceHint = 'Documentación que respalda tus respuestas';
  else evidenceHint = 'Consultá la documentación de respaldo';

  const tools: ToolTile[] = [];
  const evidenceTile: ToolTile = {
    key: 'evidence',
    label: 'Evidencias',
    hint: evidenceHint,
    icon: 'clip',
    badge: ctx.evidenceCount != null ? String(ctx.evidenceCount) : undefined,
    highlight: (status === 'IN_PROGRESS' && canRespond) || (status === 'IN_AUDIT' && canAudit),
    disabled: archived,
    kind: 'route',
    to: `/evidence/${id}`,
  };

  let observationsTile: ToolTile | null = null;
  if (ctx.showAuditorObservations) {
    const total = ctx.observations?.total ?? null;
    const open = ctx.observations?.open ?? 0;
    const empty = total === 0;
    let hint: string;
    if (empty) hint = 'Aún no hay observaciones del auditor';
    else if (status === 'RETURNED') hint = 'Leelas antes de corregir';
    else if (status === 'APPROVED' || archived) hint = 'Historial de la auditoría';
    else hint = 'Lo que registró el auditor en la última revisión';
    observationsTile = {
      key: 'observations',
      label: 'Observaciones del auditor',
      hint,
      icon: 'chat',
      badge: total == null ? undefined : open > 0 ? `${open} abiertas` : String(total),
      highlight: status === 'RETURNED' || (status === 'IN_PROGRESS' && open > 0),
      // Con el bloque oculto (primer ciclo, sin observaciones) no hay adónde bajar.
      disabled: empty && HIDE_WHEN_EMPTY.includes(status),
      kind: 'scroll',
    };
  } else if (canAudit && status === 'IN_AUDIT') {
    observationsTile = {
      key: 'observations',
      label: 'Observaciones del auditor',
      hint: 'Cargalas en la pantalla de Auditoría',
      icon: 'chat',
      highlight: true,
      disabled: false,
      kind: 'route',
      to: `/audit/${id}`,
    };
  }

  // En Devuelta lo primero que debe ver la organización son las observaciones.
  if (observationsTile && status === 'RETURNED') tools.push(observationsTile, evidenceTile);
  else tools.push(evidenceTile, ...(observationsTile ? [observationsTile] : []));

  return {
    tone: PANEL_TONE[status],
    turn,
    eyebrow,
    title,
    text,
    showProgress: status === 'IN_PROGRESS',
    primary,
    waitingLabel: primary.length === 0 ? (waitingLabel ?? 'Sin acciones para tu rol') : null,
    tools,
    secondary,
  };
}
