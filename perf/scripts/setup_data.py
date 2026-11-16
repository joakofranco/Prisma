#!/usr/bin/env python3
import os
import secrets
import string
import sys

from prisma_api import (ORG, PREFIJO, RESPONSABLE, USUARIO_DOMINIO, USUARIO_PREFIJO, ErrorApi, api, letras, paginar,
                        token_admin, token_password)
from summary import leer_props

DATOS = "/perf/data/datos.properties"
CANTIDAD = int(os.environ.get("PERF_USUARIOS", "20"))
CANTIDAD_APROBADAS = int(os.environ.get("PERF_EVAL_APROBADAS", "5"))


def clave_nueva():
    alfabeto = string.ascii_letters + string.digits
    return "Pf" + "".join(secrets.choice(alfabeto) for _ in range(20)) + "-9a_Z"


def asegurar_org(token):
    for org in paginar("/api/organizations", token):
        if org.get("name") == ORG:
            if org.get("enabled") is False:
                api("PUT", f"/api/organizations/{org['id']}/enable", token)
                print(f"Organización {ORG} reactivada")
            return org["id"]
    creada = api("POST", "/api/organizations", token, {"name": ORG})
    print(f"Organización creada: {ORG}")
    return creada["id"]


def asegurar_usuario(token, existentes, email, apellido, roles, org_id, clave):
    cuerpo = {
        "email": email,
        "firstName": "Perf",
        "lastName": apellido,
        "organizationIds": [org_id],
        "roles": roles,
        "password": clave,
        "enabled": True,
    }
    actual = existentes.get(email)
    if actual:
        api("PUT", f"/api/users/{actual['id']}", token, cuerpo)
        return actual["id"], False
    creado = api("POST", "/api/users", token, cuerpo)["id"]
    api("PUT", f"/api/users/{creado}", token, cuerpo)
    return creado, True


def asegurar_evaluacion(token, existentes, nombre, org_id, version):
    actual = existentes.get(nombre)
    if actual:
        return actual, False
    creada = api("POST", "/api/evaluations", token, {"name": nombre, "organizationId": org_id, "catalogVersion": version})
    return creada, True


def poner_en_curso(token, evaluacion):
    if evaluacion.get("status") == "DRAFT":
        api("PATCH", f"/api/evaluations/{evaluacion['id']}/status", token, {"status": "IN_PROGRESS"})


