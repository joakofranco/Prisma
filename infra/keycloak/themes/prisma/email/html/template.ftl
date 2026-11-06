<#--
  PRI-366: wrapper de identidad visual para TODOS los correos de Keycloak (password-reset,
  email-verification, execute-actions, etc. -- todos importan "template.ftl" y llaman a esta
  macro). Las plantillas de contenido en sí (texto, botones, links) siguen viniendo del theme
  "keycloak" (parent=keycloak en theme.properties): acá solo se branding el encabezado/pie.

  Sin logo rasterizado (no existe ninguno en el repo -- la app tampoco usa uno, ver
  AppHeader.vue: es un icono + texto). Se usa una banda de color + el wordmark "PRISMA" en el
  mismo azul (#2563eb, Tailwind blue-600) que usa el header real de la app, en vez de depender
  de una imagen externa (muchos clientes de correo bloquean imágenes remotas por defecto, lo
  que haría perder el branding en la mayoría de las bandejas de entrada).
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
