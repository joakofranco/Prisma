#!/usr/bin/env python3
import argparse
import io
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from html import escape

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import Image, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from summary import estadisticas, evaluar, leer_jtl, leer_props, umbral

ANCHO = landscape(A4)[0] - 3 * cm
ESTILOS = getSampleStyleSheet()
H1 = ParagraphStyle("h1", parent=ESTILOS["Heading1"], fontSize=18, spaceAfter=6)
H2 = ParagraphStyle("h2", parent=ESTILOS["Heading2"], fontSize=13, spaceBefore=8, spaceAfter=4)
TXT = ParagraphStyle("txt", parent=ESTILOS["BodyText"], fontSize=9, leading=12)
CELDA = ParagraphStyle("celda", parent=TXT, fontSize=7.5, leading=9)
VERDE, ROJO, GRIS = "#2e7d32", "#c62828", "#555555"
PALETA = ["#1f77b4", "#ff7f0e", "#2ca02c", "#9467bd", "#8c564b", "#17becf", "#e377c2", "#7f7f7f"]


def p(texto, estilo=TXT):
    return Paragraph(texto, estilo)


def png(fig, ancho_cm=21):
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=130, bbox_inches="tight")
    plt.close(fig)
    buf.seek(0)
    img = Image(buf)
    factor = (ancho_cm * cm) / img.imageWidth
    img.drawWidth = img.imageWidth * factor
    img.drawHeight = img.imageHeight * factor
    return img


def tabla(filas, anchos=None, cabecera=True):
    t = Table(filas, colWidths=anchos, repeatRows=1 if cabecera else 0)
    estilo = [
        ("FONTSIZE", (0, 0), (-1, -1), 7.5),
        ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#bbbbbb")),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f4f6f8")]),
    ]
    if cabecera:
        estilo += [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#263238")),
                   ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                   ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold")]
    t.setStyle(TableStyle(estilo))
    return t


def leer_resumen(directorio):
    ruta = os.path.join(directorio, "resumen.md")
    veredicto, vinietas = "", []
    if os.path.isfile(ruta):
        with open(ruta, encoding="utf-8") as f:
            for linea in f:
                if linea.startswith("## "):
                    break
                if linea.startswith("**Resultado:"):
                    veredicto = linea.strip().strip("*").replace("Resultado: ", "")
                elif linea.startswith("- "):
                    vinietas.append(linea[2:].strip())
    return veredicto, vinietas


def leer_servidor(directorio):
    ruta = os.path.join(directorio, "servidor.md")
    if not os.path.isfile(ruta):
        return None, []
    filas, aviso = [], None
    with open(ruta, encoding="utf-8") as f:
        for linea in f:
            linea = linea.rstrip("\n")
            if linea.startswith("|") and not linea.startswith("|---"):
                filas.append([c.strip() for c in linea.strip("|").split("|")])
            elif linea and not linea.startswith("#"):
                aviso = linea
    return aviso, filas


def cubetas(filas, paso):
    t0 = min(f["ts"] for f in filas)
    return t0, lambda ts: int((ts - t0) / 1000 / paso)


def grafico_tiempo(filas, paso):
    t0, cubeta = cubetas(filas, paso)
    por_label = defaultdict(lambda: defaultdict(list))
    for f in filas:
        por_label[f["label"]][cubeta(f["ts"])].append(f["ms"])
    fig, ax = plt.subplots(figsize=(11, 2.5))
    for i, label in enumerate(sorted(por_label)[:8]):
        xs = sorted(por_label[label])
        ax.plot([x * paso for x in xs], [sum(por_label[label][x]) / len(por_label[label][x]) for x in xs],
                label=label, color=PALETA[i % len(PALETA)], linewidth=1.3)
    ax.set_title("Tiempo de respuesta medio por petición a lo largo de la prueba")
    ax.set_xlabel("segundos desde el inicio")
    ax.set_ylabel("ms")
    ax.grid(alpha=0.3)
    ax.legend(fontsize=7, ncol=4, loc="upper center", bbox_to_anchor=(0.5, -0.3))
    return png(fig, 19)


def grafico_carga(filas, paso):
    t0, cubeta = cubetas(filas, paso)
    total, errores = Counter(), Counter()
    for f in filas:
        c = cubeta(f["ts"])
        total[c] += 1
        if not f["ok"]:
            errores[c] += 1
    xs = sorted(total)
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(11, 2.0), gridspec_kw={"width_ratios": [3, 2]})
    a1.plot([x * paso for x in xs], [total[x] / paso for x in xs], color="#1f77b4")
    a1.set_title("Throughput (peticiones por segundo)")
    a1.set_xlabel("segundos desde el inicio")
    a1.grid(alpha=0.3)
    a2.bar([x * paso for x in xs], [errores[x] * 100 / total[x] for x in xs], width=paso * 0.9, color="#c62828")
    a2.set_title("Errores por intervalo (%)")
    a2.set_xlabel("segundos desde el inicio")
    a2.set_ylim(0, 100)
    a2.grid(alpha=0.3)
    return png(fig, 19)


