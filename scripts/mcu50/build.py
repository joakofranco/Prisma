#!/usr/bin/env python3
"""
Generador único del seed alineado de MCU 5.0 a las planillas oficiales de Agesic (formato 2025).

Lee  : docs/mcu-5.0/Planilla MCU 5.0 {Básico,Estándar,Avanzado}.xlsx
Emite:
  1. apps/backend-core/src/main/resources/db/migration/V19__mcu50_align_agesic_2025.sql
       - control nuevo OR.5-10 (req OR.5, nivel 4)
       - prisma.catalog_control_subcategories: los 1013 pares curados control<->subcategoría
       - membresía de los 3 perfiles comunitarios sembrados = controles requeridos (165/234/309)
  2. scripts/mcu50/expected_basico.json  (baseline de MaturityValidationIT)

Formato de las planillas:
  - pestaña "Cumplimiento"        : fila 8 = encabezado; col B = "COD: texto", col D = "Si" si el
                                     control es requerido para ese perfil
  - pestaña "Madurez Subcategoría": fila 8 = encabezado; col D = ID subcategoría; cols H/I/J/K =
                                     "COD: texto" de los controles ubicados en Nivel 1/2/3/4

El placement (H..K) es idéntico en las 3 planillas -> se lee de una sola.

Uso:  python scripts/mcu50/build.py        (o `make mcu50-build`)

La migración es INMUTABLE una vez mergeada. Regenerar sólo si Agesic revisa las planillas
antes del merge (y entonces bump de versión Flyway).
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import defaultdict
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "docs" / "mcu-5.0"
MIGRATION = (
    REPO
    / "apps/backend-core/src/main/resources/db/migration/V19__mcu50_align_agesic_2025.sql"
)
EXPECTED = Path(__file__).parent / "expected_basico.json"

M = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
NS = {"m": M[1:-1]}
SUBCAT_RE = re.compile(r"^[A-Z]{2}\.[A-Z]{2}-\d+$")
CODE_RE = re.compile(r"^\s*([A-Z]{2}\.\d+-\d+)\s*:\s*(.*)$", re.S)
CTRL_COLS = {"H": 1, "I": 2, "J": 3, "K": 4}
FUNC_ALIAS = {"RS": "RE"}  # la planilla llama "RE" a RESPONDER

PROFILES = [
    ("basico", "Planilla MCU 5.0 Básico.xlsx", "e1000001-0000-0000-0000-000000000001", "Básico"),
    ("estandar", "Planilla MCU 5.0 Estándar.xlsx", "e1000001-0000-0000-0000-000000000002", "Estándar"),
    ("avanzado", "Planilla MCU 5.0 Avanzado.xlsx", "e1000001-0000-0000-0000-000000000003", "Avanzado"),
]
PROFILE_DESC = {
    "basico": "Controles requeridos del perfil comunitario Básico de Agesic (MCU 5.0, planilla "
    "2025): la línea base mínima recomendada.",
    "estandar": "Controles requeridos del perfil comunitario Estándar de Agesic (MCU 5.0, "
    "planilla 2025).",
    "avanzado": "Controles requeridos del perfil comunitario Avanzado de Agesic (MCU 5.0, "
    "planilla 2025).",
}


def load_book(path):
    z = zipfile.ZipFile(path)
    shared = [
        "".join(t.text or "" for t in si.iter(M + "t"))
        for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", NS)
    ]
    wb = ET.fromstring(z.read("xl/workbook.xml"))
    rels = {
        r.get("Id"): r.get("Target")
        for r in ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
    }
    sheet_ids = list(wb.find("m:sheets", NS))

    def grid(idx):
        rid = sheet_ids[idx].get(
            "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
        )
        sh = ET.fromstring(z.read("xl/" + rels[rid].lstrip("/")))
        out = {}
        for row in sh.find("m:sheetData", NS).findall("m:row", NS):
            d = {}
            for c in row.findall("m:c", NS):
                col = re.match(r"[A-Z]+", c.get("r")).group(0)
                v = c.find("m:v", NS)
                if v is not None:
                    d[col] = shared[int(v.text)] if c.get("t") == "s" else v.text
            out[int(row.get("r"))] = d
        return out

    return grid


def required_set(grid):
    """Controles con Cumplimiento!D == 'Si'."""
    req = {}
    for rn, d in grid(1).items():
        if rn < 9:
            continue
        m = CODE_RE.match(d.get("B", "") or "")
        if m and (d.get("D") or "").strip().lower() == "si":
            req[m.group(1)] = m.group(2).strip()
    return req


def placements(grid):
    """subcat_code -> {nivel: {control_code: texto}} desde 'Madurez Subcategoría'."""
    out = {}
    cur = None
    for rn, d in grid(2).items():
        if rn < 9:
            continue
        dsub = (d.get("D") or "").strip()
        if SUBCAT_RE.match(dsub):
            cur = dsub
            out.setdefault(cur, {1: {}, 2: {}, 3: {}, 4: {}})
        if cur is None:
            continue
        for col, lvl in CTRL_COLS.items():
            m = CODE_RE.match(d.get(col, "") or "")
            if m:
                out[cur][lvl][m.group(1)] = m.group(2).strip()
    return {k: v for k, v in out.items() if any(v.values())}


def sql_str(s):
    return "'" + s.replace("'", "''") + "'"


def natural_key(code):
    dom = re.match(r"[A-Z]+", code).group(0)
    return (dom, [int(x) for x in re.findall(r"\d+", code)])


def emit_migration(pairs, or510_desc, profiles):
    """pairs: set[(control_code, subcat_code)]  ;  profiles: list[(slug, pid, name, [codes])]"""
    L = []
    L.append("-- =====================================================================")
    L.append("-- V19__mcu50_align_agesic_2025.sql  (GENERADA por scripts/mcu50/build.py)")
    L.append("--")
    L.append("-- Alinea el seed de la version '5.0' a las planillas oficiales de Agesic 2025:")
    L.append("--   1) control nuevo OR.5-10 (req OR.5, nivel 4), ausente en V9;")
    L.append("--   2) mapeo curado control<->subcategoria (1013 pares) en catalog_control_subcategories")
    L.append("--      (tabla creada por V18) -- la madurez de cada subcategoria se calcula solo sobre")
    L.append("--      los controles ubicados bajo ella (ver CatalogControl.effectiveSubcategories);")
    L.append("--   3) membresia de los 3 perfiles comunitarios = los controles marcados 'Si' en la")
    L.append("--      columna 'Linea Base' de cada planilla (165 / 234 / 309). Reemplaza la membresia")
    L.append("--      por prioridad de subcategoria que sembro V10.")
    L.append("--")
    L.append("-- NO toca ninguna otra version de catalogo. Todo por JOIN sobre 'code'.")
    L.append("-- =====================================================================")
    L.append("")
    L.append("-- 1) OR.5-10 -----------------------------------------------------------")
    L.append("INSERT INTO prisma.catalog_controls (id, requirement_id, code, description, target_level, sort_order)")
    L.append("SELECT gen_random_uuid(), r.id, 'OR.5-10', " + sql_str(or510_desc) + ", 4,")
    L.append("       COALESCE((SELECT MAX(c.sort_order) FROM prisma.catalog_controls c WHERE c.requirement_id = r.id), -1) + 1")
    L.append("FROM prisma.catalog_requirements r")
    L.append("JOIN prisma.catalog_versions v ON v.id = r.version_id AND v.version = '5.0'")
    L.append("WHERE r.code = 'OR.5'")
    L.append("  AND NOT EXISTS (SELECT 1 FROM prisma.catalog_controls c2")
    L.append("                  JOIN prisma.catalog_requirements r2 ON r2.id = c2.requirement_id")
    L.append("                  JOIN prisma.catalog_versions v2 ON v2.id = r2.version_id AND v2.version = '5.0'")
    L.append("                  WHERE c2.code = 'OR.5-10');")
    L.append("")
    L.append("-- 2) mapeo curado control <-> subcategoria (1013 pares) ---------------")
    L.append("INSERT INTO prisma.catalog_control_subcategories (control_id, subcategory_id)")
    L.append("SELECT ct.id, s.id")
    L.append("FROM (VALUES")
    rows = sorted(pairs, key=lambda p: (natural_key(p[1]), natural_key(p[0])))
    for i, (cc, sc) in enumerate(rows):
        L.append(f"  ({sql_str(cc)}, {sql_str(sc)}){',' if i < len(rows) - 1 else ''}")
    L.append(") AS p(control_code, subcat_code)")
    L.append("JOIN prisma.catalog_controls ct       ON ct.code = p.control_code")
    L.append("JOIN prisma.catalog_requirements r     ON r.id = ct.requirement_id")
    L.append("JOIN prisma.catalog_versions v         ON v.id = r.version_id AND v.version = '5.0'")
    L.append("JOIN prisma.catalog_subcategories s    ON s.code = p.subcat_code")
    L.append("JOIN prisma.catalog_categories cat     ON cat.id = s.category_id")
    L.append("JOIN prisma.catalog_functions f        ON f.id = cat.function_id AND f.version_id = v.id")
    L.append("ON CONFLICT DO NOTHING;")
    L.append("")
    L.append("-- 3) perfiles comunitarios = controles requeridos de cada planilla ---")
    pids = ", ".join(sql_str(p[1]) for p in profiles)
    L.append(f"DELETE FROM prisma.community_profile_controls WHERE profile_id IN ({pids});")
    L.append("")
    for slug, pid, name, codes in profiles:
        L.append(f"UPDATE prisma.community_profiles SET description = {sql_str(PROFILE_DESC[slug])} WHERE id = {sql_str(pid)};")
    L.append("")
    for slug, pid, name, codes in profiles:
        L.append(f"-- {name}: {len(codes)} controles")
        L.append("INSERT INTO prisma.community_profile_controls (profile_id, control_id)")
        L.append(f"SELECT {sql_str(pid)}, ct.id")
        L.append("FROM (VALUES")
        cs = sorted(codes, key=natural_key)
        for i, c in enumerate(cs):
            L.append(f"  ({sql_str(c)}){',' if i < len(cs) - 1 else ''}")
        L.append(") AS p(code)")
        L.append("JOIN prisma.catalog_controls ct    ON ct.code = p.code")
        L.append("JOIN prisma.catalog_requirements r ON r.id = ct.requirement_id")
        L.append("JOIN prisma.catalog_versions v     ON v.id = r.version_id AND v.version = '5.0';")
        L.append("")
    L.append("-- ---- verificacion ----")
    L.append("DO $$")
    L.append("DECLARE nctrl int; npairs int; nb int; ne int; na int;")
    L.append("BEGIN")
    L.append("  SELECT count(*) INTO nctrl FROM prisma.catalog_controls ct JOIN prisma.catalog_requirements r ON r.id=ct.requirement_id JOIN prisma.catalog_versions v ON v.id=r.version_id WHERE v.version='5.0';")
    L.append("  SELECT count(*) INTO npairs FROM prisma.catalog_control_subcategories ccs JOIN prisma.catalog_controls ct ON ct.id=ccs.control_id JOIN prisma.catalog_requirements r ON r.id=ct.requirement_id JOIN prisma.catalog_versions v ON v.id=r.version_id WHERE v.version='5.0';")
    L.append("  SELECT count(*) INTO nb FROM prisma.community_profile_controls WHERE profile_id='e1000001-0000-0000-0000-000000000001';")
    L.append("  SELECT count(*) INTO ne FROM prisma.community_profile_controls WHERE profile_id='e1000001-0000-0000-0000-000000000002';")
    L.append("  SELECT count(*) INTO na FROM prisma.community_profile_controls WHERE profile_id='e1000001-0000-0000-0000-000000000003';")
    L.append("  RAISE NOTICE 'MCU 5.0 alineado: % controles, % pares control<->subcat; perfiles B/E/A = % / % / %', nctrl, npairs, nb, ne, na;")
    L.append("  IF nctrl <> 671 THEN RAISE EXCEPTION 'Se esperaban 671 controles en 5.0, hay %', nctrl; END IF;")
    L.append("  IF npairs <> 1013 THEN RAISE EXCEPTION 'Se esperaban 1013 pares, hay %', npairs; END IF;")
    L.append("  IF nb <> 165 OR ne <> 234 OR na <> 309 THEN RAISE EXCEPTION 'Perfiles: % / % / % (esperado 165 / 234 / 309)', nb, ne, na; END IF;")
    L.append("END $$;")
    L.append("")
    MIGRATION.write_text("\n".join(L), encoding="utf-8")


def emit_expected(basico_required, plc):
    def sim(sub):
        n = 0
        for lvl in (1, 2, 3, 4):
            controls = set(plc[sub][lvl])
            if controls and controls <= basico_required:
                n = lvl
            else:
                break
        return n

    subcategory = {s: sim(s) for s in sorted(plc)}
    by_func = defaultdict(list)
    for code, lvl in subcategory.items():
        by_func[FUNC_ALIAS.get(code[:2], code[:2])].append(lvl)
    function = {f: round(sum(v) / len(v), 2) for f, v in sorted(by_func.items())}
    general = round(sum(subcategory.values()) / len(subcategory), 3)
    payload = {
        "_comment": "GENERADO por scripts/mcu50/build.py desde docs/mcu-5.0/Planilla MCU 5.0 "
        "Básico.xlsx. Escenario: los 165 controles requeridos cumplen. subcategory = fórmula "
        "'L' de la planilla; function/general = medias sobre 103.",
        "answers": {c: True for c in sorted(basico_required)},
        "subcategory": subcategory,
        "function": function,
        "general": general,
    }
    EXPECTED.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return len(subcategory), general


def main():
    reqs = {}
    or510_desc = None
    basico_plc = None
    for slug, fname, _pid, _name in PROFILES:
        path = SRC / fname
        if not path.exists():
            sys.exit(f"falta {path} -- copiá las planillas a docs/mcu-5.0/")
        grid = load_book(path)
        reqs[slug] = required_set(grid)
        or510_desc = or510_desc or _or510_from(grid)
        if slug == "basico":
            basico_plc = placements(grid)  # el placement H..K es idéntico en las 3 planillas

    # placement (control, subcat) -- ignoramos el nivel (vive en catalog_controls.target_level)
    pairs = {
        (cc, sub)
        for sub, levels in basico_plc.items()
        for lvl in levels.values()
        for cc in lvl
    }

    profiles = [
        (slug, pid, name, sorted(reqs[slug]))
        for slug, _f, pid, name in PROFILES
    ]

    emit_migration(pairs, or510_desc, profiles)
    nsub, general = emit_expected(set(reqs["basico"]), basico_plc)

    print(f"OR.5-10: {or510_desc!r}")
    print(f"pares control<->subcategoria: {len(pairs)}")
    for slug in ("basico", "estandar", "avanzado"):
        print(f"  perfil {slug}: {len(reqs[slug])} requeridos")
    print(f"-> {MIGRATION.relative_to(REPO)}")
    print(f"-> {EXPECTED.relative_to(REPO)}  ({nsub} subcategorías, general {general})")


def _or510_from(grid):
    for rn, d in grid(1).items():
        if rn < 9:
            continue
        m = CODE_RE.match(d.get("B", "") or "")
        if m and m.group(1) == "OR.5-10":
            return m.group(2).strip()
    return None


if __name__ == "__main__":
    main()
