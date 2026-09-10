#!/usr/bin/env python3
"""
Genera docs/mcu-5.0/bugasura-import.csv: el backlog completo de PRISMA en el formato de
importación de Bugasura (mismas columnas que docs/mcu-5.0/PlanillaBugasura.csv).

Fuentes:
  - docs/HistoriasDeUsuario.md  -> 15 áreas (Epic, Completed) + 68 historias (Story, Completed)
  - listas PENDING / BUGS de abajo -> el refactor MCU/JDK y la deuda técnica documentada
    (docs/PLAN-CAMBIOS-EQUIPO.md, docs/{SECURITY,Testing,API,RUNBOOK}.md) como Task / Bug (New)

Columnas: Title,Details,Overview,Priority,Type,Status,Assignees,Tags,Estimation
  - Assignees y Estimation quedan vacías (las completa el equipo en Bugasura).
  - Cada celda es de UNA sola línea (Bugasura no espera saltos de línea embebidos).

Uso:  python scripts/bugasura/build_import.py    (o `make bugasura-build`)
"""
import csv
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = Path(__file__).resolve().parents[2]
HU_MD = REPO / "docs" / "HistoriasDeUsuario.md"
OUT = REPO / "docs" / "mcu-5.0" / "bugasura-import.csv"
HEADER = ["Title", "Details", "Overview", "Priority", "Type", "Status", "Assignees", "Tags", "Estimation"]

# nº de área -> (tag corto, prioridad del Epic, frase de propósito)
AREAS = {
    1: ("Autenticación", "P1", "Acceso a la plataforma y gestión de la cuenta propia, con el login delegado 100% a Keycloak."),
    2: ("Organizaciones", "P2", "Alta, edición y baja lógica de las organizaciones evaluadas, preservando su historial."),
    3: ("Usuarios", "P2", "Gestión de usuarios, roles y su acotamiento por organización (multi-tenant)."),
    4: ("Catálogo", "P1", "Estructura jerárquica versionada del marco MCU 5.0 (Función → Categoría → Subcategoría → Requisito → Control)."),
    5: ("Perfiles", "P2", "Subconjuntos curados de controles para acotar una evaluación a lo relevante de un sector."),
    6: ("Evaluaciones", "P1", "Ciclo de vida de la evaluación, autoevaluación control por control y cálculo de madurez."),
    7: ("Evidencias", "P2", "Carga de evidencia documental por control e indexación por IA para consulta durante la auditoría."),
    8: ("Auditoría", "P2", "Revisión de una evaluación en auditoría y registro de observaciones / no conformidades."),
    9: ("Mejora", "P2", "Plan de mejora: sugerencias automáticas a partir de las brechas y seguimiento de las acciones."),
    10: ("Reportes", "P2", "Exportación de los resultados de madurez a PDF y Excel."),
    11: ("Dashboard", "P3", "Panel resumen con KPIs y madurez por función acotado a lo que cada rol puede ver."),
    12: ("UX", "P3", "Experiencia transversal: notificaciones, tooltips, aislamiento multi-tenant transparente, tema claro/oscuro."),
    13: ("Configuración", "P3", "Configuración de la plataforma (SMTP de Keycloak, administración del catálogo)."),
    14: ("Observabilidad", "P3", "Bitácora de acciones sensibles, intentos de login fallidos, logs y métricas centralizadas."),
    15: ("Infraestructura", "P3", "Levantar y desplegar el stack (Docker Compose, Kubernetes, CI/CD)."),
}