def grafico_p95(por_label, props):
    labels = [l for l in sorted(por_label) if not l.startswith("_")]
    est = {l: estadisticas(por_label[l]) for l in labels}
    fig, ax = plt.subplots(figsize=(11, max(1.8, 0.36 * len(labels) + 0.9)))
    valores = [est[l]["p95"] for l in labels]
    limites = [umbral(props, "p95_ms", l) for l in labels]
    cols = [ROJO if (lim is not None and v > lim) else VERDE for v, lim in zip(valores, limites)]
    ax.barh(labels, valores, color=cols)
    for i, lim in enumerate(limites):
        if lim is not None:
            ax.plot([lim, lim], [i - 0.4, i + 0.4], color="black", linewidth=1.5)
    ax.set_title("p95 por petición (la línea negra marca el umbral)")
    ax.set_xlabel("ms")
    ax.invert_yaxis()
    ax.grid(axis="x", alpha=0.3)
    return png(fig)


def grafico_percentiles(por_label):
    labels = [l for l in sorted(por_label) if not l.startswith("_")]
    est = {l: estadisticas(por_label[l]) for l in labels}
    fig, ax = plt.subplots(figsize=(11, 2.8))
    ancho, base = 0.2, range(len(labels))
    for k, (clave, nombre) in enumerate((("media", "media"), ("p90", "p90"), ("p95", "p95"), ("p99", "p99"))):
        ax.bar([b + (k - 1.5) * ancho for b in base], [est[l][clave] for l in labels], ancho, label=nombre, color=PALETA[k])
    ax.set_xticks(list(base))
    ax.set_xticklabels(labels, rotation=20, ha="right", fontsize=7)
    ax.set_yscale("log")
    ax.set_ylabel("ms (escala logarítmica)")
    ax.set_title("Distribución de tiempos por petición")
    ax.legend(fontsize=7, ncol=4)
    ax.grid(axis="y", alpha=0.3)
    return png(fig)


def grafico_codigos(filas):
    codigos = Counter(f["code"] for f in filas if not f["label"].startswith("_"))
    claves = sorted(codigos)
    fig, ax = plt.subplots(figsize=(11, 2.0))
    ax.bar(claves, [codigos[c] for c in claves], color=[VERDE if c.startswith("2") else ROJO for c in claves])
    for i, c in enumerate(claves):
        ax.text(i, codigos[c], f"{codigos[c]:,}".replace(",", "."), ha="center", va="bottom", fontsize=8)
    ax.set_title("Códigos de respuesta (peticiones medidas)")
    ax.set_yscale("log")
    ax.set_ylabel("cantidad (escala logarítmica)")
    return png(fig)


