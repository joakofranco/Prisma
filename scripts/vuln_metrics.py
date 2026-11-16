#!/usr/bin/env python3
"""Convierte los informes JSON de Trivy en métricas de Prometheus y un resumen en Markdown.

Lee <directorio>/manifiesto.tsv (tipo, objetivo, archivo, estado) y los JSON que lista, y escribe
<directorio>/metricas.prom y <directorio>/resumen.md.
"""
import csv
import json
import os
import sys
import time

SEVERIDADES = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"]
DETALLE_MAXIMO = int(os.environ.get("VULN_DETALLE_MAXIMO", "400"))


def etiqueta(valor):
    return str(valor).replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")


def leer_manifiesto(directorio):
    ruta = os.path.join(directorio, "manifiesto.tsv")
    with open(ruta, encoding="utf-8", newline="") as f:
        return [fila for fila in csv.reader(f, delimiter="\t") if len(fila) >= 4]


def vulnerabilidades(ruta):
    with open(ruta, encoding="utf-8") as f:
        datos = json.load(f)
    for resultado in datos.get("Results") or []:
        for v in resultado.get("Vulnerabilities") or []:
            yield resultado.get("Target", ""), v


def main(directorio):
    ahora = int(time.time())
    lineas = [
        "# HELP prisma_vuln_total Vulnerabilidades halladas por objetivo, severidad y si tienen parche.",
        "# TYPE prisma_vuln_total gauge",
    ]
    detalle = []
    resumen = []
    exitos = []
    for tipo, objetivo, archivo, estado in leer_manifiesto(directorio):
        base = f'tipo="{etiqueta(tipo)}",objetivo="{etiqueta(objetivo)}"'
        exitos.append(f"prisma_vuln_scan_success{{{base}}} {1 if estado == 'ok' else 0}")
        exitos.append(f"prisma_vuln_scan_timestamp_seconds{{{base}}} {ahora}")
        if estado != "ok":
            resumen.append((tipo, objetivo, None))
            continue
        cuentas = {(s, p): 0 for s in SEVERIDADES for p in ("si", "no")}
        for destino, v in vulnerabilidades(os.path.join(directorio, archivo)):
            sev = v.get("Severity", "UNKNOWN")
            sev = sev if sev in SEVERIDADES else "UNKNOWN"
            parche = "si" if v.get("FixedVersion") else "no"
            cuentas[(sev, parche)] += 1
            if sev in ("CRITICAL", "HIGH"):
                detalle.append((SEVERIDADES.index(sev), parche != "si", tipo, objetivo, destino, v))
        for (sev, parche), n in sorted(cuentas.items()):
            lineas.append(f'prisma_vuln_total{{{base},severidad="{sev}",parche="{parche}"}} {n}')
        resumen.append((tipo, objetivo, cuentas))

    detalle.sort(key=lambda d: (d[0], d[1], d[3], d[5].get("PkgName", "")))
    lineas += [
        "# HELP prisma_vuln_detalle Cada vulnerabilidad crítica o alta (valor 1); se limita a las más graves.",
        "# TYPE prisma_vuln_detalle gauge",
    ]
    for _, _, tipo, objetivo, destino, v in detalle[:DETALLE_MAXIMO]:
        etiquetas = {
            "tipo": tipo,
            "objetivo": objetivo,
            "cve": v.get("VulnerabilityID", ""),
            "paquete": v.get("PkgName", ""),
            "instalada": v.get("InstalledVersion", ""),
            "corregida": v.get("FixedVersion", "") or "sin parche",
            "severidad": v.get("Severity", ""),
        }
        pares = ",".join(f'{k}="{etiqueta(val)}"' for k, val in etiquetas.items())
        lineas.append(f"prisma_vuln_detalle{{{pares}}} 1")
    lineas += [
        "# HELP prisma_vuln_scan_success 1 si el escaneo del objetivo terminó bien.",
        "# TYPE prisma_vuln_scan_success gauge",
        "# HELP prisma_vuln_scan_timestamp_seconds Momento del último escaneo del objetivo.",
        "# TYPE prisma_vuln_scan_timestamp_seconds gauge",
    ] + exitos
    with open(os.path.join(directorio, "metricas.prom"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lineas) + "\n")

    md = [
        "# Escaneo de vulnerabilidades de PRISMA",
        "",
        time.strftime("Generado: %Y-%m-%d %H:%M UTC", time.gmtime(ahora)),
        "",
        "| Tipo | Objetivo | Críticas | Altas | Medias | Bajas | Con parche (crít.+altas) |",
        "|---|---|---|---|---|---|---|",
    ]
    for tipo, objetivo, c in resumen:
        if c is None:
            md.append(f"| {tipo} | {objetivo} | no se pudo escanear | | | | |")
            continue
        total = lambda s: c[(s, "si")] + c[(s, "no")]
        con_parche = c[("CRITICAL", "si")] + c[("HIGH", "si")]
        md.append(f"| {tipo} | {objetivo} | {total('CRITICAL')} | {total('HIGH')} | {total('MEDIUM')} | {total('LOW')} | {con_parche} |")
    md.append("")
    with open(os.path.join(directorio, "resumen.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md))

    criticas_con_parche = sum(c[("CRITICAL", "si")] for _, _, c in resumen if c)
    print("\n".join(md))
    print(f"CRITICAS_CON_PARCHE={criticas_con_parche}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