# Overrides de prioridad por código de HU (default = prioridad del área)
HU_PRIORITY = {
    "HU-USR-04": "P1",  # protección contra auto-eliminación / borrar al admin global
    "HU-USR-05": "P2",
    "HU-UX-03": "P1",   # aislamiento multi-tenant
    "HU-EVAL-04": "P1", # cálculo de madurez
    "HU-EVAL-05": "P1", # transiciones de estado
    "HU-EVAL-06": "P1", # rechazo de transiciones inválidas
    "HU-EVI-05": "P2",  # evidencia bloqueada en auditoría
    "HU-AUTH-05": "P2",
    "HU-EVAL-03": "P3", "HU-EVAL-07": "P3",
    "HU-EVAL-08": "P4", "HU-EVAL-09": "P4", "HU-EVAL-10": "P4",
    "HU-UX-01": "P4", "HU-UX-02": "P4", "HU-UX-04": "P4",
    "HU-CAT-03": "P3", "HU-CAT-04": "P4",
    "HU-ORG-02": "P3", "HU-ORG-04": "P3",
    "HU-EVI-07": "P3", "HU-EVI-08": "P3",
    "HU-DASH-02": "P3",
    "HU-OBS-02": "P4", "HU-OBS-03": "P4",  # observabilidad de infra / LogRocket
    "HU-INFRA-02": "P4",
}

