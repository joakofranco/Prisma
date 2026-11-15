# Instructivo: configurar Gmail como SMTP de PRISMA

Para quien administra el despliegue. Sirve para que Keycloak (invitaciones, «¿Olvidaste tu contraseña?») y Alertmanager (alertas) envíen correo desde una cuenta de Gmail. Contexto general en [Notificaciones-Email.md](Notificaciones-Email.md).

Usar una **cuenta propia del sistema** (por ejemplo `prisma.sistema@gmail.com`), nunca el buzón personal de una persona.

## 1. Preparar la cuenta de Gmail

Gmail ya no acepta la contraseña normal de la cuenta por SMTP. Hace falta una **contraseña de aplicación**:

1. Entrar a la cuenta con la sesión iniciada en <https://myaccount.google.com/security>.
2. Activar la **verificación en dos pasos** (sin ella no aparece la opción siguiente).
3. Abrir <https://myaccount.google.com/apppasswords>.
4. Poner un nombre (por ejemplo `PRISMA`) y pulsar **Crear**.
5. Copiar la clave de 16 caracteres que se muestra. Solo se ve una vez. Se puede usar con o sin espacios.

Si la cuenta es de Google Workspace y no aparece la opción, el administrador del dominio debe permitir las contraseñas de aplicación.

## 2. Datos de conexión de Gmail

| Dato | Valor |
|---|---|
| Servidor | `smtp.gmail.com` |
| Puerto con STARTTLS (recomendado) | `587` |
| Puerto con SSL directo | `465` |
| Usuario | La dirección completa, `cuenta@gmail.com` |
| Contraseña | La contraseña de aplicación de 16 caracteres |
| Remitente | La misma cuenta. Gmail reemplaza cualquier otro remitente por el de la cuenta. |

## 3. Correo del sistema (Keycloak)

En el `.env` del servidor:

```env
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_FROM=cuenta@gmail.com
SMTP_FROM_NAME=PRISMA
SMTP_USER=cuenta@gmail.com
SMTP_PASSWORD=abcdefghijklmnop
SMTP_STARTTLS=true
SMTP_SSL=false
```

Para el puerto 465 usar `SMTP_PORT=465`, `SMTP_STARTTLS=false` y `SMTP_SSL=true`.

Aplicar la configuración al realm:

```bash
make auth-policy
```

El script `scripts/keycloak/apply-auth-policy.sh` es idempotente. Sin `SMTP_HOST` no toca el SMTP del realm.

## 4. Alertas (Alertmanager)

En el mismo `.env`:

```env
ALERT_SMTP_SMARTHOST=smtp.gmail.com:587
ALERT_SMTP_FROM=cuenta@gmail.com
ALERT_SMTP_USER=cuenta@gmail.com
ALERT_SMTP_PASSWORD=abcdefghijklmnop
ALERT_SMTP_REQUIRE_TLS=true
ALERT_EMAIL_TO=destino@dominio.com
```

Reiniciar Alertmanager. Estos valores solo se usan hasta que un administrador guarde la configuración desde la plataforma (Configuración → Alertas); desde ese momento manda la guardada.

## 5. Probar

- Keycloak: en la plataforma, Configuración → Correo, botón de prueba. El correo llega a la cuenta del sistema.
- Alertmanager: Configuración → Alertas, enviar alerta de prueba.

## 6. Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| `535 5.7.8 Username and Password not accepted` | Se usó la contraseña normal en vez de la de aplicación, o el usuario no es la dirección completa. |
| No aparece «Contraseñas de aplicación» | Falta la verificación en dos pasos, o el administrador de Workspace la bloqueó. |
| Se cuelga o expira al conectar | El servidor tiene bloqueada la salida a los puertos 587 o 465. Probar con el otro puerto o abrirlo en el firewall. |
| El remitente que llega no es el configurado | Gmail fuerza la dirección de la cuenta. Usar esa misma dirección en `SMTP_FROM`. |
| Dejan de llegar correos tras cambiar la contraseña de la cuenta | Al cambiar la contraseña de Google se revocan las contraseñas de aplicación. Crear una nueva y actualizar el `.env`. |

## 7. Límites y seguridad

- Cuenta personal de Gmail: unos 500 destinatarios por día. Google Workspace: unos 2000. Al pasarlos, Gmail bloquea el envío por un tiempo. Alcanza para uso interno; para volumen mayor conviene un servicio de correo transaccional.
- La contraseña de aplicación da acceso al envío de correo de la cuenta. Guardarla solo en el `.env` del servidor, que no se sube al repositorio, y revocarla en la página de contraseñas de aplicación si se filtra.