def historia_caso(directorio, titulo_nivel=H1):
    props = leer_props(os.path.join(directorio, "efectivo.properties"))
    veredicto, vinietas = leer_resumen(directorio)
    filas = leer_jtl(os.path.join(directorio, "resultados.jtl"))
    medibles = [f for f in filas if not f["label"].startswith("_")]
    por_label = defaultdict(list)
    for f in filas:
        por_label[f["label"]].append(f)
    total = estadisticas(medibles)
    color = VERDE if veredicto.startswith("CUMPLE") else ROJO
    h = [p(escape(f"{props.get('caso', '')} - {props.get('titulo', '')}"), titulo_nivel),
         p(f'<font color="{color}"><b>Resultado: {escape(veredicto)}</b></font>', ParagraphStyle("v", parent=TXT, fontSize=12, leading=15))]
    for v in vinietas:
        h.append(p("- " + escape(v).replace("`", "")))
    h.append(Spacer(1, 6))
    h.append(tabla([["Muestras", "Media (ms)", "p90 (ms)", "p95 (ms)", "p99 (ms)", "Máx (ms)", "Error %", "Peticiones/s"],
                    [total["muestras"], total["media"], total["p90"], total["p95"], total["p99"], total["max"],
                     total["error_pct"], total["throughput"]]]))
    duracion = max((total["fin"] - total["inicio"]) / 1000, 1)
    paso = max(1, int(duracion / 60))
    h += [Spacer(1, 6), grafico_tiempo(medibles, paso), grafico_carga(medibles, paso), PageBreak(),
          p("Detalle por petición", H2), grafico_p95(por_label, props), grafico_percentiles(por_label),
          grafico_codigos(filas), PageBreak(), p("Tabla de tiempos por petición", H2)]
    filas_t = [["Petición", "Muestras", "Media", "p90", "p95", "p99", "Máx", "Error %", "Pet/s", "Resultado"]]
    for label in sorted(por_label):
        e = estadisticas(por_label[label])
        if label.startswith("_"):
            resultado = "auxiliar"
        else:
            fallas = evaluar(e, props, label)
            resultado = "cumple" if not fallas else "NO cumple: " + "; ".join(fallas)
        filas_t.append([p(escape(label), CELDA), e["muestras"], e["media"], e["p90"], e["p95"], e["p99"], e["max"],
                        e["error_pct"], e["throughput"], p(escape(resultado), CELDA)])
    h.append(tabla(filas_t, [5 * cm, 2 * cm, 1.8 * cm, 1.6 * cm, 1.6 * cm, 1.6 * cm, 1.6 * cm, 1.8 * cm, 1.8 * cm, 6 * cm]))
    aviso, srv = leer_servidor(directorio)
    h.append(p("Métricas del servidor durante la corrida", H2))
    h.append(tabla(srv) if srv else p(escape(aviso or "Sin datos del servidor.")))
    h.append(p("Parámetros de la corrida", H2))
    claves = ["caso", "plan", "modo", "objetivo", "usuarios", "rampa_s", "duracion_s", "iteraciones", "pausa_ms", "endpoints",
              "umbrales_de_la_corrida", "commit", "fecha"] + sorted(k for k in props if k.startswith("umbral.") and props[k] != "")
    h.append(tabla([["Parámetro", "Valor"]] + [[k, p(escape(props[k]), CELDA)] for k in claves if props.get(k) not in (None, "")],
                   [5 * cm, 20 * cm]))
    return h


def grafico_estres(pasos):
    us = [int(x["usuarios"]) for x in pasos]
    fig, (a1, a2, a3) = plt.subplots(1, 3, figsize=(12, 2.8))
    a1.plot(us, [x["p95"] for x in pasos], marker="o", label="p95", color="#1f77b4")
    a1.plot(us, [x["p99"] for x in pasos], marker="o", label="p99", color="#ff7f0e")
    a1.set_title("Latencia vs usuarios")
    a1.set_ylabel("ms")
    a1.legend(fontsize=7)
    a2.plot(us, [x["error_pct"] for x in pasos], marker="o", color="#c62828")
    a2.set_title("Error % vs usuarios")
    a3.plot(us, [x["throughput"] for x in pasos], marker="o", color="#2ca02c")
    a3.set_title("Peticiones/s vs usuarios")
    for a in (a1, a2, a3):
        a.set_xlabel("usuarios concurrentes")
        a.grid(alpha=0.3)
    return png(fig)


def historia_estres(directorio):
    props = leer_props(os.path.join(directorio, "efectivo.properties"))
    veredicto, vinietas = leer_resumen(directorio)
    pasos = []
    for n in sorted(os.listdir(directorio)):
        r = os.path.join(directorio, n, "resultado.json")
        if n.startswith("paso-") and os.path.isfile(r):
            with open(r, encoding="utf-8") as f:
                pasos.append(json.load(f))
    pasos.sort(key=lambda x: int(x["usuarios"]))
    quiebre = next((x for x in pasos if not x["cumple"]), None)
    h = [p(escape(f"{props.get('caso', '')} - {props.get('titulo', '')}"), H1)]
    if quiebre:
        h.append(p(f'<font color="{ROJO}"><b>Punto de quiebre: {quiebre["usuarios"]} usuarios concurrentes</b></font> '
                   f'(p95 {quiebre["p95"]} ms, error {quiebre["error_pct"]} %).', TXT))
    else:
        h.append(p(f'<font color="{VERDE}"><b>No se alcanzó el punto de quiebre hasta {pasos[-1]["usuarios"]} usuarios.</b></font>', TXT))
    resultado_estres = {}
    ruta_resultado = os.path.join(directorio, "resultado.json")
    if os.path.isfile(ruta_resultado):
        with open(ruta_resultado, encoding="utf-8") as f:
            resultado_estres = json.load(f)
    rec = resultado_estres.get("recuperacion")
    if rec:
        color = VERDE if rec["recuperado"] else (ROJO if rec["recuperado"] is False else GRIS)
        h.append(p(f'<font color="{color}"><b>Recuperación: {escape(rec["etiqueta"])}.</b></font> '
                   + escape(rec["detalle"].replace("**", "").replace("`", "")), TXT))
    h += [Spacer(1, 6), grafico_estres(pasos), Spacer(1, 6),
          tabla([["Usuarios", "Muestras", "Media (ms)", "p95", "p99", "Error %", "Peticiones/s", "Dentro de umbrales"]] +
                [[x["usuarios"], x["muestras"], x["media"], x["p95"], x["p99"], x["error_pct"], x["throughput"],
                  "sí" if x["cumple"] else "no"] for x in pasos]),
          p("Parámetros de la corrida", H2)]
    claves = ["caso", "plan", "modo", "objetivo", "escalones", "rampa_s", "duracion_s", "pausa_ms", "umbrales_de_la_corrida", "commit", "fecha"] + \
        sorted(k for k in props if k.startswith("umbral.") and props[k] != "")
    h.append(tabla([["Parámetro", "Valor"]] + [[k, p(escape(props[k]), CELDA)] for k in claves if props.get(k) not in (None, "")],
                   [5 * cm, 20 * cm]))
    for x in pasos:
        sub = os.path.join(directorio, f"paso-{int(x['usuarios']):04d}")
        if os.path.isfile(os.path.join(sub, "resultados.jtl")):
            h += [PageBreak(), p(f"Escalón de {x['usuarios']} usuarios", H2)] + historia_caso(sub, H2)
    sub = os.path.join(directorio, "recuperacion")
    if os.path.isfile(os.path.join(sub, "resultados.jtl")):
        h += [PageBreak(), p("Recuperación tras el estrés", H2)] + historia_caso(sub, H2)
    return h


