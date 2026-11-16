#!/usr/bin/env python3
import argparse
import csv
import hashlib
import json
import math
import os
import ssl
import sys
import re
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import datetime, timezone


def leer_props(ruta):
    props = {}
    if not os.path.isfile(ruta):
        return props
    with open(ruta, encoding="utf-8") as f:
        for linea in f:
            linea = linea.strip()
            if not linea or linea.startswith("#") or "=" not in linea:
                continue
            clave, valor = linea.split("=", 1)
            props[clave.strip()] = valor.strip()
    return props


def percentil(ordenados, p):
    if not ordenados:
        return 0
    indice = max(0, math.ceil(p / 100 * len(ordenados)) - 1)
    return ordenados[indice]


def leer_jtl(ruta):
    filas = []
    with open(ruta, encoding="utf-8", newline="") as f:
        for fila in csv.DictReader(f):
            try:
                filas.append(
                    {
                        "ts": int(fila["timeStamp"]),
                        "ms": int(fila["elapsed"]),
                        "label": fila["label"],
                        "code": fila["responseCode"],
                        "ok": fila["success"].lower() == "true",
                    }
                )
            except (KeyError, ValueError):
                continue
    return filas


def estadisticas(filas):
    if not filas:
        return None
    tiempos = sorted(f["ms"] for f in filas)
    inicio = min(f["ts"] for f in filas)
    fin = max(f["ts"] + f["ms"] for f in filas)
    duracion = max((fin - inicio) / 1000, 0.001)
    errores = sum(1 for f in filas if not f["ok"])
    return {
        "muestras": len(filas),
        "media": round(sum(tiempos) / len(tiempos)),
        "min": tiempos[0],
        "p90": percentil(tiempos, 90),
        "p95": percentil(tiempos, 95),
        "p99": percentil(tiempos, 99),
        "max": tiempos[-1],
        "error_pct": round(errores * 100 / len(filas), 2),
        "throughput": round(len(filas) / duracion, 2),
        "inicio": inicio,
        "fin": fin,
    }


def umbral(props, nombre, label=None):
    if label and props.get(f"forzar_umbral.{nombre}") in (None, ""):
        especifico = props.get(f"umbral.{nombre}.{label}")
        if especifico not in (None, ""):
            return float(especifico)
    valor = props.get(f"umbral.{nombre}")
    return float(valor) if valor not in (None, "") else None


def evaluar(est, props, label=None):
    fallas = []
    for nombre, clave, mayor_es_malo in (
        ("p95_ms", "p95", True),
        ("p99_ms", "p99", True),
        ("media_ms", "media", True),
        ("error_pct", "error_pct", True),
        ("throughput_min", "throughput", False),
    ):
        limite = umbral(props, nombre, label)
        if limite is None:
            continue
        valor = est[clave]
        if (mayor_es_malo and valor > limite) or (not mayor_es_malo and valor < limite):
            fallas.append(f"{nombre}: {valor} (umbral {limite:g})")
    return fallas


def consulta_http(url, timeout=8):
    contexto = ssl.create_default_context()
    contexto.check_hostname = False
    contexto.verify_mode = ssl.CERT_NONE
    with urllib.request.urlopen(url, timeout=timeout, context=contexto) as r:
        return json.loads(r.read().decode("utf-8"))


def p95_servidor(props, uri_regex, inicio_ms, fin_ms):
    base = props.get("prometheus_url", "").rstrip("/")
    if not base:
        return None, "Prometheus no configurado (`prometheus_url` vacío)"
    ventana = max(15, int((fin_ms - inicio_ms) / 1000) + 15)
    consulta = (f'histogram_quantile(0.95, sum(increase(http_server_requests_seconds_bucket{{uri=~"{uri_regex}"}}[{ventana}s])) by (le)) * 1000')
    url = f"{base}/api/v1/query?query={urllib.parse.quote(consulta)}&time={fin_ms / 1000 + 10:.0f}"
    try:
        resultado = consulta_http(url).get("data", {}).get("result", [])
    except Exception as e:
        return None, f"Prometheus no disponible en `{base}` ({type(e).__name__})"
    valores = [float(r["value"][1]) for r in resultado if r["value"][1] not in ("NaN", "+Inf", "-Inf")]
    if not valores:
        return None, "Prometheus no devolvió datos para esa ruta (¿lo scrapea backend-core?)"
    return valores[0], None


