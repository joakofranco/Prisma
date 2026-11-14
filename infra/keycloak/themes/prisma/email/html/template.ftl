<#--
  PRI-366: wrapper de identidad visual para TODOS los correos de Keycloak (password-reset,
  email-verification, execute-actions, etc. -- todos importan "template.ftl" y llaman a esta
  macro). Las plantillas de contenido en sí (texto, botones, links) siguen viniendo del theme
  "keycloak" (parent=keycloak en theme.properties): acá solo se branding el encabezado/pie.

  Sin imagen: Gmail (y Outlook.com, y Outlook de escritorio anterior a 2016) no renderiza
  imágenes embebidas en base64 dentro del propio correo -- las muestra como ícono roto, visto
  en un correo real (Mailpit, que no aplica esa restricción, sí la mostraba, por eso no se notó
  antes). La alternativa confiable -- un logo alojado en un servidor público -- no existe todavía
  (la plataforma solo corre en local). Por eso el logo es texto + color: un cuadrado y el
  wordmark "PRISMA" en el mismo azul (#2563eb, Tailwind blue-600) que el header real de la app
  -- funciona igual en cualquier cliente de correo, sin depender de que cargue una imagen.
-->
<#macro emailLayout>
<html>
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
</head>
<body style="margin:0; padding:0; background-color:#f1f5f9; font-family:Arial,Helvetica,sans-serif;">
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f1f5f9; padding:24px 0;">
    <tr>
      <td align="center">
        <table role="presentation" width="480" cellpadding="0" cellspacing="0" style="background-color:#ffffff; border-radius:8px; overflow:hidden; border:1px solid #e2e8f0;">
          <tr>
            <td style="background-color:#1e293b; padding:20px 24px;" align="left">
              <span style="color:#3b82f6; font-size:20px; font-weight:bold; letter-spacing:0.08em;">&#9632;</span>
              <span style="color:#ffffff; font-size:18px; font-weight:bold; letter-spacing:0.08em; vertical-align:middle;">PRISMA</span>
            </td>
          </tr>
          <tr>
            <td style="padding:32px 24px; color:#1e293b; font-size:14px; line-height:1.6;">
              <#nested>
            </td>
          </tr>
          <tr>
            <td style="padding:16px 24px; background-color:#f8fafc; color:#64748b; font-size:12px;" align="center">
              Este es un mensaje automático de PRISMA. Si no reconocés esta acción, podés ignorar este correo.
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>
</#macro>
