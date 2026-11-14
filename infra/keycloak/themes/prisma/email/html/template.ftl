<#--
  PRI-366: wrapper de identidad visual para TODOS los correos de Keycloak (password-reset,
  email-verification, execute-actions, etc. -- todos importan "template.ftl" y llaman a esta
  macro). Las plantillas de contenido en sí (texto, botones, links) siguen viniendo del theme
  "keycloak" (parent=keycloak en theme.properties): acá solo se branding el encabezado/pie.

  Sin imagen: Gmail (y Outlook.com, y Outlook de escritorio anterior a 2016) no renderiza
  imágenes embebidas en base64 dentro del correo -- las muestra como ícono roto. Sin un logo
  alojado en un servidor público (la plataforma corre en local) tampoco hay otra forma confiable
  de mostrar una imagen. En su lugar, el wordmark "PRISMA" en blanco más la barra de color real
  del logo (apps/frontend/src/assets/img/prisma-logo-dark.svg, gradiente "bar": mismos cinco
  colores, acá como celdas sólidas en vez de gradiente CSS -- Outlook de escritorio tampoco
  soporta gradientes) -- reconocible como PRISMA y funciona igual en cualquier cliente.
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
            <td style="background-color:#1e293b; padding:18px 24px 14px;" align="left">
              <span style="color:#ffffff; font-size:18px; font-weight:bold; letter-spacing:0.08em;">PRISMA</span>
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin-top:8px; border-collapse:collapse;"><tr>
                <td width="28" height="4" style="background-color:#1d6bff; font-size:0; line-height:4px;">&nbsp;</td>
                <td width="28" height="4" style="background-color:#19b6a4; font-size:0; line-height:4px;">&nbsp;</td>
                <td width="28" height="4" style="background-color:#f5d90a; font-size:0; line-height:4px;">&nbsp;</td>
                <td width="28" height="4" style="background-color:#ff8a00; font-size:0; line-height:4px;">&nbsp;</td>
                <td width="28" height="4" style="background-color:#ff3d1f; font-size:0; line-height:4px;">&nbsp;</td>
              </tr></table>
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
