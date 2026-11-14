<#import "field.ftl" as field>
<#import "footer.ftl" as loginFooter>
<#import "theme-resources.ftl" as themeResourceTags>
<#macro username>
  <#assign label>
    <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
  </#assign>
  <@field.group name="username" label=label>
    <div class="${properties.kcInputGroup}">
      <div class="${properties.kcInputGroupItemClass} ${properties.kcFill}">
        <span class="${properties.kcInputClass} ${properties.kcFormReadOnlyClass}">
          <input id="kc-attempted-username" value="${auth.attemptedUsername}" readonly>
        </span>
      </div>
      <div class="${properties.kcInputGroupItemClass}">
        <button id="reset-login" class="${properties.kcFormPasswordVisibilityButtonClass} kc-login-tooltip" type="button"
              aria-label="${msg('restartLoginTooltip')}" onclick="location.href='${url.loginRestartFlowUrl}'">
            <i class="fa-sync-alt fas" aria-hidden="true"></i>
            <span class="kc-tooltip-text">${msg("restartLoginTooltip")}</span>
        </button>
      </div>
    </div>
  </@field.group>
</#macro>

<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="color-scheme" content="light dark">
    <meta name="viewport" content="width=device-width, initial-scale=1">

    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${title!}</title>
    <#if themeResources?? && themeResources.favicons?has_content>
        <@themeResourceTags.renderFavicons themeResources.favicons url.resourcesPath />
    <#else>
        <link rel="icon" href="${url.resourcesPath}/img/favicon.ico" />
    </#if>
    <#if themeResources?? && themeResources.stylesCommon?has_content>
        <@themeResourceTags.renderStyles themeResources.stylesCommon url.resourcesCommonPath />
    <#elseif properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if themeResources?? && themeResources.styles?has_content>
        <@themeResourceTags.renderStyles themeResources.styles url.resourcesPath />
    <#elseif properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <script blocking="render">
        <#outputformat "JavaScript">
        (function () {
            var stored = null;
            try { stored = localStorage.getItem("prisma:theme"); } catch (e) {}
            var dark = stored === "dark" || (stored !== "light" && window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
            var cl = document.documentElement.classList;
            cl.toggle("prisma-dark", dark);
            cl.toggle(${properties.kcDarkModeClass?c}, dark);
        })();
        </#outputformat>
    </script>
    <#if themeResources?? && themeResources.scripts?has_content>
        <@themeResourceTags.renderScripts themeResources.scripts url.resourcesPath "text/javascript" />
    <#elseif properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};

        startSessionPolling(
            ${url.ssoLoginInOtherTabsUrl?c}
        );
        </#outputformat>
    </script>
    <script type="module">
        document.addEventListener("click", (event) => {
            const link = event.target.closest("a[data-once-link]");

            if (!link) {
                return;
            }

            if (link.getAttribute("aria-disabled") === "true") {
                event.preventDefault();
                return;
            }

            const { disabledClass } = link.dataset;

            if (disabledClass) {
                link.classList.add(...disabledClass.trim().split(/\s+/));
            }

            link.setAttribute("role", "link");
            link.setAttribute("aria-disabled", "true");
        });
    </script>
    <#if authenticationSession??>
        <script type="module">
             <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};

            checkAuthSession(
                ${authenticationSession.authSessionIdHash?c}
            );
            </#outputformat>
        </script>
    </#if>
</head>