PENDING = [
    ("pkg0 — pom.xml: config de maven-surefire/failsafe para JDK 25",
     "Agregar -XX:+EnableDynamicAgentLoading -Djdk.attach.allowAttachSelf=true -Xshare:off (con @{argLine} tardío para no pisar JaCoCo) a surefire y failsafe. Sin esto los @SpringBootTest + Testcontainers revientan al forkear la VM en JDK 25 (self-attach de ByteBuddy). Opcional: <java.version> 21 -> 25.",
     "Como equipo de desarrollo, quiero poder correr los tests de integración en JDK 25 localmente, para no depender de CI para validar cambios de backend.",
     "P1", "Build"),
    ("pkg1 — Endpoint GET /api/catalog/{version}/controls (lista plana de controles)",
     "Nuevo CatalogControlFlatDto (id, code, description, targetLevel, requirementCode, requirementDescription, domain) y CatalogService.listControlsFlat: 404 si la versión no existe, ordena por (dominio, nº requisito, nº control) con comparación numérica. @Size(max=5000) en CreateCommunityProfileDto.controlIds.",
     "Como frontend del selector de perfiles, quiero una lista plana y ordenada de todos los controles de una versión, para poder renderizar un selector eficiente sin recorrer el árbol completo.",
     "P2", "Perfiles"),
    ("pkg1 — Selector de perfiles nuevo (CommunityProfileControlPicker.vue)",
     "Componente que reemplaza el v-for anidado: controles agrupados por dominio en secciones colapsables (colapsadas por defecto), checkbox tri-estado por dominio, buscador (código + descripción), filtro por nivel objetivo (1-4), botones Seleccionar todo / Limpiar / Seleccionar visibles. form.controlIds sigue siendo string[] de UUIDs, el payload no cambia.",
     "Como PRISMA_ADMIN, quiero armar un perfil comunitario seleccionando controles de a dominios enteros y con búsqueda, para no tener que tildar cientos de casillas una por una hasta que la sesión expire.",
     "P2", "Perfiles"),
    ("pkg1 — Duplicar-desde-perfil e importar/exportar CSV/JSON en el alta de perfil",
     "En el modal de creación de perfil: BaseSelect para clonar los controles de un perfil existente de la misma versión; exportar la selección a CSV (una columna code) y volver a importar .csv/.json (códigos -> ids; códigos desconocidos en un alert, no rompen; opción de sumar a la selección actual).",
     "Como PRISMA_ADMIN, quiero partir de un perfil existente o de un archivo, para no rearmar de cero perfiles parecidos.",
     "P3", "Perfiles"),
    ("pkg1 — Refresh silencioso del token de Keycloak",
     "keycloak-js onTokenExpired + setInterval(updateToken, 60s); el interceptor 401 de api.ts intenta refrescar (await refreshToken()) y sólo desloguea si falla, con guarda _retried para no reintentar en loop.",
     "Como usuario, quiero seguir trabajando sin que se me corte la sesión en medio de una tarea larga, para no perder avance por un token vencido.",
     "P2", "Autenticación"),
    ("pkg2 — Pestaña 'Tablas y promedios' en resultados de madurez",
     "Nueva pestaña dentro de la card de resultados (sin sacar la vista actual): tabla con nombre + sigla + nivel por función, tabla por categoría marcada por función, y un gráfico a nivel subcategoría (más granular que el de funciones). Todos los niveles = media aritmética plana de las partes, 2 decimales. Referencia visual: Graficos.png.",
     "Como cualquier rol viendo una evaluación terminada, quiero ver el detalle por función y por categoría en tablas y un gráfico más granular, para leer los resultados como en la planilla de Agesic.",
     "P2", "Reportes"),
    ("pkg2 — Composable useMaturitySummary (media plana sobre las 103 subcategorías)",
     "useMaturitySummary(results, catalogFunctions) devuelve { general, byFunction, byCategory, categoryRadarItems }: promedia sobre TODAS las subcategorías del árbol del catálogo (subcategoría sin fila = nivel 0), no sólo las presentes en results. Cubierto por useMaturitySummary.spec.ts.",
     "Como frontend de resultados, quiero un único cálculo de promedios alineado a la planilla de Agesic, para que las tablas y el dashboard muestren el mismo número.",
     "P2", "Reportes"),
    ("pkg2 — MaturityRadarChart con 'Nivel objetivo' opcional",
     "withDefaults(defineProps<{items; showTarget?: boolean}>(), { showTarget: true }); el dataset 'Nivel objetivo' sólo se agrega si showTarget. Permite reusar el radar a nivel categoría sin la línea de objetivo.",
     "Como frontend, quiero reusar el gráfico radar para distintos niveles de agregación, para no duplicar el componente.",
     "P4", "Reportes"),
    ("pkg2 — Stack MVP mínimo (docker-compose.mvp.yml + make mvp-up)",
     "Override de compose con perfil mvp: sólo front, back, keycloak, postgres, redis, nginx (HTTPS) — sin Ollama/LLM, sin observabilidad, sin MinIO. backend-ai con depends_on: !reset [] para soltar ollama.",
     "Como desarrollador, quiero levantar sólo lo mínimo para cargar catálogos y hacer evaluaciones, para no tener que esperar y alimentar a Ollama y todo el stack de observabilidad.",
     "P3", "Infraestructura"),
    ("pkg3 — Alinear calculateMaturity a la fórmula de la planilla de Agesic",
     "EvaluationService.calculateMaturity puntúa la madurez de una subcategoría sobre TODOS los controles del marco ubicados en ella (un control sin respuesta 'cumple' frena el nivel), no sólo los del perfil. Emite 103 filas MaturityResult por evaluación 5.x. Baja el globalMaturity de toda evaluación con perfil. Reproduce las 3 planillas 103/103 (Básico 1.165, Estándar 1.689, Avanzado 2.029).",
     "Como responsable de una organización, quiero que el nivel de madurez que muestra PRISMA sea idéntico al que da la planilla oficial de Agesic, para poder confiar en el número y comunicarlo.",
     "P1", "Evaluaciones"),
    ("pkg3 — Mapeo curado control↔subcategoría (V18 + effectiveSubcategories + importCatalog)",
     "Tabla catalog_control_subcategories (V18, sólo DDL), CatalogControl.effectiveSubcategories() (usa el mapeo curado si existe, si no cae al de requisito→subcategoría), y CatalogService.importCatalog enlaza cada control listado bajo un bloque subcategoría→requisito. Agesic usa ~1013 pares curados vs ~1921 del cruce requisito→subcategoría.",
     "Como sistema, quiero calcular la madurez de cada subcategoría sólo sobre los controles que Agesic efectivamente ubicó en ella, para reproducir su modelo.",
     "P2", "Catálogo"),
    ("pkg3 — Seed 5.0 alineado a Agesic 2025 (V19 + scripts/mcu50/build.py)",
     "Migración generada V19__mcu50_align_agesic_2025.sql: control nuevo OR.5-10, los 1013 pares catalog_control_subcategories y la membresía de los 3 perfiles sembrados (Básico 165 / Estándar 234 / Avanzado 309, = columna 'Línea Base' de cada planilla). scripts/mcu50/build.py la regenera desde docs/mcu-5.0/Planilla MCU 5.0 *.xlsx. Reemplaza los ~12 scripts de 'MCU 5.2'; ya no hay versión de catálogo separada.",
     "Como equipo de despliegue, quiero que un deploy limpio siembre el catálogo y los perfiles ya alineados a las planillas 2025, para no tener que importarlos a mano ni mantener dos versiones casi idénticas.",
     "P1", "Catálogo"),
    ("pkg3 — MaturityValidationIT (coteja PRISMA vs planilla de Agesic)",
     "Test de integración (@SpringBootTest + Testcontainers) que usa el catálogo 5.0 y el perfil Básico sembrados, marca 'cumple' los 165 controles requeridos y verifica que calculateMaturity reproduce la columna 'Nivel de Madurez' de la planilla en las 103 subcategorías (0 diferencias, general 1.165). Baseline: scripts/mcu50/build.py -> expected_basico.json.",
     "Como equipo, quiero una prueba automática que falle si el cálculo de madurez se desvía de la planilla oficial, para no volver a trabajar con números que no sabemos si están bien.",
     "P2", "Testing"),
    ("pkg3 — Ajustar PrismaApplicationIT al catálogo alineado",
     "TOTAL_CONTROLS 670 -> 671 (+ OR.5-10); los tests de cálculo eligen la subcategoría y sus controles vía control.effectiveSubcategories() (el mapeo curado que usa calculateMaturity), no requirement.getSubcategories().",
     "Como equipo, quiero que la suite de integración siga verde después de alinear el seed, para poder mergear con confianza.",
     "P3", "Testing"),
    ("pkg3 — Actualizar la documentación con el modelo nuevo",
     "docs/PLAN-CAMBIOS-EQUIPO.md (sin 'MCU 5.2', redivisión pkg0-3), docs/HistoriasDeUsuario.md y docs/diagramas/casos-de-uso.md: el promedio global ahora es sobre las 103 subcategorías (0 en las no cubiertas) y la madurez de subcategoría es sobre el marco completo, no sólo el perfil.",
     "Como integrante del equipo, quiero que la documentación refleje el modelo de cálculo vigente, para no implementar contra una descripción vieja.",
     "P3", "Documentación"),
    ("Mejora — Hacer MFA/TOTP obligatorio para PRISMA_ADMIN",
     "MFA/TOTP está configurado en el realm de Keycloak pero no es obligatorio para ningún rol. Hacerlo requerido al menos para PRISMA_ADMIN (Required Action / flujo de autenticación del realm).",
     "Como plataforma, quiero exigir un segundo factor a las cuentas con privilegios globales, para reducir el impacto de una credencial de admin comprometida.",
     "P3", "Seguridad"),
]

