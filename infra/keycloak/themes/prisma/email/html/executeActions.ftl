<#--
  PRI-240 (RF-USR-05): correo de «acciones requeridas» en español. PRISMA lo usa para invitar a
  evaluadores internos (UserService.invite pide VERIFY_EMAIL + UPDATE_PASSWORD con 72 h de
  vigencia). Si alguien lo dispara con otras acciones desde la consola de Keycloak, sale el texto
  genérico. Reemplaza al executeActions.ftl del tema base (en inglés); el encabezado y el pie
  siguen saliendo de template.ftl.
-->
<#import "template.ftl" as layout>
<#assign actions = requiredActions![]>
<#assign invitation = actions?seq_contains("UPDATE_PASSWORD") && actions?seq_contains("VERIFY_EMAIL")>
<#assign hours = (linkExpiration / 60)?floor>
<@layout.emailLayout>
<p style="margin:0 0 12px;">Hola${(user.firstName?has_content)?then(" " + user.firstName, "")}:</p>
<#if invitation>
<p style="margin:0 0 12px;">Te invitaron a <strong>PRISMA</strong>, la plataforma de evaluación de madurez en ciberseguridad, con el rol <strong>Evaluador interno</strong>.</p>
<p style="margin:0 0 16px;">Para activar tu cuenta, verificá tu email y creá tu contraseña:</p>
<#else>
<p style="margin:0 0 16px;">Se pidió que actualices tu cuenta de PRISMA. Entrá desde este enlace para completarlo:</p>
</#if>
<p style="margin:0 0 20px;"><a href="${link}" style="display:inline-block; padding:12px 22px; border-radius:6px; background-color:#2563eb; color:#ffffff; font-weight:bold; text-decoration:none;"><#if invitation>Crear mi contraseña<#else>Actualizar mi cuenta</#if></a></p>
<p style="margin:0 0 8px; font-size:13px; color:#475569;">El enlace vence en <strong>${hours} horas</strong>.<#if invitation> Si vence, pedile a quien te invitó que te reenvíe la invitación.</#if></p>
<p style="margin:0; font-size:13px; color:#475569;">Si el botón no funciona, copiá este enlace en el navegador: <a href="${link}" style="color:#1d4ed8; word-break:break-all;">${link}</a></p>
</@layout.emailLayout>
