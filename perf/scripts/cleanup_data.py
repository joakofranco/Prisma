#!/usr/bin/env python3
import os
import sys

from prisma_api import ORG, PREFIJO, USUARIO_PREFIJO, ErrorApi, api, paginar, token_admin

DATOS = "/perf/data/datos.properties"


def borrar(token, ruta, nombre):
    try:
        api("DELETE", ruta, token)
        return "borrado"
    except ErrorApi as e:
        if e.estado == 409:
            return "conservado"
        print(f"  No se pudo borrar {nombre}: HTTP {e.estado} ({str(e.cuerpo)[:160]})")
        return "error"


def deshabilitar(token, usuario):
    cuerpo = {
        "email": usuario["email"],
        "firstName": usuario.get("firstName"),
        "lastName": usuario.get("lastName"),
        "roles": sorted(usuario.get("roles") or []),
        "organizationIds": sorted(usuario.get("organizationIds") or []),
        "enabled": False,
    }
    try:
        api("PUT", f"/api/users/{usuario['id']}", token, cuerpo)
        return True
    except ErrorApi as e:
        print(f"  No se pudo deshabilitar {usuario['email']}: HTTP {e.estado} ({str(e.cuerpo)[:160]})")
        return False


def main():
    token = token_admin()
    org = next((o for o in paginar("/api/organizations", token) if o.get("name") == ORG), None)
    evaluaciones_restantes = 0
    if not org:
        print(f"No existe la organización {ORG}: no hay evaluaciones PERF- que borrar.")
    else:
        evaluaciones = [
            e for e in paginar("/api/evaluations", token, f"&organizationId={org['id']}") if e["name"].startswith(PREFIJO)
        ]
        estados = [borrar(token, f"/api/evaluations/{e['id']}", e["name"]) for e in evaluaciones]
        evaluaciones_restantes = len(evaluaciones) - estados.count("borrado")
        print(f"Evaluaciones PERF-: {estados.count('borrado')} borradas, {evaluaciones_restantes} conservadas (de {len(evaluaciones)})")

    usuarios = [u for u in paginar("/api/users", token) if u.get("email", "").startswith(USUARIO_PREFIJO)]
    borrados = deshabilitados = 0
    for u in usuarios:
        resultado = borrar(token, f"/api/users/{u['id']}", u["email"])
        if resultado == "borrado":
            borrados += 1
        elif resultado == "conservado" and deshabilitar(token, u):
            deshabilitados += 1
    print(f"Usuarios PERF-: {borrados} borrados, {deshabilitados} deshabilitados por tener historial (de {len(usuarios)})")

    if org:
        if evaluaciones_restantes or deshabilitados:
            print(f"La organización {ORG} se conserva porque aún tiene datos con historial.")
        elif borrar(token, f"/api/organizations/{org['id']}", ORG) == "borrado":
            print(f"Organización {ORG} borrada")
    if os.path.isfile(DATOS):
        os.remove(DATOS)
    print("Lo que se conserva queda identificado con el prefijo PERF-; make perf-setup lo reutiliza.")


if __name__ == "__main__":
    try:
        main()
    except ErrorApi as e:
        sys.exit(f"Falló la limpieza: {e}")