BUGS = [
    ("npm run lint falla: variable _unused sin usar en validators.spec.ts",
     "apps/frontend/tests/utils/validators.spec.ts:87 usa `const { organizationId: _unused, ...withoutOrg } = valid` y @typescript-eslint/no-unused-vars lo marca como error, así que `npm run lint` termina con exit != 0 en limpio. Preexistente en main (commit 0b64208). Fix: renombrar a un patrón ignorado, usar rest sin nombrar la key, o ajustar varsIgnorePattern en la config de eslint.",
     "Como equipo, quiero que `npm run lint` pase en limpio, para que el gate de CI y el pre-commit sean confiables.",
     "P2", "Frontend"),
    ("Accesibilidad WCAG 2.1 AA incompleta",
     "Los tests de accesibilidad (Playwright + axe) no pasan del todo: falta aria-label en el botón de colapsar el sidebar y hay contraste insuficiente en algunos textos (ver docs/Testing.md).",
     "Como usuario con lector de pantalla o baja visión, quiero que la interfaz cumpla WCAG 2.1 AA, para poder operar PRISMA sin barreras.",
     "P3", "UX"),
    ("@PreAuthorize + @Valid: devuelve 400 (validación) en vez de 403 (permiso)",
     "En un endpoint con @PreAuthorize + @Valid (p.ej. POST /api/catalog/import), Spring resuelve la validación del body antes de evaluar el rol: un request sin permiso con body inválido recibe 400 con el detalle de validación en vez del 403 esperado. No es un bypass (con body válido el mismo rol sin permiso sí recibe 403) pero filtra el shape del DTO. Ver docs/API.md.",
     "Como sistema, no quiero revelar la forma del body a quien no tiene permiso para llamar el endpoint, para no dar información útil a un atacante.",
     "P4", "Backend"),
    ("Bitácora: el actor queda en blanco al auto-eliminarse un usuario",
     "AuditLogService resuelve 'quién' hizo la acción después de la operación, dentro de la misma transacción; si la operación fue que un usuario se borró a sí mismo, para cuando se resuelve el actor la fila ya no existe y queda en blanco. Ver docs/RUNBOOK.md y HU-OBS-01.",
     "Como PRISMA_ADMIN investigando un incidente, quiero que toda entrada de la bitácora tenga el actor, incluso cuando la acción fue una auto-eliminación.",
     "P4", "Observabilidad"),
]


