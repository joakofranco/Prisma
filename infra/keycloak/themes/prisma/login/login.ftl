<#import "template.ftl" as layout>
<#import "buttons.ftl" as buttons>
<#import "social-providers.ftl" as identityProviders>
<#import "passkeys.ftl" as passkeys>
<@layout.registrationLayout displayMessage=false displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>

    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <p class="prisma-subtitle">${msg("prismaLoginSubtitle")}</p>
        <#assign loginError = messagesPerField.getFirstError('username','password')>
        <#if loginError?has_content>
            <div class="prisma-alert prisma-alert-error" role="alert" id="input-error">
                <span class="prisma-alert-icon" aria-hidden="true"><span class="${properties.kcFeedbackErrorIcon!}"></span></span>
                <span class="kc-feedback-text">${loginError}</span>
            </div>
        <#elseif message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div class="prisma-alert prisma-alert-${message.type}" role="alert">
                <span class="prisma-alert-icon" aria-hidden="true"><span class="<#if message.type = 'success'>${properties.kcFeedbackSuccessIcon!}<#elseif message.type = 'warning'>${properties.kcFeedbackWarningIcon!}<#elseif message.type = 'error'>${properties.kcFeedbackErrorIcon!}<#else>${properties.kcFeedbackInfoIcon!}</#if>"></span></span>
                <span class="kc-feedback-text">${message.summary}</span>
            </div>
        </#if>
        <div id="kc-form">
          <div id="kc-form-wrapper">
            <#if realm.password>
                <form id="kc-form-login" class="prisma-form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <#if !usernameHidden??>
                        <#assign label>
                            <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                        </#assign>
                        <div class="prisma-field">
                            <label for="username" class="prisma-sr-only">${label}</label>
                            <span class="prisma-input<#if loginError?has_content> prisma-input-error</#if>">
                                <svg class="prisma-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7"/></svg>
                                <input id="username" name="username" type="text" value="${(login.username!'')}" placeholder="<#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>" autofocus
                                    autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}"
                                    aria-invalid="<#if loginError?has_content>true</#if>" />
                            </span>
                        </div>
                    </#if>
                    <div class="prisma-field">
                        <label for="password" class="prisma-sr-only">${msg("password")}</label>
                        <span class="prisma-input<#if loginError?has_content> prisma-input-error</#if>">
                            <svg class="prisma-input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>
                            <input id="password" name="password" type="password" placeholder="${msg("password")}" autocomplete="current-password"
                                <#if usernameHidden??>autofocus</#if> aria-invalid="<#if loginError?has_content>true</#if>" />
                            <button class="prisma-toggle" type="button" aria-label="${msg('showPassword')}"
                                aria-controls="password" data-password-toggle
                                data-icon-show="${properties.kcFormPasswordVisibilityIconShow}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide}"
                                data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}" id="password-show-password">
                                <i class="${properties.kcFormPasswordVisibilityIconShow}" aria-hidden="true"></i>
                            </button>
                        </span>
                        <div class="prisma-caps" id="prisma-caps" role="status" hidden>${msg("prismaCapsLock")}</div>
                    </div>

                    <#if realm.rememberMe && !usernameHidden??>
                        <label class="prisma-check" for="rememberMe">
                            <input type="checkbox" id="rememberMe" name="rememberMe" <#if login.rememberMe??>checked</#if> />
                            <span>${msg("rememberMe")}</span>
                        </label>
                    </#if>

                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <button class="prisma-btn prisma-btn-primary" name="login" id="kc-login" type="submit"><span class="prisma-spinner" aria-hidden="true"></span><span class="prisma-btn-text">${msg("doLogIn")}</span></button>

                    <#if realm.resetPasswordAllowed>
                        <div class="prisma-links">
                            <a href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                        </div>
                    </#if>
                </form>
            </#if>
            </div>
        </div>
        <@passkeys.conditionalUIData />
    <#elseif section = "socialProviders" >
        <#if realm.password && social.providers?? && social.providers?has_content>
            <@identityProviders.show social=social/>
        </#if>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration-container">
                <div id="kc-registration">
                    <span>${msg("noAccount")} <a href="${url.registrationUrl}">${msg("doRegister")}</a></span>
                </div>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