<body id="keycloak-bg" class="prisma-body" data-page-id="login-${pageId}">
<div class="prisma-shell">
  <div class="prisma-topbar" aria-hidden="true"></div>
  <div class="prisma-theme-switch" role="group" aria-label="${msg("prismaThemeLabel")}">
    <button type="button" data-theme-choice="light" aria-pressed="false" title="${msg("prismaThemeLight")}" aria-label="${msg("prismaThemeLight")}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>
    </button>
    <button type="button" data-theme-choice="system" aria-pressed="false" title="${msg("prismaThemeSystem")}" aria-label="${msg("prismaThemeSystem")}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/></svg>
    </button>
    <button type="button" data-theme-choice="dark" aria-pressed="false" title="${msg("prismaThemeDark")}" aria-label="${msg("prismaThemeDark")}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>
    </button>
  </div>
  <div class="prisma-backdrop" aria-hidden="true"></div>
  <div class="prisma-layout">
    <header id="kc-header" class="prisma-brand">
      <img class="prisma-logo prisma-logo-light" src="${url.resourcesPath}/img/prisma-logo-light.svg" alt="PRISMA - Plataforma de Revisión Integral de Seguridad y Marcos de Auditorías" />
      <img class="prisma-logo prisma-logo-dark" src="${url.resourcesPath}/img/prisma-logo-dark.svg" alt="" aria-hidden="true" />
    </header>
    <div class="prisma-divider" aria-hidden="true"></div>
    <main class="prisma-card">
      <h1 class="prisma-title" id="kc-page-title"><#nested "header"></h1>

      <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
        <#if displayRequiredFields>
          <p class="prisma-hint"><span class="${properties.kcInputRequiredClass!}">*</span> ${msg("requiredFields")}</p>
        </#if>
      <#else>
        <#if displayRequiredFields>
          <p class="prisma-hint"><span class="${properties.kcInputRequiredClass!}">*</span> ${msg("requiredFields")}</p>
        </#if>
        <div class="${properties.kcFormClass} ${properties.kcContentWrapperClass}">
          <#nested "show-username">
          <@username />
        </div>
      </#if>

      <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
        <div class="prisma-alert prisma-alert-${message.type}" role="alert">
          <span class="prisma-alert-icon" aria-hidden="true">
            <#if message.type = 'success'><span class="${properties.kcFeedbackSuccessIcon!}"></span></#if>
            <#if message.type = 'warning'><span class="${properties.kcFeedbackWarningIcon!}"></span></#if>
            <#if message.type = 'error'><span class="${properties.kcFeedbackErrorIcon!}"></span></#if>
            <#if message.type = 'info'><span class="${properties.kcFeedbackInfoIcon!}"></span></#if>
          </span>
          <span class="kc-feedback-text">${message.summary}</span>
        </div>
      </#if>

      <#nested "form">

      <#if auth?has_content && auth.showTryAnotherWayLink()>
        <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post" novalidate="novalidate">
          <input type="hidden" name="tryAnotherWay" value="on"/>
          <a id="try-another-way" href="javascript:document.forms['kc-select-try-another-way-form'].requestSubmit()"
              class="prisma-btn prisma-btn-secondary">
            ${msg("doTryAnotherWay")}
          </a>
        </form>
      </#if>

      <#if switchOrganizationEnabled?? && switchOrganizationEnabled>
        <form id="kc-switch-organization-form" action="${url.loginAction}" method="post" novalidate="novalidate">
          <input type="hidden" name="switchOrganization" value="true"/>
          <a id="switch-organization" href="javascript:document.forms['kc-switch-organization-form'].requestSubmit()"
              class="prisma-btn prisma-btn-secondary">
            ${msg("doSwitchOrganization")}
          </a>
        </form>
      </#if>

      <#nested "socialProviders">

      <#if displayInfo>
        <div id="kc-info" class="prisma-info">
          <div id="kc-info-wrapper">
            <#nested "info">
          </div>
        </div>
      </#if>

      <@loginFooter.content/>
    </main>
  </div>
</div>
<script>
    <#outputformat "JavaScript">
    (function () {
        var KEY = "prisma:theme";
        var root = document.documentElement;
        var mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
        var buttons = document.querySelectorAll("[data-theme-choice]");
        function choice() {
            var stored = null;
            try { stored = localStorage.getItem(KEY); } catch (e) {}
            return stored === "dark" || stored === "light" ? stored : "system";
        }
        function apply() {
            var c = choice();
            var dark = c === "dark" || (c === "system" && mq && mq.matches);
            root.classList.toggle("prisma-dark", dark);
            root.classList.toggle(${properties.kcDarkModeClass?c}, dark);
            buttons.forEach(function (b) {
                b.setAttribute("aria-pressed", String(b.getAttribute("data-theme-choice") === c));
            });
        }
        buttons.forEach(function (b) {
            b.addEventListener("click", function () {
                var c = b.getAttribute("data-theme-choice");
                try {
                    if (c === "system") { localStorage.removeItem(KEY); } else { localStorage.setItem(KEY, c); }
                } catch (e) {}
                apply();
            });
        });
        if (mq && mq.addEventListener) { mq.addEventListener("change", apply); }
        apply();
    })();
    </#outputformat>
</script>
<script>
    (function () {
        var pwd = document.getElementById("password");
        var caps = document.getElementById("prisma-caps");
        if (pwd && caps) {
            var check = function (e) {
                caps.hidden = !(e.getModifierState && e.getModifierState("CapsLock"));
            };
            pwd.addEventListener("keydown", check);
            pwd.addEventListener("keyup", check);
            pwd.addEventListener("blur", function () { caps.hidden = true; });
        }
        document.querySelectorAll("form.prisma-form").forEach(function (f) {
            f.addEventListener("submit", function () {
                var b = f.querySelector(".prisma-btn-primary");
                if (b) { b.classList.add("prisma-loading"); }
            });
        });
    })();
</script>
<div id="prisma-env" class="prisma-env" role="status" hidden>
  <div class="prisma-env-bar" aria-hidden="true"></div>
  <div class="prisma-env-label"></div>
</div>
<script>
    (function () {
        var labels = { development: "Entorno de desarrollo · datos de prueba", staging: "Entorno de pruebas (staging)" };
        fetch("/api/version", { headers: { Accept: "application/json" } })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (info) {
                var env = info && info.environment;
                if (!env || env === "production") { return; }
                var box = document.getElementById("prisma-env");
                box.setAttribute("data-env", env);
                box.querySelector(".prisma-env-label").textContent = labels[env] || ("Entorno: " + env);
                box.hidden = false;
                var tag = env === "development" ? "DEV" : env.toUpperCase().slice(0, 7);
                document.title = "[" + tag + "] " + document.title;
            })
            .catch(function () {});
    })();
</script>
</body>
</html>
</#macro>
