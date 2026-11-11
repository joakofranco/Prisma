<#ftl output_format="plainText">
<#-- PRI-240: versión en texto plano de html/executeActions.ftl (mismos textos). -->
<#assign actions = requiredActions![]>
<#assign invitation = actions?seq_contains("UPDATE_PASSWORD") && actions?seq_contains("VERIFY_EMAIL")>
<#assign hours = (linkExpiration / 60)?floor>
Hola${(user.firstName?has_content)?then(" " + user.firstName, "")}:

<#if invitation>
Te invitaron a PRISMA, la plataforma de evaluación de madurez en ciberseguridad, con el rol Evaluador interno.

Para activar tu cuenta, verificá tu email y creá tu contraseña desde este enlace:
<#else>
Se pidió que actualices tu cuenta de PRISMA. Entrá desde este enlace para completarlo:
</#if>
${link}

El enlace vence en ${hours} horas.<#if invitation> Si vence, pedile a quien te invitó que te reenvíe la invitación.</#if>

--
Este es un mensaje automático de PRISMA. Si no reconocés esta acción, podés ignorar este correo.
