<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=false displayMessage=!messagesPerField.existsError('username'); section>
    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "form">
        <p class="prisma-subtitle">
            <#if realm.duplicateEmailsAllowed>${msg("emailInstructionUsername")}<#else>${msg("emailInstruction")}</#if>
        </p>
        <form id="kc-reset-password-form" class="prisma-form" action="${url.loginAction}" method="post">
            <#assign label>
                <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
            </#assign>
            <#assign fieldError = messagesPerField.get('username')>
            <div class="prisma-field">
                <label for="username" class="prisma-sr-only">${label}</label>
                <span class="prisma-input<#if fieldError?has_content> prisma-input-error</#if>">
                    <svg class="prisma-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7"/></svg>
                    <input id="username" name="username" type="text" value="${(auth.attemptedUsername!'')}" placeholder="${msg("prismaResetPlaceholder")}" autofocus autocomplete="username"
                        aria-invalid="<#if fieldError?has_content>true</#if>" />
                </span>
                <#if fieldError?has_content>
                    <div class="prisma-field-error" id="input-error-username" aria-live="polite">${fieldError}</div>
                </#if>
            </div>

            <button class="prisma-btn prisma-btn-primary" id="kc-form-buttons" type="submit">${msg("doSubmit")}</button>

            <div class="prisma-links prisma-links-divided">
                <a href="${url.loginUrl}" class="prisma-back">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19 12H5"/><path d="M11 6l-6 6 6 6"/></svg>
                    ${msg("backToLogin")}
                </a>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
