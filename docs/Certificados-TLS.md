# 🔐 HTTPS y certificados TLS — PRISMA

> Cómo está armado el cifrado en tránsito de PRISMA en desarrollo local: un único punto de
> entrada HTTPS (nginx), certificado autofirmado con validez configurable, y el procedimiento para
> generarlo/renovarlo sin tocar ninguna configuración de nginx.

## 1. Arquitectura: un solo punto de entrada HTTPS

Antes de este documento, el navegador llamaba a cada servicio por su puerto expuesto en HTTP
plano (`http://localhost:8081` a backend-core, `http://localhost:8000` a backend-ai,
`http://localhost:8180` a Keycloak) aunque la SPA se sirviera por HTTPS — el JWT, las credenciales
del login y la evidencia subida viajaban sin cifrar en esos tramos. Eso ya está corregido:

```
Navegador ──HTTPS (443)──▶ nginx (infra/nginx/conf.d/prisma.conf)
                              ├─ /            → frontend (SPA Vue, HTTP interno)
                              ├─ /api/*        → backend-core:8080 (HTTP interno)
                              ├─ /ai/*         → backend-ai:8000 (HTTP interno)
                              └─ /auth/*       → keycloak:8080 (HTTP interno)
```

La SPA usa **rutas relativas** (`VITE_API_BASE_URL=/api`, `VITE_AI_API_BASE_URL=/ai/api/v1`,
`VITE_KEYCLOAK_URL=/auth`, ver `.env.example`) — el navegador nunca sale del origen HTTPS desde el
que se sirvió la página. Los tramos `nginx → servicio` quedan en HTTP simple porque viajan por la
red interna de Docker (`prisma-net`), no por la red pública; es el mismo criterio que separa
"cifrado en tránsito hacia el cliente" (obligatorio, cruza una red no confiable) de "cifrado
interno entre contenedores del mismo host" (mTLS/service mesh, una capa adicional que este
proyecto no implementa).

El contenedor del propio frontend (`apps/frontend/nginx.conf`) tiene la **misma** definición de
`/api`, `/ai`, `/auth` que el reverse-proxy principal, así que acceder directo a
`http://localhost:8080` (sin pasar por el :443) funciona igual — la única diferencia es que ese
tramo no está cifrado, por eso `https://prisma.local` es la URL recomendada, no
`http://localhost:8080`.

**El propio nginx principal también sirve la app por HTTP plano en el :80** (no redirige a
HTTPS): `infra/nginx/conf.d/prisma.conf` tiene un server block en el :80 y otro en el :443, ambos
con las mismas `location` (compartidas vía `infra/nginx/conf.d/prisma-locations.inc`) — es una
forma de acceso soportada tanto como el :443, no sólo un fallback de desarrollo. La única
diferencia entre los dos es que `X-Forwarded-Proto` viaja como `http` o `https` según por cuál
entró el navegador (`$scheme`, no fijo), y Keycloak arma el claim `iss` del token acorde — por eso
`KEYCLOAK_TRUSTED_ISSUERS` (`.env.example`) lista **las dos variantes** (`http://` y `https://`)
de cada host soportado, no sólo la https. Si necesitás forzar HTTPS igual (por ejemplo, exponer el
:80 sólo dentro de una red confiable) es decisión de infraestructura fuera de este repo — este
nginx no lo hace por defecto.

## 2. El certificado: autofirmado, 5 años, modular

`infra/nginx/conf.d/prisma.conf` referencia dos nombres de archivo **fijos**:

```nginx
ssl_certificate     /etc/nginx/certs/prisma.crt;
ssl_certificate_key /etc/nginx/certs/prisma.key;
```

Nunca hay que tocar esta configuración para renovar o cambiar el certificado — alcanza con
reemplazar esos dos archivos (gitignored, uno por máquina/entorno:
`infra/nginx/certs/*.crt`/`*.key`, ver `.gitignore`) y reiniciar el contenedor de nginx. Eso es
toda la "modularidad": el script de generación es la única pieza que cambia.

### Generar o renovar el certificado

```bash
./scripts/generate-nginx-cert.sh          # 5 años (1825 días), CN=prisma.local
# — o con Make —
make certs                                # pide confirmación si ya existe uno
make certs-force                          # lo reemplaza sin preguntar

make restart-nginx                        # para que nginx cargue el nuevo certificado
```