def construir(salida, historia, titulo):
    def pie(canvas, doc):
        canvas.saveState()
        canvas.setFont("Helvetica", 7)
        canvas.setFillColor(colors.HexColor(GRIS))
        canvas.drawString(1.5 * cm, 0.8 * cm, f"PRISMA - {titulo}")
        canvas.drawRightString(landscape(A4)[0] - 1.5 * cm, 0.8 * cm, f"Página {doc.page}")
        canvas.restoreState()

    doc = SimpleDocTemplate(salida, pagesize=landscape(A4), leftMargin=1.5 * cm, rightMargin=1.5 * cm,
                            topMargin=1.3 * cm, bottomMargin=1.5 * cm, title=titulo, author="PRISMA")
    doc.build(historia, onFirstPage=pie, onLaterPages=pie)


def informe_corrida(directorio):
    props = leer_props(os.path.join(directorio, "efectivo.properties"))
    es_estres = bool(props.get("escalones"))
    historia = historia_estres(directorio) if es_estres else historia_caso(directorio)
    salida = os.path.join(directorio, "informe.pdf")
    construir(salida, historia, f"Informe de performance {props.get('caso', '')}")
    return salida


def informe_consolidado(directorios, salida):
    casos = []
    for d in directorios:
        r = os.path.join(d, "resultado.json")
        if os.path.isfile(r):
            with open(r, encoding="utf-8") as f:
                casos.append((d, json.load(f)))
    ahora = datetime.now(timezone.utc)
    historia = [p("Informe de performance - PRISMA", H1),
                p(f"Generado: {ahora:%Y-%m-%d %H:%M} UTC. Casos incluidos: {len(casos)}.", TXT), Spacer(1, 8)]
    historia.append(tabla([["Caso", "Descripción", "Usuarios", "Muestras", "Media (ms)", "p95", "p99", "Error %", "Pet/s", "Resultado"]] +
                          [[r["caso"], p(escape(r["titulo"]), CELDA), r["usuarios"], r["muestras"], r["media"], r["p95"], r["p99"],
                            r["error_pct"], r["throughput"], p(escape(r.get("nota") or ("cumple" if r["cumple"] else "NO cumple")), CELDA)]
                           for _, r in casos],
                          [2.4 * cm, 8 * cm, 1.8 * cm, 2 * cm, 2 * cm, 1.6 * cm, 1.6 * cm, 1.8 * cm, 1.8 * cm, 2.6 * cm]))
    for d, r in casos:
        historia.append(PageBreak())
        propiedades = leer_props(os.path.join(d, "efectivo.properties"))
        historia += historia_estres(d) if propiedades.get("escalones") else historia_caso(d)
    construir(salida, historia, "Informe de performance")
    return salida


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir")
    ap.add_argument("--consolidar", nargs="+")
    ap.add_argument("--salida")
    a = ap.parse_args()
    ruta = informe_consolidado(a.consolidar, a.salida) if a.consolidar else informe_corrida(a.dir)
    print(f"Informe PDF: {ruta}")


if __name__ == "__main__":
    sys.exit(main())
