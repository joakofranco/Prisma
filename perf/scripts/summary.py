#!/usr/bin/env python3
import argparse
import csv
import hashlib
import json
import math
import os
import ssl
import sys
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
    if label:
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


def alerta_activa(props, nombre):
    base = props.get("alertmanager_url", "").rstrip("/")
    if not base:
        return None, "Alertmanager no configurado (`alertmanager_url` vacío)"
    espera = int(float(props.get("dos_espera_alerta_s", "0") or 0))
    limite = time.time() + espera
    while True:
        try:
            alertas = consulta_http(f"{base}/api/v2/alerts")
            activas = [
                a
                for a in alertas
                if a.get("labels", {}).get("alertname") == nombre and a.get("status", {}).get("state") == "active"
            ]
            if activas:
                return True, f"`{nombre}` activa en Alertmanager"
        except Exception as e:
            return None, f"Alertmanager no disponible en `{base}` ({type(e).__name__})"
        if time.time() >= limite:
            return False, f"`{nombre}` no apareció en Alertmanager tras esperar {espera} s"
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
        "pausa_ms", "endpoints", "commit", "fecha",
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
            estado, detalle = alerta_activa(props, nombre_alerta)
            extra.append(f"- Alerta: {detalle}.")
            if estado is False and props.get("dos_alerta_obligatoria", "false") == "true":
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
    md += ["", "## Parámetros de la corrida", "", parametros_md(props), "",
           "Cada escalón tiene su carpeta `paso-NNNN` con el detalle y el reporte HTML.", ""]
    with open(os.path.join(directorio, "resumen.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md))
    resultado = {
        "caso": props.get("caso", ""),
        "titulo": props.get("titulo", ""),
        "cumple": True,
        "usuarios": quiebre["usuarios"] if quiebre else pasos[-1]["usuarios"],
        "muestras": sum(p["muestras"] for p in pasos),
        "p95": (ultimo_ok or pasos[0])["p95"],
        "p99": (ultimo_ok or pasos[0])["p99"],
        "media": (ultimo_ok or pasos[0])["media"],
        "error_pct": (ultimo_ok or pasos[0])["error_pct"],
        "throughput": (ultimo_ok or pasos[0])["throughput"],
        "directorio": os.path.basename(os.path.normpath(directorio)),
        "nota": f"quiebre en {quiebre['usuarios']} usuarios" if quiebre else "sin quiebre",
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
