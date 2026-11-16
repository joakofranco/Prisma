import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

API = os.environ.get("PERF_API_URL", "https://backend-core:8080").rstrip("/")
AUTH = os.environ.get("PERF_AUTH_URL", "https://nginx:443").rstrip("/")
AUTH_HOST = os.environ.get("PERF_AUTH_HOST_HEADER", "localhost")
REALM = os.environ.get("PERF_REALM", "prisma")
CLIENT_ID = os.environ.get("PERF_CLIENT_ID", "prisma-backend")
PREFIJO = "PERF-"
ORG = "PERF-ORG"
USUARIO_PREFIJO = "perf-"
USUARIO_DOMINIO = os.environ.get("PERF_DOMINIO", "prisma.local")
RESPONSABLE = f"{USUARIO_PREFIJO}resp@{USUARIO_DOMINIO}"

_CTX = ssl.create_default_context()
_CTX.check_hostname = False
_CTX.verify_mode = ssl.CERT_NONE


class ErrorApi(Exception):
    def __init__(self, estado, cuerpo, metodo, url):
        super().__init__(f"{metodo} {url} -> HTTP {estado}: {str(cuerpo)[:300]}")
        self.estado = estado
        self.cuerpo = cuerpo


def pedir(metodo, url, token=None, cuerpo=None, formulario=None, cabeceras=None, timeout=120):
    datos = None
    cab = dict(cabeceras or {})
    if cuerpo is not None:
        datos = json.dumps(cuerpo).encode("utf-8")
        cab["Content-Type"] = "application/json"
    elif formulario is not None:
        datos = urllib.parse.urlencode(formulario).encode("utf-8")
        cab["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        cab["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=datos, method=metodo, headers=cab)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=_CTX) as r:
            texto = r.read().decode("utf-8", "replace")
            estado = r.status
    except urllib.error.HTTPError as e:
        texto = e.read().decode("utf-8", "replace")
        try:
            detalle = json.loads(texto)
        except ValueError:
            detalle = texto
        raise ErrorApi(e.code, detalle, metodo, url) from None
    if not texto:
        return None
    try:
        return json.loads(texto)
    except ValueError:
        return texto


def api(metodo, ruta, token, cuerpo=None):
    return pedir(metodo, API + ruta, token=token, cuerpo=cuerpo)


def token_password(usuario, clave, totp=None):
    secreto = os.environ.get("PERF_CLIENT_SECRET", "")
    if not secreto:
        sys.exit("Falta el secreto del cliente prisma-backend (KEYCLOAK_ADMIN_CLIENT_SECRET en .env o PERF_CLIENT_SECRET).")
    formulario = {
        "grant_type": "password",
        "client_id": CLIENT_ID,
        "client_secret": secreto,
        "username": usuario,
        "password": clave,
    }
    if totp:
        formulario["totp"] = totp
    url = f"{AUTH}/auth/realms/{REALM}/protocol/openid-connect/token"
    try:
        return pedir("POST", url, formulario=formulario, cabeceras={"Host": AUTH_HOST})["access_token"]
    except ErrorApi as e:
        detalle = e.cuerpo.get("error_description", e.cuerpo) if isinstance(e.cuerpo, dict) else e.cuerpo
        sys.exit(f"No se pudo iniciar sesión como {usuario}: HTTP {e.estado} ({detalle}).")


def token_admin():
    usuario = os.environ.get("PERF_ADMIN_USER", "")
    clave = os.environ.get("PERF_ADMIN_PASSWORD", "")
    if not usuario or not clave:
        sys.exit("Definí PERF_ADMIN_USER y PERF_ADMIN_PASSWORD en perf/data/admin.env.")
    return token_password(usuario, clave, os.environ.get("PERF_ADMIN_TOTP") or None)


def paginar(ruta, token, extra=""):
    pagina, salida = 1, []
    while True:
        r = api("GET", f"{ruta}?page={pagina}&pageSize=100{extra}", token)
        if isinstance(r, list):
            return r
        datos = r.get("data", [])
        salida += datos
        if not datos or len(salida) >= r.get("total", 0):
            return salida
        pagina += 1


def letras(n):
    s = ""
    while n > 0:
        n, resto = divmod(n - 1, 26)
        s = chr(65 + resto) + s
    return s