Opciones del script (todas con default razonable, ninguna es obligatoria):

| Flag | Default | Qué hace |
|---|---|---|
| `--days N` | `1825` (5 años) | Validez del certificado. |
| `--cn HOST` | `prisma.local` | Common Name (host principal). |
| `--san H1,H2,...` | `prisma.local,localhost,127.0.0.1` | Subject Alternative Names — detecta automáticamente si cada entrada es una IP o un hostname. |
| `--out-dir DIR` | `infra/nginx/certs` | Dónde escribir `prisma.crt`/`prisma.key`. |
| `--force` | — | No pregunta confirmación si ya existe un certificado (lo sobreescribe). |

También se puede invocar 100% no interactivo con variables de entorno (`CERT_DAYS`, `CERT_CN`,
`CERT_SAN`, `CERT_OUT_DIR`) — útil desde un pipeline de CI/CD que provisione un entorno efímero.

### Verificar cuándo vence

```bash
openssl x509 -in infra/nginx/certs/prisma.crt -noout -enddate
# notAfter=Aug 28 18:48:28 2031 GMT
```

Cuando falten pocas semanas para esa fecha (o ya venció): `make certs-force && make
restart-nginx` — nada más. No hace falta reconstruir ninguna imagen ni tocar `docker-compose.yml`.

### Por qué autofirmado y no una CA pública en local