def metricas_servidor(props, inicio_ms, fin_ms):
    base = props.get("prometheus_url", "").rstrip("/")
    if not base:
        return "Prometheus no configurado (`prometheus_url` vacío).\n"
    consultas = [
        ("CPU del host (%)", '100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100)'),
        ("Heap JVM usado (MB)", 'sum(jvm_memory_used_bytes{area="heap"}) / 1048576'),
        ("Conexiones Hikari activas", "sum(hikaricp_connections_active)"),
        ("Conexiones Hikari en espera", "sum(hikaricp_connections_pending)"),
        (
            "p95 del servidor (ms)",
            "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[1m])) by (le)) * 1000",
        ),
    ]
    lineas = ["| Métrica | Promedio | Máximo |", "|---|---|---|"]
    paso = max(5, int((fin_ms - inicio_ms) / 1000 / 120))
    for nombre, consulta in consultas:
        url = (
            f"{base}/api/v1/query_range?query={urllib.parse.quote(consulta)}"
            f"&start={inicio_ms / 1000:.0f}&end={fin_ms / 1000:.0f}&step={paso}"
        )
        try:
            datos = consulta_http(url)
            valores = [
                float(v[1])
                for serie in datos.get("data", {}).get("result", [])
                for v in serie.get("values", [])
                if v[1] not in ("NaN", "+Inf", "-Inf")
            ]
        except Exception as e:
            return f"Prometheus no disponible en `{base}` ({type(e).__name__}).\n"
        if valores:
            lineas.append(f"| {nombre} | {sum(valores) / len(valores):.1f} | {max(valores):.1f} |")
        else:
            lineas.append(f"| {nombre} | sin datos | sin datos |")
    return "\n".join(lineas) + "\n"


def segundos_desde(marca_iso, inicio_ms):
    try:
        t = datetime.fromisoformat(marca_iso.replace("Z", "+00:00"))
        return round(t.timestamp() - inicio_ms / 1000)
    except (ValueError, AttributeError):
        return None


def validar_muestra_pdf(directorio):
    ruta = os.path.join(directorio, "muestra.pdf")
    if not os.path.isfile(ruta):
        return False, "No se guardó ningún PDF de muestra (ninguna respuesta válida de más de 10 KB)."
    with open(ruta, "rb") as f:
        datos = f.read()
    paginas = len(re.findall(rb"/Type\s*/Page(?![s\w])", datos))
    valido = datos.startswith(b"%PDF-") and b"%%EOF" in datos[-2048:] and len(datos) > 10240 and paginas >= 1
    detalle = f"{len(datos) / 1024:.1f} KB, {paginas} página(s), {'encabezado y cierre PDF correctos' if valido else 'estructura PDF inválida'}"
    return valido, f"PDF de muestra guardado en `muestra.pdf`: {detalle}."


def correo_mailpit(props, nombres, inicio_ms):
    base = props.get("mailpit_url", "").rstrip("/")
    if not base:
        return None, "Mailpit no configurado (`mailpit_url` vacío)", None
    nombres = [n.strip() for n in nombres.split(",") if n.strip()]
    espera = int(float(props.get("correo_espera_s", "60") or 60))
    limite = time.time() + espera
    while True:
        try:
            datos = consulta_http(f"{base}/api/v1/messages?limit=100")
            for m in datos.get("messages", []):
                texto = f"{m.get('Subject', '')} {m.get('Snippet', '')}"
                creado = segundos_desde(m.get("Created", ""), inicio_ms)
                if any(n in texto for n in nombres) and creado is not None and creado >= -5:
                    return True, f"correo recibido en Mailpit: «{m.get('Subject', '')}»", m
        except Exception as e:
            return None, f"Mailpit no disponible en `{base}` ({type(e).__name__})", None
        if time.time() >= limite:
            return False, f"no llegó a Mailpit ningún correo de {' o '.join(nombres)} tras esperar {espera} s", None
        time.sleep(5)


