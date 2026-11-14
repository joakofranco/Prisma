CREATE TABLE prisma.alert_settings (
    id            SMALLINT     PRIMARY KEY CHECK (id = 1),
    recipients    VARCHAR(500) NOT NULL,
    smtp_host     VARCHAR(255) NOT NULL,
    smtp_port     INTEGER      NOT NULL CHECK (smtp_port BETWEEN 1 AND 65535),
    smtp_from     VARCHAR(255) NOT NULL,
    auth_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    smtp_user     VARCHAR(255),
    password_set  BOOLEAN      NOT NULL DEFAULT FALSE,
    require_tls   BOOLEAN      NOT NULL DEFAULT FALSE,
    configured    BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO prisma.alert_settings (id, recipients, smtp_host, smtp_port, smtp_from)
VALUES (1, 'admin@prisma.uy', 'mailpit', 1025, 'alertas@prisma.uy');
