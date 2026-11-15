-- RF-USR-05 (PRI-240): invitación de evaluadores internos por correo. invited_at = cuándo se envió
-- (o reenvió) la última invitación; invitation_accepted_at = su primer ingreso. Ambas en null para
-- los usuarios dados de alta con contraseña temporal («Nuevo Usuario»), que no pasan por acá.
ALTER TABLE prisma.users
    ADD COLUMN invited_at TIMESTAMPTZ,
    ADD COLUMN invitation_accepted_at TIMESTAMPTZ;