def alerta_activa(props, nombres, inicio_ms=None):
    base = props.get("alertmanager_url", "").rstrip("/")
    if not base:
        return None, "Alertmanager no configurado (`alertmanager_url` vacío)", None
    nombres = [n.strip() for n in nombres.split(",") if n.strip()]
    etiqueta = " o ".join(f"`{n}`" for n in nombres)
    espera = int(float(props.get("dos_espera_alerta_s", "0") or 0))
    limite = time.time() + espera
    while True:
        try:
            alertas = consulta_http(f"{base}/api/v2/alerts")
            activas = [
                a
                for a in alertas
                if a.get("labels", {}).get("alertname") in nombres and a.get("status", {}).get("state") == "active"
            ]
            if activas:
                alerta = activas[0]
                segundos = segundos_desde(alerta.get("startsAt", ""), inicio_ms) if inicio_ms else None
                limite_s = int(float(props.get("dos_alerta_max_s", "300") or 300))
                texto = f"{etiqueta} activa en Alertmanager (`{alerta['labels']['alertname']}`)"
                if segundos is not None:
                    en_plazo = segundos <= limite_s
                    texto += f", disparada a los {segundos} s de iniciada la ráfaga (límite {limite_s} s): {'en plazo' if en_plazo else 'FUERA DE PLAZO'}"
                    return en_plazo, texto, alerta
                return True, texto, alerta
        except Exception as e:
            return None, f"Alertmanager no disponible en `{base}` ({type(e).__name__})", None
        if time.time() >= limite:
            return False, f"{etiqueta} no apareció en Alertmanager tras esperar {espera} s", None
        time.sleep(10)


