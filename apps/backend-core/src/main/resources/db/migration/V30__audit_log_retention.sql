-- RF-TRZ-04 (PRI-325): retención mínima de 365 días de la bitácora de auditoría.
--
-- Ninguna ruta de la aplicación borra filas de audit.audit_logs (solo INSERT y SELECT, ver
-- AuditLogService), pero hasta ahora eso era una convención de código: un DELETE por error de
-- programación, por un script o a mano en la consola borraba historial sin que nada lo impidiera,
-- y los backups (30 días, ver scripts/backup/backup.sh) no alcanzan a cubrir los 12 meses. Este
-- trigger lo hace cumplir el motor: rechaza el borrado de cualquier fila con menos de 365 días y
-- deja purgar las más viejas (procedimiento en docs/RUNBOOK.md, "Retención de la bitácora").
--
-- No cubre TRUNCATE (los triggers de fila no se disparan con él): queda reservado a
-- mantenimiento deliberado del DBA y está documentado como tal en el RUNBOOK.
CREATE OR REPLACE FUNCTION audit.enforce_audit_log_retention() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.created_at > now() - INTERVAL '365 days' THEN
        RAISE EXCEPTION 'RF-TRZ-04: los registros de la bitácora se retienen al menos 365 días (registro % del %)',
            OLD.id, OLD.created_at
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_audit_logs_retention
    BEFORE DELETE ON audit.audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit.enforce_audit_log_retention();