def clean(text: str) -> str:
    """markdown inline -> texto plano, una sola línea."""
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)   # [txt](url) -> txt
    text = text.replace("**", "").replace("`", "")
    text = text.replace("&lt;", "<").replace("&gt;", ">")
    text = re.sub(r"\s--\s", " — ", text)                  # '--' que el doc usa como raya
    text = re.sub(r"\s+", " ", text).strip()
    return text


def parse_hu_md():
    """Devuelve (epics, stories) como listas de dicts con las columnas de Bugasura."""
    lines = HU_MD.read_text(encoding="utf-8").splitlines()
    epics, stories = [], []
    area_num, area_tag, area_prio, area_name = None, None, None, None
    area_intro = []
    area_story_count = [0]
    cur = None  # historia en curso

    def flush_story():
        nonlocal cur
        if not cur:
            return
        area_story_count[0] += 1
        crit = [c for c in cur["crit"] if not c.lower().startswith("implementado en")]
        details = " · ".join(crit)
        if cur["impl"]:
            details = (details + " " if details else "") + "Implementado en: " + cur["impl"]
        stories.append({
            "Title": f"{cur['code']} — {cur['title']}",
            "Details": details,
            "Overview": cur["overview"],
            "Priority": HU_PRIORITY.get(cur["code"], area_prio),
            "Type": "Story", "Status": "Completed", "Assignees": "",
            "Tags": area_tag, "Estimation": "",
        })
        cur = None

    i = 0
    while i < len(lines):
        ln = lines[i]
        m_area = re.match(r"^## (\d+)\.\s+(.+)$", ln)
        m_hu = re.match(r"^### (HU-[A-Z]+-\d+[a-z]?)\s+—\s+(.+)$", ln)
        if m_area:
            flush_story()
            if area_num is not None:
                _finish_epic(epics, area_num, area_name, area_intro, area_story_count[0])
            area_num = int(m_area.group(1))
            area_name = clean(m_area.group(2))
            area_tag, area_prio, _purpose = AREAS[area_num]
            area_intro = []
            area_story_count[0] = 0
            i += 1
            continue
        if m_hu:
            flush_story()
            cur = {"code": m_hu.group(1), "title": clean(m_hu.group(2)),
                   "overview": "", "crit": [], "impl": ""}
            # blockquote (puede ser multilínea)
            j = i + 1
            while j < len(lines) and not lines[j].strip():
                j += 1
            quote = []
            while j < len(lines) and lines[j].lstrip().startswith(">"):
                quote.append(lines[j].lstrip()[1:].strip())
                j += 1
            cur["overview"] = clean(" ".join(quote))
            # bullets hasta el próximo ### / ## / ---
            k = j
            bullet = None
            while k < len(lines):
                s = lines[k]
                if re.match(r"^(#{2,3} |---\s*$)", s):
                    break
                if re.match(r"^\s*-\s+", s):
                    if bullet is not None:
                        _add_bullet(cur, bullet)
                    bullet = re.sub(r"^\s*-\s+", "", s)
                elif bullet is not None and s.strip():
                    bullet += " " + s.strip()
                k += 1
            if bullet is not None:
                _add_bullet(cur, bullet)
            i = k
            continue
        # texto suelto bajo un área (antes de la primera HU) -> intro del Epic
        if area_num is not None and cur is None and ln.strip() and not ln.startswith("#"):
            if not ln.strip().startswith(">") and "Ver " not in ln[:6]:
                area_intro.append(ln.strip())
        i += 1

    flush_story()
    if area_num is not None:
        _finish_epic(epics, area_num, area_name, area_intro, area_story_count[0])
    return epics, stories