def tabla(por_label, props):
    lineas = [
        "| Petición | Muestras | Media (ms) | p90 | p95 | p99 | Máx | Error % | Peticiones/s | Resultado |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    veredictos = {}
    for label in sorted(por_label):
        est = por_label[label]
        if label.startswith("_"):
            resultado = "auxiliar"
        else:
            fallas = evaluar(est, props, label)
            veredictos[label] = fallas
            resultado = "cumple" if not fallas else "NO cumple: " + "; ".join(fallas)
        lineas.append(
            f"| {label} | {est['muestras']} | {est['media']} | {est['p90']} | {est['p95']} | {est['p99']} "
            f"| {est['max']} | {est['error_pct']} | {est['throughput']} | {resultado} |"
        )
    return "\n".join(lineas), veredictos


def hashes(directorio):
    salida = []
    for nombre in sorted(os.listdir(directorio)):
        ruta = os.path.join(directorio, nombre)
        if os.path.isfile(ruta) and nombre != "hashes.sha256":
            h = hashlib.sha256()
            with open(ruta, "rb") as f:
                for bloque in iter(lambda: f.read(1 << 20), b""):
                    h.update(bloque)
            salida.append(f"{h.hexdigest()}  {nombre}")
    with open(os.path.join(directorio, "hashes.sha256"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(salida) + "\n")


def parametros_md(props):
    claves = [
        "caso", "titulo", "plan", "modo", "objetivo", "usuarios", "rampa_s", "duracion_s", "iteraciones",
        "pausa_ms", "endpoints", "umbrales_de_la_corrida", "commit", "fecha",
    ]
    lineas = ["| Parámetro | Valor |", "|---|---|"]
    for clave in claves:
        if props.get(clave) not in (None, ""):
            lineas.append(f"| {clave} | {props[clave]} |")
    umbrales = sorted(k for k in props if k.startswith("umbral.") and props[k] != "")
    for clave in umbrales:
        lineas.append(f"| {clave} | {props[clave]} |")
    return "\n".join(lineas)


def resumir(directorio):
    props = leer_props(os.path.join(directorio, "efectivo.properties"))
    jtl = os.path.join(directorio, "resultados.jtl")
    if not os.path.isfile(jtl):
        print(f"No existe {jtl}", file=sys.stderr)
        return 2
    filas = leer_jtl(jtl)
    medibles = [f for f in filas if not f["label"].startswith("_")]
    if not medibles:
        print("El archivo de resultados no tiene muestras medibles", file=sys.stderr)
        return 2
    por_label = defaultdict(list)
    for f in filas:
        por_label[f["label"]].append(f)
    est_label = {k: estadisticas(v) for k, v in por_label.items()}
    total = estadisticas(medibles)
    fallas_total = evaluar(total, props)
    tabla_md, veredictos = tabla(est_label, props)
    codigos = Counter(f["code"] for f in medibles)
    login_fallidos = sum(1 for f in filas if f["label"] == "_login" and not f["ok"])

    cumple = not fallas_total and not any(veredictos.values())
    extra = []
    min_429 = props.get("umbral.min_429")
    if min_429 not in (None, ""):
        cantidad = codigos.get("429", 0)
        ok_429 = cantidad >= int(float(min_429))
        cumple = ok_429
        extra.append(f"- Respuestas 429 obtenidas: {cantidad} (mínimo esperado {min_429}): {'cumple' if ok_429 else 'NO cumple'}.")
        extra.append("- fail2ban puede haber baneado la IP del generador de carga. Para liberarla: "
                     "`docker exec <contenedor-fail2ban> fail2ban-client unban <IP>` (ver `fail2ban-client banned`).")
        nombre_alerta = props.get("dos_alerta", "")
        if nombre_alerta:
            estado, detalle, alerta = alerta_activa(props, nombre_alerta, total["inicio"])
            extra.append(f"- Alerta: {detalle}.")
            if alerta:
                with open(os.path.join(directorio, "evidencia-alerta.json"), "w", encoding="utf-8", newline="\n") as f:
                    json.dump(alerta, f, ensure_ascii=False, indent=2)
            if estado is False and props.get("dos_alerta_obligatoria", "false") == "true":
                cumple = False
            if props.get("mailpit_url", ""):
                ok_correo, detalle_correo, mensaje = correo_mailpit(props, nombre_alerta, total["inicio"])
                extra.append(f"- Correo: {detalle_correo}.")
                if mensaje:
                    with open(os.path.join(directorio, "evidencia-correo.json"), "w", encoding="utf-8", newline="\n") as f:
                        json.dump({k: mensaje.get(k) for k in ("From", "To", "Subject", "Created", "Snippet")}, f, ensure_ascii=False, indent=2)
                if ok_correo is False and props.get("mailpit_obligatorio", "false") == "true":
                    cumple = False
    uri_servidor = {"madurez": ".*/calculate", "reporte-pdf": ".*/reports/.*"}.get(props.get("plan", ""))
    if uri_servidor:
        valor, motivo = p95_servidor(props, uri_servidor, total["inicio"], total["fin"])
        nombre = "cálculo de madurez" if props.get("plan") == "madurez" else "reporte"
        if valor is not None:
            extra.append(f"- p95 medido en el servidor ({nombre}, Prometheus): {valor:.0f} ms. "
                         "La tabla de abajo mide desde el generador de carga e incluye la red y el cliente.")
        else:
            extra.append(f"- p95 medido en el servidor ({nombre}): no disponible. {motivo}.")
    if props.get("plan") == "reporte-pdf":
        ok_pdf, detalle_pdf = validar_muestra_pdf(directorio)
        extra.append(f"- {detalle_pdf}")
        extra.append("- La primera generación de cada evaluación se mide aparte (petición `reporte-primera-generacion`); "
                     "las repeticiones figuran como `reporte`. El servidor no cachea los reportes.")
        if not ok_pdf:
            cumple = False
    if login_fallidos and min_429 not in (None, ""):
        extra.append(f"- Inicios de sesión rechazados durante la ráfaga: {login_fallidos} (esperable cuando fail2ban banea al generador).")
    elif login_fallidos:
        extra.append(f"- Inicios de sesión fallidos: {login_fallidos}. Revisar usuarios PERF- y el secreto del cliente.")
        cumple = False

    veredicto = "CUMPLE" if cumple else "NO CUMPLE"
    inicio = datetime.fromtimestamp(total["inicio"] / 1000, timezone.utc)
    fin = datetime.fromtimestamp(total["fin"] / 1000, timezone.utc)
    md = [
        f"# {props.get('caso', 'Caso')} — {props.get('titulo', '')}".rstrip(" —"),
        "",
        f"**Resultado: {veredicto}**",
        "",
        f"- Ventana: {inicio:%Y-%m-%d %H:%M:%S} a {fin:%H:%M:%S} UTC.",
        f"- Total medido: {total['muestras']} peticiones, p95 {total['p95']} ms, error {total['error_pct']} %, "
        f"{total['throughput']} peticiones/s.",
    ]
    if fallas_total:
        md.append("- Umbrales globales incumplidos: " + "; ".join(fallas_total) + ".")
    md += extra
    md += ["", "## Tiempos por petición", "", tabla_md, "", "## Códigos de respuesta", "", "| Código | Cantidad |", "|---|---|"]
    md += [f"| {codigo} | {cantidad} |" for codigo, cantidad in sorted(codigos.items())]
    md += ["", "## Parámetros de la corrida", "", parametros_md(props), ""]
    md += ["Las filas marcadas como auxiliar (inicio de sesión) no cuentan para los umbrales.", ""]
    with open(os.path.join(directorio, "resumen.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md))

    servidor = "# Métricas del servidor durante la corrida\n\n" + metricas_servidor(props, total["inicio"], total["fin"])
    with open(os.path.join(directorio, "servidor.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(servidor)

    resultado = {
        "caso": props.get("caso", ""),
        "titulo": props.get("titulo", ""),
        "cumple": cumple,
        "usuarios": props.get("usuarios", ""),
        "muestras": total["muestras"],
        "p95": total["p95"],
        "p99": total["p99"],
        "media": total["media"],
        "error_pct": total["error_pct"],
        "throughput": total["throughput"],
        "directorio": os.path.basename(os.path.normpath(directorio)),
    }
    with open(os.path.join(directorio, "resultado.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(resultado, f, ensure_ascii=False, indent=2)
    hashes(directorio)
    print(f"{resultado['caso']}: {veredicto} | p95 {total['p95']} ms | error {total['error_pct']} % | "
          f"{total['throughput']} pet/s | {total['muestras']} muestras")
    return 0 if cumple else 1


def linea_base(directorio):
    carpeta = os.path.dirname(os.path.abspath(directorio))
    candidatos = sorted(n for n in os.listdir(carpeta) if n.endswith("-CP-RND-01"))
    for nombre in reversed(candidatos):
        ruta = os.path.join(carpeta, nombre, "resultado.json")
        if os.path.isfile(ruta):
            with open(ruta, encoding="utf-8") as f:
                return nombre, json.load(f)
    return None, None


def evaluar_recuperacion(directorio, props):
    ruta = os.path.join(directorio, "recuperacion", "resultado.json")
    if not os.path.isfile(ruta):
        return None
    with open(ruta, encoding="utf-8") as f:
        rec = json.load(f)
    tolerancia = float(props.get("recuperacion_tolerancia_pct") or 20)
    nombre, base = linea_base(directorio)
    duracion = leer_props(os.path.join(directorio, "recuperacion", "efectivo.properties")).get("duracion_s", "")
    cabecera = (f"Tras el estrés se corrieron {duracion} s con {rec['usuarios']} usuario(s), "
                f"sin reiniciar nada: p95 {rec['p95']} ms, error {rec['error_pct']} %.")
    if not base:
        detalle = (cabecera + " No hay una corrida de CP-RND-01 en perf/results para usar de línea base, "
                   "por lo que la recuperación no se pudo evaluar.")
        return {"p95": rec["p95"], "base_p95": None, "limite": None, "recuperado": None,
                "etiqueta": "recuperación sin línea base", "detalle": detalle}
    limite = round(base["p95"] * (1 + tolerancia / 100), 1)
    recuperado = rec["p95"] <= limite
    detalle = (cabecera + f" Línea base (CP-RND-01, `{nombre}`): p95 {base['p95']} ms; el límite es {limite} ms "
               f"(línea base + {tolerancia:g} %). **{'Recuperado' if recuperado else 'No recuperado'}**.")
    return {"p95": rec["p95"], "base_p95": base["p95"], "limite": limite, "recuperado": recuperado,
            "etiqueta": "recuperado" if recuperado else "no recuperado", "detalle": detalle}


def resumir_estres(directorio):
    props = leer_props(os.path.join(directorio, "efectivo.properties"))
    pasos = []
    for nombre in sorted(os.listdir(directorio)):
        ruta = os.path.join(directorio, nombre, "resultado.json")
        if nombre.startswith("paso-") and os.path.isfile(ruta):
            with open(ruta, encoding="utf-8") as f:
                pasos.append(json.load(f))
    pasos.sort(key=lambda p: int(p["usuarios"]))
    if not pasos:
        print("No hay escalones con resultados", file=sys.stderr)
        return 2
    quiebre = next((p for p in pasos if not p["cumple"]), None)
    ultimo_ok = None
    for p in pasos:
        if p["cumple"]:
            ultimo_ok = p
        else:
            break
    recuperacion = evaluar_recuperacion(directorio, props)
    md = [f"# {props.get('caso', 'Estrés')} — {props.get('titulo', '')}".rstrip(" —"), ""]
    if quiebre:
        md.append(f"**Punto de quiebre: {quiebre['usuarios']} usuarios concurrentes** "
                  f"(p95 {quiebre['p95']} ms, error {quiebre['error_pct']} %).")
        if ultimo_ok:
            md.append(f"\nÚltimo escalón dentro de los umbrales: {ultimo_ok['usuarios']} usuarios "
                      f"({ultimo_ok['throughput']} peticiones/s).")
    else:
        md.append(f"**No se alcanzó el punto de quiebre** hasta {pasos[-1]['usuarios']} usuarios; "
                  "ampliar `escalones` para seguir buscándolo.")
    md += ["", "| Usuarios | Muestras | Media (ms) | p95 | p99 | Error % | Peticiones/s | Dentro de umbrales |",
           "|---|---|---|---|---|---|---|---|"]
    for p in pasos:
        md.append(f"| {p['usuarios']} | {p['muestras']} | {p['media']} | {p['p95']} | {p['p99']} | {p['error_pct']} "
                  f"| {p['throughput']} | {'sí' if p['cumple'] else 'no'} |")
    if recuperacion:
        md += ["", "## Recuperación", "", recuperacion["detalle"]]
    md += ["", "## Parámetros de la corrida", "", parametros_md(props), "",
           "Cada escalón tiene su carpeta `paso-NNNN` con el detalle y el reporte HTML.", ""]
    with open(os.path.join(directorio, "resumen.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md))
    resultado = {
        "caso": props.get("caso", ""),
        "titulo": props.get("titulo", ""),
        "cumple": recuperacion["recuperado"] is not False if recuperacion else True,
        "recuperacion": recuperacion,
        "usuarios": quiebre["usuarios"] if quiebre else pasos[-1]["usuarios"],
        "muestras": sum(p["muestras"] for p in pasos),
        "p95": (ultimo_ok or pasos[0])["p95"],
        "p99": (ultimo_ok or pasos[0])["p99"],
        "media": (ultimo_ok or pasos[0])["media"],
        "error_pct": (ultimo_ok or pasos[0])["error_pct"],
        "throughput": (ultimo_ok or pasos[0])["throughput"],
        "directorio": os.path.basename(os.path.normpath(directorio)),
        "nota": (f"quiebre en {quiebre['usuarios']} usuarios" if quiebre else "sin quiebre")
        + (f"; {recuperacion['etiqueta']}" if recuperacion else ""),
    }
    with open(os.path.join(directorio, "resultado.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(resultado, f, ensure_ascii=False, indent=2)
    hashes(directorio)
    print(f"{resultado['caso']}: {resultado['nota']}")
    return 0


def consolidar(salida, directorios):
    filas = []
    for d in directorios:
        ruta = os.path.join(d, "resultado.json")
        if os.path.isfile(ruta):
            with open(ruta, encoding="utf-8") as f:
                filas.append(json.load(f))
    md = ["# Informe consolidado de performance — PRISMA", "",
          f"Generado: {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC", "",
          "| Caso | Descripción | Usuarios | Muestras | Media (ms) | p95 | p99 | Error % | Peticiones/s | Resultado | Carpeta |",
          "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in filas:
        resultado = r.get("nota") or ("cumple" if r["cumple"] else "NO cumple")
        md.append(f"| {r['caso']} | {r['titulo']} | {r['usuarios']} | {r['muestras']} | {r['media']} | {r['p95']} "
                  f"| {r['p99']} | {r['error_pct']} | {r['throughput']} | {resultado} | `{r['directorio']}` |")
    md.append("")
    with open(salida, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md))
    print(f"Informe consolidado: {salida}")
    return 0 if all(r["cumple"] for r in filas) else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir")
    ap.add_argument("--estres", action="store_true")
    ap.add_argument("--consolidar", nargs="+")
    ap.add_argument("--salida")
    a = ap.parse_args()
    if a.consolidar:
        return consolidar(a.salida, a.consolidar)
    if a.estres:
        return resumir_estres(a.dir)
    return resumir(a.dir)


if __name__ == "__main__":
    sys.exit(main())