def main():
    previo = leer_props(DATOS)
    clave = previo.get("usuario_password") or clave_nueva()
    token = token_admin()

    versiones = api("GET", "/api/catalog/versions", token).get("versions", [])
    if not versiones:
        sys.exit("No hay ningún catálogo cargado: corré primero la carga del catálogo (make seed).")
    version = os.environ.get("PERF_CATALOG_VERSION") or sorted(versiones)[-1]
    controles = [c["id"] for c in api("GET", f"/api/catalog/{version}/controls", token)]
    print(f"Catálogo {version}: {len(controles)} controles")

    org_id = asegurar_org(token)
    usuarios = {u["email"]: u for u in paginar("/api/users", token) if u.get("email", "").startswith(USUARIO_PREFIJO)}
    evaluaciones = {
        e["name"]: e for e in paginar("/api/evaluations", token, f"&organizationId={org_id}") if e["name"].startswith(PREFIJO)
    }

    creados = 0
    _, nuevo = asegurar_usuario(token, usuarios, RESPONSABLE, "Responsable", ["ORG_RESPONSIBLE"], org_id, clave)
    creados += nuevo
    lineas = []
    for i in range(1, CANTIDAD + 1):
        nn = f"{i:02d}"
        email = f"{USUARIO_PREFIJO}{nn}@{USUARIO_DOMINIO}"
        _, nuevo = asegurar_usuario(token, usuarios, email, f"Evaluador {letras(i)}", ["INTERNAL_EVALUATOR"], org_id, clave)
        creados += nuevo
        evaluacion, _ = asegurar_evaluacion(token, evaluaciones, f"{PREFIJO}EVAL-{nn}", org_id, version)
        poner_en_curso(token, evaluacion)
        lineas.append(f"eval_{nn}={evaluacion['id']}")
    print(f"Usuarios PERF-: {CANTIDAD} evaluadores y 1 responsable ({creados} creados ahora)")

    completa, nueva = asegurar_evaluacion(token, evaluaciones, f"{PREFIJO}EVAL-COMPLETA", org_id, version)
    poner_en_curso(token, completa)
    respondidos = {r.get("controlId") for r in api("GET", f"/api/evaluations/{completa['id']}/responses", token) or []}
    faltan = [c for c in controles if c not in respondidos]
    for control in faltan:
        api("POST", f"/api/evaluations/{completa['id']}/responses", token,
            {"controlId": control, "compliant": True, "observations": "PERF"})
    api("POST", f"/api/evaluations/{completa['id']}/calculate", token)
    print(f"Evaluación {PREFIJO}EVAL-COMPLETA: {len(controles)} controles respondidos ({len(faltan)} cargados ahora) y madurez calculada")

    aprobadas = []
    for n in range(1, CANTIDAD_APROBADAS + 1):
        nombre = f"{PREFIJO}EVAL-APROBADA" if n == 1 else f"{PREFIJO}EVAL-APROBADA-{n:02d}"
        aprobada, _ = asegurar_evaluacion(token, evaluaciones, nombre, org_id, version)
        if aprobada.get("status") != "APPROVED":
            poner_en_curso(token, aprobada)
            respondidos = {r.get("controlId") for r in api("GET", f"/api/evaluations/{aprobada['id']}/responses", token) or []}
            for control in [c for c in controles if c not in respondidos]:
                api("POST", f"/api/evaluations/{aprobada['id']}/responses", token,
                    {"controlId": control, "compliant": True, "observations": "PERF"})
            api("POST", f"/api/evaluations/{aprobada['id']}/calculate", token)
            actual = api("GET", f"/api/evaluations/{aprobada['id']}", token).get("status")
            for estado in ("READY_FOR_AUDIT", "IN_AUDIT", "APPROVED"):
                if actual == "APPROVED":
                    break
                if estado == "READY_FOR_AUDIT" and actual != "IN_PROGRESS":
                    continue
                if estado == "IN_AUDIT" and actual not in ("IN_PROGRESS", "READY_FOR_AUDIT"):
                    continue
                api("PATCH", f"/api/evaluations/{aprobada['id']}/status", token, {"status": estado})
                actual = estado
        aprobadas.append(aprobada)
        print(f"Evaluación {nombre} lista para reportes PDF (estado APPROVED)")
    aprobada = aprobadas[0]

    token_password(f"{USUARIO_PREFIJO}01@{USUARIO_DOMINIO}", clave)
    print("Inicio de sesión de un usuario PERF- verificado")

    salida = [
        f"total_usuarios={CANTIDAD}",
        f"usuario_prefijo={USUARIO_PREFIJO}",
        f"usuario_dominio={USUARIO_DOMINIO}",
        f"usuario_password={clave}",
        f"responsable_email={RESPONSABLE}",
        f"org_id={org_id}",
        f"catalog_version={version}",
        f"eval_completa={completa['id']}",
        f"eval_aprobada={aprobada['id']}",
        f"total_aprobadas={len(aprobadas)}",
        f"total_controles={len(controles)}",
    ] + lineas + [f"eval_aprobada_{i}={a['id']}" for i, a in enumerate(aprobadas, 1)] + \
        [f"control_{i}={c}" for i, c in enumerate(controles, 1)]
    os.makedirs(os.path.dirname(DATOS), exist_ok=True)
    with open(DATOS, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(salida) + "\n")
    print("Datos listos en perf/data/datos.properties")


if __name__ == "__main__":
    try:
        main()
    except ErrorApi as e:
        sys.exit(f"Falló la preparación de datos: {e}")
