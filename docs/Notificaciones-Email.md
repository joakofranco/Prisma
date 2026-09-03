# 📧 Notificaciones por email (SMTP) — PRISMA

> Cómo PRISMA envía el email de "¿Olvidaste tu contraseña?" (y demás notificaciones de cuenta),
> cómo configurarlo desde la aplicación, y por qué no existe un `EmailService` propio en
> backend-core.

## 1. Por qué delega en Keycloak en vez de tener su propio envío de emails

PRISMA usa Keycloak como **única fuente de verdad para autenticación** (ver
[`Certificados-TLS.md`](Certificados-TLS.md) y [`SECURITY.md`](SECURITY.md)) — el login, el
cambio de contraseña y la recuperación de cuenta ya viven ahí, no en backend-core. Keycloak trae
de fábrica el flujo completo de "¿Olvidaste tu contraseña?" (el realm de PRISMA ya lo tiene
habilitado vía `resetPasswordAllowed: true` en `infra/keycloak/realm-prisma.json`); lo único que
faltaba era decirle **con qué servidor SMTP enviar el correo**.

Construir un segundo mecanismo de recuperación de contraseña en backend-core (tabla de tokens de
reseteo, `JavaMailSender`, endpoint público `/forgot-password`, etc.) hubiera creado exactamente
el problema que este proyecto evitó al migrar todo el login a Keycloak: dos caminos distintos para
cambiar la contraseña de un usuario, potencialmente desincronizados. En cambio, PRISMA expone una
pantalla de administración que **configura el SMTP del realm de Keycloak** vía su Admin REST API
(`KeycloakAdminClient`) — Keycloak sigue siendo el único que efectivamente decide cuándo y a quién
mandar un email de cuenta.

```
Admin global (PRISMA) ──PUT /api/admin/email-settings──▶ backend-core
                                                              │
                                                              ▼ Admin REST API
                                                          Keycloak (smtpServer del realm)
                                                              │
Usuario final ──"¿Olvidaste tu contraseña?"─────────────────▶│──envía el email──▶ su bandeja
```

## 2. Configurar el correo

1. Iniciar sesión con un usuario `PRISMA_ADMIN`.
2. Ir a **Configuración** en el menú lateral (`/settings`).
3. Completar el servidor SMTP, puerto, email remitente y, si el servidor lo requiere,
   autenticación (usuario/contraseña) y STARTTLS/SSL.
4. Guardar.

Para probarlo sin usar un usuario real: cerrar sesión y hacer clic en "¿Olvidaste tu contraseña?"
en la pantalla de login con el email de cualquier usuario existente — si el SMTP está bien
configurado, el correo con el link de reseteo llega a esa bandeja.

### Un detalle importante: la contraseña SMTP siempre hay que reingresarla

Keycloak **nunca devuelve la contraseña SMTP guardada** al leerla vía su Admin REST API (la
enmascara como `**********`, incluso para llamadas administrativas) — es una medida de seguridad
del propio Keycloak, no algo que PRISMA pueda evitar. Como consecuencia, la pantalla de
Configuración jamás pre-completa ese campo, y **hay que volver a escribir la contraseña cada vez
que se guarda un cambio**, aunque lo que se esté editando sea otro campo (el remitente, el puerto,
etc.). Si el servidor no requiere autenticación (por ejemplo, un relay interno sin credenciales),
alcanza con dejar "Requiere autenticación" sin marcar.

## 3. Qué pasa del lado de Keycloak (para quien lo necesite depurar)

- `PUT /api/admin/email-settings` (backend-core, sólo `PRISMA_ADMIN`) termina en un
  `PUT /admin/realms/prisma` con `{"smtpServer": {...}}` contra la Admin REST API de Keycloak
  (`KeycloakAdminClient.updateSmtpConfig`). Keycloak **reemplaza el objeto `smtpServer` entero**
  (no hace merge campo a campo) y **valida el resultado** — un remitente vacío o mal formado hace
  fallar el guardado con 400 — por eso backend-core siempre manda el objeto completo, nunca un
  delta.
- El client de servicio `prisma-backend` necesita el rol `manage-realm` de `realm-management`
  (además de `manage-users`/`view-users`/`view-realm`, que ya tenía) para poder tocar la
  configuración del realm — ver `infra/keycloak/realm-prisma.json`.
- El realm ya trae `resetPasswordAllowed: true`: sin SMTP configurado, un usuario puede pedir el
  reseteo, pero Keycloak falla en silencio al intentar mandar el correo (queda en los logs del
  contenedor `keycloak`, no en los de backend-core).

## 4. Qué NO hace esta pantalla

- No envía un "email de prueba" con un botón dedicado: el endpoint de Keycloak para eso
  (`POST /admin/realms/{realm}/testSMTPConnection`) sólo funciona en el contexto de un admin
  humano logueado interactivamente en la consola de Keycloak (necesita poder resolver el email del
  admin actual) — no funciona llamado por un client de servicio como `prisma-backend`, que es
  como backend-core habla con Keycloak. La forma de probar la configuración es la del punto 2:
  pedir "¿Olvidaste tu contraseña?" con un usuario real.
- No guarda ni ve la contraseña SMTP en ningún lado del lado de PRISMA (ni en Postgres ni en
  ninguna otra parte) — vive únicamente en la base de datos de Keycloak.

## Referencias

- Backend: `EmailSettingsController`, `EmailSettingsService`,
  `KeycloakAdminClient.getSmtpConfig`/`updateSmtpConfig`.
- Frontend: `SettingsView.vue` (`/settings`, sólo `PRISMA_ADMIN`).
- Arquitectura de autenticación: [`Certificados-TLS.md`](Certificados-TLS.md).
- Modelo de seguridad general: [`SECURITY.md`](SECURITY.md).
