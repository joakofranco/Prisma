ALTER TABLE prisma.improvement_plans
    ADD COLUMN baseline_due_date    DATE,
    ADD COLUMN baseline_priority    VARCHAR(10),
    ADD COLUMN baseline_responsible VARCHAR(200),
    ADD COLUMN completed_at         TIMESTAMPTZ;

UPDATE prisma.improvement_plans
SET baseline_due_date    = due_date,
    baseline_priority    = priority,
    baseline_responsible = responsible;

UPDATE prisma.improvement_plans p
SET baseline_due_date = (f.payload -> 'changes' -> 'dueDate' ->> 'from')::date
FROM (SELECT DISTINCT ON (resource) resource, payload
      FROM audit.audit_logs
      WHERE action = 'UPDATE_IMPROVEMENT_PLAN'
        AND payload -> 'changes' -> 'dueDate' IS NOT NULL
      ORDER BY resource, created_at) f
WHERE f.resource = 'improvement_plan:' || p.id;

UPDATE prisma.improvement_plans p
SET baseline_priority = f.payload -> 'changes' -> 'priority' ->> 'from'
FROM (SELECT DISTINCT ON (resource) resource, payload
      FROM audit.audit_logs
      WHERE action = 'UPDATE_IMPROVEMENT_PLAN'
        AND payload -> 'changes' -> 'priority' ->> 'from' IN ('LOW', 'MEDIUM', 'HIGH')
      ORDER BY resource, created_at) f
WHERE f.resource = 'improvement_plan:' || p.id;

UPDATE prisma.improvement_plans p
SET baseline_responsible = left(f.payload -> 'changes' -> 'responsible' ->> 'from', 200)
FROM (SELECT DISTINCT ON (resource) resource, payload
      FROM audit.audit_logs
      WHERE action = 'UPDATE_IMPROVEMENT_PLAN'
        AND payload -> 'changes' -> 'responsible' IS NOT NULL
      ORDER BY resource, created_at) f
WHERE f.resource = 'improvement_plan:' || p.id;

UPDATE prisma.improvement_plans p
SET completed_at = f.created_at
FROM (SELECT DISTINCT ON (resource) resource, created_at
      FROM audit.audit_logs
      WHERE action = 'UPDATE_IMPROVEMENT_PLAN'
        AND payload -> 'changes' -> 'status' ->> 'to' = 'COMPLETED'
      ORDER BY resource, created_at DESC) f
WHERE p.status = 'COMPLETED'
  AND f.resource = 'improvement_plan:' || p.id;

ALTER TABLE prisma.improvement_plans
    ALTER COLUMN baseline_priority SET NOT NULL;