def _add_bullet(cur, bullet):
    b = clean(bullet)
    if b.lower().startswith("implementado en:"):
        cur["impl"] = b[len("Implementado en:"):].strip().rstrip(".")
    else:
        cur["crit"].append(b)


def _finish_epic(epics, num, name, intro, n_stories):
    tag, prio, purpose = AREAS[num]
    intro_txt = clean(" ".join(intro))[:500]
    details = f"{purpose} Agrupa {n_stories} historias de usuario, todas implementadas."
    if intro_txt:
        details += " " + intro_txt
    epics.append({
        "Title": name,
        "Details": details,
        "Overview": purpose,
        "Priority": prio, "Type": "Epic", "Status": "Completed",
        "Assignees": "", "Tags": tag, "Estimation": "",
    })


def pending_rows():
    for title, details, overview, prio, tag in PENDING:
        yield {"Title": title, "Details": details, "Overview": overview, "Priority": prio,
               "Type": "Task", "Status": "New", "Assignees": "", "Tags": tag, "Estimation": ""}


def bug_rows():
    for title, details, overview, prio, tag in BUGS:
        yield {"Title": title, "Details": details, "Overview": overview, "Priority": prio,
               "Type": "Bug", "Status": "New", "Assignees": "", "Tags": tag, "Estimation": ""}


def main():
    epics, stories = parse_hu_md()
    rows = epics + stories + list(pending_rows()) + list(bug_rows())

    # ---- validación ----
    errs = []
    if len(epics) != 15:
        errs.append(f"esperaba 15 Epics, hay {len(epics)}")
    if len(stories) != 68:
        errs.append(f"esperaba 68 Stories, hay {len(stories)}")
    for r in rows:
        for col in ("Title", "Overview", "Tags", "Priority", "Type", "Status"):
            if not r[col]:
                errs.append(f"fila sin {col}: {r['Title']!r}")
        if r["Priority"] not in {"P1", "P2", "P3", "P4", "P5"}:
            errs.append(f"prioridad rara en {r['Title']!r}: {r['Priority']}")
        if r["Assignees"] or r["Estimation"]:
            errs.append(f"Assignees/Estimation no vacías en {r['Title']!r}")
        for col, v in r.items():
            if "\n" in v or "\r" in v:
                errs.append(f"celda multilínea en {r['Title']!r} ({col})")
    if errs:
        for e in errs:
            print("  !!", e)
        sys.exit("abortado: errores de validación")

    OUT.write_text("", encoding="utf-8")
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=HEADER, quoting=csv.QUOTE_MINIMAL)
        w.writeheader()
        w.writerows(rows)

    from collections import Counter
    print(f"Escrito: {OUT.relative_to(REPO)}  ({len(rows)} filas)")
    print("  por tipo:    ", dict(Counter(r["Type"] for r in rows)))
    print("  por estado:  ", dict(Counter(r["Status"] for r in rows)))
    print("  por prioridad:", dict(sorted(Counter(r["Priority"] for r in rows).items())))
    print("  por tag:     ", dict(sorted(Counter(r["Tags"] for r in rows).items())))


if __name__ == "__main__":
    main()