Un certificado de una CA pública (Let's Encrypt, etc.) exige que el dominio sea resoluble desde
internet para el challenge de validación — no aplica a `prisma.local`, que solo existe en el
`/etc/hosts` de cada desarrollador. Para producción (dominio real, público), la recomendación es
Let's Encrypt + `cert-manager` (si se despliega en Kubernetes, ver
[`Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md)) o el certificado que
gestione el balanceador de carga del proveedor cloud — no este script, que es exclusivamente para
desarrollo local.

### Alternativa: `mkcert` (sin advertencia del navegador)

El script anterior genera un certificado **autofirmado real** (sin una CA detrás): el navegador
va a mostrar la advertencia de "conexión no privada" la primera vez que se acepta por
origen/máquina. Si eso molesta y se prefiere que el navegador confíe en el certificado sin
advertencias, la alternativa es `mkcert` (instala una CA local en el sistema y firma con ella):

```bash
mkcert -install                     # una sola vez por máquina
cd infra/nginx/certs
mkcert -cert-file prisma.crt -key-file prisma.key prisma.local localhost 127.0.0.1
```

**Trade-off:** `mkcert` limita la validez a ~825 días (regla del CA/Browser Forum que respeta
aunque la CA sea local), no se le puede pedir 5 años — por eso el script propio es el default del
proyecto. Ambos enfoques escriben los mismos dos archivos (`prisma.crt`/`prisma.key`) en el mismo
lugar; nginx no distingue cuál los generó.

## 3. Hostname dinámico de Keycloak: el login funciona por cualquier host configurado

`/etc/hosts` **no hace falta editarlo** para poder loguearse. Keycloak corre con hostname
"dinámico" (sin `KC_HOSTNAME` fijo): arma cada URL que genera — el link de login, el `action` del
formulario de credenciales, el claim `iss` del token — a partir del `Host` real de cada request
(vía `X-Forwarded-Host`/`X-Forwarded-Proto`, que nginx agrega y Keycloak respeta gracias a
`KC_PROXY_HEADERS=xforwarded`), en vez de forzar siempre un único hostname configurado de antemano.
En la práctica: entrar por `https://prisma.local` te loguea contra `https://prisma.local/auth/...`,
y entrar por `https://localhost` te loguea contra `https://localhost/auth/...` — **ambos
funcionan sin ninguna configuración extra por máquina**.

> **Por qué esto importa (bug real que corrigió esto):** una versión anterior de este documento
> fijaba `KC_HOSTNAME=https://prisma.local/auth`. Con un hostname fijo, el formulario de login
> quedaba apuntando SIEMPRE a esa URL — si entrabas por `https://localhost` (sin haber agregado
> `prisma.local` a `/etc/hosts`), la página de login cargaba bien, pero al enviar las credenciales
> el navegador intentaba ir directo a `https://prisma.local`, que no podía resolver → "no se puede
> abrir este sitio web". El hostname dinámico elimina esa dependencia por completo.

Como el claim `iss` ahora varía según el host **y el protocolo** (http/https, ver §1) usados para
loguearse, backend-core no valida contra un único valor sino contra una **lista de orígenes
confiables** (`KEYCLOAK_TRUSTED_ISSUERS` → `prisma.keycloak.trusted-issuers` →
`SecurityConfig.trustedIssuerValidator`, ver `apps/backend-core/src/main/java/uy/edu/prisma/config/SecurityConfig.java`).
Un token con un `iss` que no esté en esa lista se rechaza con 401 aunque la firma sea válida — es
decir, la SPA carga igual desde cualquier host/protocolo que nginx sirva, pero **el login sólo
funciona desde un host+protocolo que esté en `KEYCLOAK_TRUSTED_ISSUERS`**. Agregar un dominio
nuevo (por ejemplo, uno de producción) es sumar sus dos variantes (`http://` y `https://`) a esa
lista, sin tocar código:

```bash
# .env
KEYCLOAK_TRUSTED_ISSUERS=https://prisma.local/auth/realms/prisma,http://prisma.local/auth/realms/prisma,https://localhost/auth/realms/prisma,http://localhost/auth/realms/prisma,https://mi-dominio.example/auth/realms/prisma,http://mi-dominio.example/auth/realms/prisma
```

`prisma.local` sigue siendo el hostname **recomendado** (es el que trae el certificado por
defecto como CN, y el que usan los ejemplos de esta documentación) — si preferís usarlo de forma
estable en vez de `localhost`, agregalo igual a `/etc/hosts`:

```bash
# Linux/Mac
echo "127.0.0.1 prisma.local" | sudo tee -a /etc/hosts

# Windows (PowerShell como administrador)
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "127.0.0.1 prisma.local"
```

pero a diferencia de antes, **es opcional**: sin ese paso, `https://localhost` (o `http://localhost`)
funciona igual de principio a fin (login incluido) siempre que su `iss` correspondiente esté en
`KEYCLOAK_TRUSTED_ISSUERS`, que es el default de `.env.example`.

## 4. Referencia rápida — variables involucradas

| Variable (`.env`) | Quién la usa | Valor por defecto |
|---|---|---|
| `KEYCLOAK_ISSUER_URL` | backend-core — sólo habilita el `JwtDecoder` de Keycloak (`SecurityConfig.MissingIssuerCondition`); su valor concreto ya no valida nada por sí solo | `https://prisma.local/auth` |
| `KEYCLOAK_TRUSTED_ISSUERS` | backend-core (`prisma.keycloak.trusted-issuers`) — lista de `iss` válidos para un token, uno por cada host+protocolo desde el que se permite loguearse | `https://prisma.local/auth/realms/prisma,http://prisma.local/auth/realms/prisma,https://localhost/auth/realms/prisma,http://localhost/auth/realms/prisma` |
| `KEYCLOAK_AUTH_SERVER_URL` | backend-core, para resolver las JWKS y la Admin REST API — dirección **interna** de Docker, no cruza el navegador (con el prefijo `/auth` agregado en `application.yml`, ya que Keycloak corre con `KC_HTTP_RELATIVE_PATH=/auth`) | `http://keycloak:8080` |
| `VITE_API_BASE_URL` / `VITE_AI_API_BASE_URL` / `VITE_KEYCLOAK_URL` | Frontend (build-time) — rutas relativas, mismo origen que sirvió la SPA | `/api` / `/ai/api/v1` / `/auth` |
| `CORS_ALLOWED_ORIGINS` | backend-core — ya no lo necesita la SPA (mismo origen), pero sigue permitiendo llamadas directas desde herramientas de prueba (Swagger UI en `:8081`, Postman, etc.) | incluye `https://prisma.local` y `https://localhost` |

## Referencias

- Certificado y config de nginx: `infra/nginx/certs/`, `infra/nginx/conf.d/prisma.conf`
- Script de generación: `scripts/generate-nginx-cert.sh`
- Modelo de amenazas / postura de seguridad general: [`SECURITY.md`](SECURITY.md)
- Setup completo por sistema operativo: [`Dev-Config.md`](Dev-Config.md)
