# 📗 Runbook operacional

> Incidentes reales encontrados operando PRISMA (no hipotéticos) más los procedimientos de rutina.
> Cuando agregues uno nuevo: síntoma → causa raíz → cómo se resolvió, en ese orden — es lo que
> alguien busca a las 2am, no una explicación teórica.

## Incidentes reales, con causa raíz

### El login funciona pero todas las llamadas a la API dan 401 (dashboard cargando infinito)

**Síntoma:** se completa el login contra Keycloak, se llega a `/dashboard`, pero los contadores
quedan en 0 y la consola del navegador muestra `GET /api/dashboard/stats` reintentando
indefinidamente, siempre con `401`.

**Causa raíz:** el `iss` (issuer) del JWT que emite Keycloak varía según el host/puerto exacto por
el que se entró a la app (hostname dinámico, ver [`Certificados-TLS.md`](Certificados-TLS.md)).
`backend-core` sólo acepta tokens cuyo `iss` esté en `KEYCLOAK_TRUSTED_ISSUERS`. Si accedés por un
origen que no está en esa lista (por ejemplo `http://localhost:8080` directo al contenedor
`frontend`, o cualquier puerto no estándar tras cambiar `FRONTEND_PORT`), el login en sí funciona
(Keycloak no valida nada de eso), pero cada llamada posterior a la API se rechaza con 401 aunque el
token sea perfectamente válido — el interceptor de axios lo reintenta tras refrescar el token, que
sigue teniendo el mismo `iss` inválido, y entra en loop.

**Cómo se resolvió:**
```bash
# Agregar el origen exacto (esquema+host+puerto) a la lista, en .env
KEYCLOAK_TRUSTED_ISSUERS=https://prisma.local/auth/realms/prisma,https://localhost/auth/realms/prisma,http://localhost:8080/auth/realms/prisma

docker compose up -d --force-recreate backend-core
```
Verificación rápida: decodificar el JWT (p.ej. en jwt.io) y comparar el claim `iss` contra la
lista — tienen que matchear carácter por carácter, incluido el puerto.

---

### Un ambiente ya desplegado no adopta un cambio de `.env`/`realm-prisma.json` — hay que empujarlo a mano

**Síntoma:** un fix ya mergeado a `main` (nueva variable en `.env.example`, `redirectUris`/roles
nuevos en `infra/keycloak/realm-prisma.json`, una password rotada en `.env`) se despliega
(`git pull` + rebuild) pero el síntoma que ese fix debía resolver sigue ahí -- login que redirige
mal, 400 al loguearse desde un host nuevo, 401/403 en llamadas que antes andaban, `backend-core`
en crash-loop por auth de base de datos.

**Causa raíz:** varios componentes de este stack sólo leen su config de arranque **una vez**, la
primera vez que se crean sus datos persistentes, y la ignoran en arranques siguientes aunque el
archivo/variable haya cambiado:
- **Keycloak + `--import-realm`**: sólo importa `realm-prisma.json` si el realm `prisma` **no
  existe todavía** en `keycloak-db` -- en un ambiente que ya tiene el realm creado (cualquier
  servidor que no sea la primerísima vez), cambiar ese JSON (`redirectUris`, `webOrigins`, roles
  del service account, el `secret` de un client) **no hace nada** hasta que se reimporta a mano.
- **Postgres/imagen oficial + `POSTGRES_PASSWORD`**: sólo fija esa password al hacer `initdb` en un
  volumen vacío -- rotar `POSTGRES_PASSWORD` en `.env` en un ambiente con el volumen ya creado dej
  a el rol de Postgres con la password VIEJA, mientras el resto de los servicios (que leen la misma
  variable) intentan conectarse con la nueva → `FATAL: password authentication failed`.

Casos reales encontrados en el mismo despliegue (todos el mismo patrón, distinto componente):
password de Postgres desincronizada (`backend-core` en crash-loop), `KEYCLOAK_ADMIN_CLIENT_SECRET`
desincronizado (401 al sincronizar intentos de login fallidos), roles `realm-management` faltantes
en `service-account-prisma-backend` (403 en lo mismo), y `redirectUris` del cliente
`prisma-frontend` sin el host público real (400 al loguearse desde un dominio nuevo).

**Cómo se resuelve** (sin perder datos -- nunca borrar el volumen para "forzar" el reimport en
producción):
```bash
# Postgres: alinear el rol a la password actual de .env
docker exec -u postgres prisma-postgres psql -U "$POSTGRES_USER" -d postgres \
  -c "ALTER USER \"$POSTGRES_USER\" WITH PASSWORD '$POSTGRES_PASSWORD';"

# Keycloak: autenticar kcadm.sh contra master con KEYCLOAK_ADMIN/KEYCLOAK_ADMIN_PASSWORD de .env,
# y desde ahí actualizar lo que haga falta en el realm "prisma":
docker exec prisma-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080/auth --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD"

# ...secret del client "prisma-backend" (debe matchear KEYCLOAK_ADMIN_CLIENT_SECRET de .env):
docker exec prisma-keycloak /opt/keycloak/bin/kcadm.sh update clients/<id> -r prisma -s "secret=$KEYCLOAK_ADMIN_CLIENT_SECRET"

# ...roles del service account (ver la lista completa en realm-prisma.json > users > clientRoles):
docker exec prisma-keycloak /opt/keycloak/bin/kcadm.sh add-roles -r prisma \
  --uusername service-account-prisma-backend --cclientid realm-management \
  --rolename manage-users --rolename view-users --rolename view-realm --rolename manage-realm --rolename view-events

# ...redirectUris/webOrigins del cliente "prisma-frontend" (ya wildcardeados en el repo -- si el
# realm real no lo tiene, es ESTE el comando que lo aplica, no editar el JSON):
docker exec prisma-keycloak /opt/keycloak/bin/kcadm.sh update clients/<id> -r prisma \
  -s 'redirectUris=["*"]' -s 'webOrigins=["*"]'
```
Al agregar un host/dominio nuevo (p.ej. producción o un dominio público que apunte a este
servidor) el checklist completo es: (1) `KEYCLOAK_TRUSTED_ISSUERS` en `.env` -- las dos variantes
`http://`/`https://` del nuevo host (ver el incidente de arriba); (2) confirmar que
`redirectUris`/`webOrigins` del cliente real en Keycloak sean `["*"]` (con el comando de arriba, si
no lo son ya). Nada de esto requiere tocar código ni reiniciar contenedores salvo `backend-core`
(para releer `KEYCLOAK_TRUSTED_ISSUERS`) -- los cambios de Keycloak vía `kcadm.sh` aplican al toque.

---

### El link de "Descargar" de una evidencia no abre nada / da error de DNS

**Síntoma:** `GET /api/evidence/{id}/download` responde 200 con una URL, pero el navegador no
puede resolverla (`ERR_NAME_NOT_RESOLVED` / `No se puede acceder a este sitio web`).

**Causa raíz:** la URL prefirmada de MinIO se estaba generando con el mismo cliente S3 que usa
`backend-core` para hablarle al bucket internamente (`MINIO_ENDPOINT=http://minio:9000`, el nombre
DNS interno de Docker) — ese host no significa nada fuera de la red `prisma-net`.

**Cómo se resolvió:** `EvidenceService` usa ahora dos `MinioClient` distintos —
`minioClient` (interno, para `putObject`/`removeObject`/y para que backend-ai baje el archivo a
indexar) y `minioPublicClient` (`MINIO_PUBLIC_ENDPOINT`, `http://localhost:9001` en dev local) sólo
para firmar la URL que ve el navegador. Ver `MinioConfig`.

**Efecto colateral no obvio al aplicar este fix:** el SDK de MinIO necesita poder *conectarse él
mismo* al endpoint configurado la primera vez que firma una URL, para resolver la región del bucket
(`GetBucketLocation`) si no se la diste de antemano — así que `minioPublicClient`, apuntado a
`localhost:9001`, fallaba con `ConnectException` **dentro del contenedor de backend-core** (ahí
"localhost" es el propio contenedor, no el host). Se resolvió fijando `.region("us-east-1")`
explícitamente en ambos clientes — con la región conocida de antemano, firmar queda 100% offline y
no hace falta conectividad real hacia el endpoint que sólo va a usar el navegador.

---

### `mvn verify` falla con `column ... does not exist` o con un conteo de filas que no cierra

**Síntoma:** los tests de integración (`PrismaApplicationIT`, Testcontainers) fallan con errores de
columnas inexistentes, o con un `assertEquals` de conteo que no matchea, justo después de traer
cambios de otra rama/PR al catálogo.

**Dos causas raíz distintas, mismo síntoma superficial:**

1. **`target/classes` con migraciones Flyway obsoletas.** Un `mvn verify` sin `clean` primero
   reutiliza `target/classes/db/migration/*` de un build anterior — si en el medio cambiaste de
   rama y esa carpeta todavía tiene migraciones `V15`/`V16` (o las que sean) de una rama distinta a
   la que tenés armada ahora, Testcontainers arranca un Postgres nuevo y le aplica ESAS migraciones
   viejas, no las que realmente están en `src/main/resources` en este momento. Se ve como si el
   código no compilara bien. **Fix: `mvn clean verify`, siempre que hayas cambiado de rama.**
2. **El propio volumen de Postgres del `docker-compose` local tiene el esquema de otra rama.** Si
   antes levantaste el stack con una rama que traía una migración que la rama actual no tiene
   (nunca mergeada, o revertida), el volumen persistente (`prisma-postgres-data`) queda con ese
   esquema aplicado — y como Flyway no reversiona hacia atrás, el `backend-core` de la rama actual
   arranca contra un esquema que no reconoce. Se detecta comparando
   `SELECT version, description FROM prisma.flyway_schema_history ORDER BY installed_rank` contra
   los archivos `V*__*.sql` que realmente hay en el checkout actual. **Fix: no hay migración hacia
   atrás — hay que tirar el volumen y levantar de cero:**
   ```bash
   docker compose --profile app --profile security down --remove-orphans   # sin -v todavía
   docker volume rm prisma-postgres-data prisma-keycloak-db-data prisma-minio-data prisma-chroma-data prisma-evidences-data
   docker compose --profile app --profile security up -d
   ```
   Esto borra datos locales (organizaciones/evaluaciones/usuarios de prueba) — nunca hacerlo contra
   un volumen que no sea de desarrollo local.

---

### `docker compose up` falla con `failed to set up container networking: network ... not found`

**Síntoma:** al levantar varios servicios nuevos de golpe (por ejemplo, pasar de `up-core` a `up`
con el perfil `full`), uno o más contenedores fallan con ese mensaje, referenciando un ID de red
que `docker network ls` no muestra (la red real vigente tiene OTRO ID).

**Causa raíz:** Docker Desktop queda con una referencia interna obsoleta a la red `prisma-net`
después de varios ciclos de `up`/`down`/recreación de contenedores en la misma sesión (más
frecuente en Windows con el backend WSL2) — el contenedor nuevo intenta adjuntarse a un ID de red
que ya no existe.

**Cómo se resolvió:** no es un problema de configuración del proyecto, es el estado interno de
Docker. Bajar todo (sin `-v`, preserva los volúmenes/datos) y levantar de nuevo alcanza para que
Compose recree la red con un ID consistente:
```bash
docker compose --profile full down --remove-orphans
docker compose --profile full up -d --remove-orphans
```
Si persiste, reiniciar Docker Desktop antes de reintentar.

---

### Un usuario nuevo (`POST /api/users`) queda creado pero no puede loguearse: `Account is not fully set up`

**Síntoma:** el usuario aparece en `GET /api/users` con `canLogin: true`, pero al intentar loguearse
(o al pedir un token vía `grant_type=password` para probar por API) Keycloak devuelve
`invalid_grant: Account is not fully set up`.

**Causa raíz — dos posibles, en orden de probabilidad:**
1. **Es esperado, no un bug:** todo usuario creado desde la app recibe una contraseña marcada como
   **temporal** en Keycloak (igual que el usuario semilla `admin@prisma.local`) — Keycloak exige
   cambiarla en el primer login real por la UI (la SPA maneja esa pantalla). Si estás probando por
   API directo (ROPC/`grant_type=password`), ese flujo no puede completar el cambio de contraseña
   obligatorio y por eso falla. Para pruebas de API, resetear la contraseña como no-temporal vía la
   Admin REST API de Keycloak:
   ```bash
   # requiere un token de admin del realm master
   curl -X PUT ".../admin/realms/prisma/users/<id>/reset-password" \
     -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
     -d '{"type":"password","value":"...","temporary":false}'
   ```
2. **`KEYCLOAK_ADMIN_CLIENT_SECRET` desincronizado** (ver el punto de "Onboarding" en
   [`SECURITY.md`](SECURITY.md)): en ese caso el usuario ni siquiera se creó de verdad en Keycloak
   (`canLogin` en la respuesta de la API igual puede mostrar `true` porque sólo refleja si hay un
   `keycloak_id` guardado, no si la creación remota tuvo éxito la última vez) — confirmar buscando
   el usuario directo en la consola de Keycloak.

---

### Un usuario borrado (incluido `admin@prisma.local`) queda con el actor de la bitácora en blanco

**Síntoma:** en `audit.audit_logs`, una fila `DELETE user:<id>` aparece con `user_id` vacío, en vez
del id de quien hizo el borrado.

**Causa raíz — no es un bug de la bitácora, es la firma de un self-delete:** `AuditLogService`
resuelve "quién hizo esto" buscando al usuario actual por email **después** de que la operación ya
corrió, dentro de la misma transacción (ver [`Arquitectura.md`](Arquitectura.md)). Si la operación
fue un usuario borrándose **a sí mismo**, esa búsqueda ya no encuentra la fila (se acaba de borrar,
en la misma transacción) y el actor queda en blanco. Este patrón fue exactamente cómo se detectó
en la práctica el bug real de "un usuario podía borrarse a sí mismo o borrar al administrador
global" — hoy corregido (`UserService.delete` lo rechaza con 400 en ambos casos), pero si alguna
vez volvés a ver un `DELETE` con actor en blanco en la bitácora, sospechá primero de un
self-delete, no de un problema de logging.

**Recuperación si ya pasó** (el admin semilla quedó borrado antes del fix):
```sql
INSERT INTO prisma.users (id, email, first_name, last_name, tenant_id, enabled) VALUES
('b0000000-0000-0000-0000-000000000001', 'admin@prisma.local', 'Admin', 'PRISMA', NULL, true)
ON CONFLICT (id) DO NOTHING;
INSERT INTO prisma.user_roles (user_id, role) VALUES
('b0000000-0000-0000-0000-000000000001', 'PRISMA_ADMIN')
ON CONFLICT DO NOTHING;
```
(misma fila que siembra `V4__seed_admin_user.sql` — la cuenta de Keycloak no se ve afectada si
nunca tuvo `keycloak_id`, que es el caso del usuario semilla).

---

### Filebeat está "Up" y sano pero Elasticsearch no tiene ningún log nuevo

**Síntoma:** `docker compose ps` muestra `prisma-filebeat` corriendo sin reinicios, pero
`GET /_cat/indices/filebeat*` en Elasticsearch no devuelve nada (o dejó de crecer después de un
upgrade de la imagen).

**Causa raíz:** el input `container` de Filebeat (`filebeat.autodiscover` con
`config: [{type: container, ...}]`) está deprecado desde Beats 8.x y Filebeat 9.x directamente lo
rechaza: cada runner falla con `"Container input is deprecated. Use Filestream input with its
container parser instead"` **sin tumbar el contenedor ni loguear nada a stdout** — el error queda
en un archivo NDJSON dentro del propio contenedor
(`/usr/share/filebeat/logs/filebeat-*.ndjson`), no visible con `docker compose logs`. El
healthcheck (si lo hay) sigue en verde porque el proceso de Filebeat en sí sigue vivo, sólo que
ningún harvester llega a arrancar.

**Diagnóstico:**
```bash
docker exec prisma-filebeat sh -c "tail -c 3000 /usr/share/filebeat/logs/filebeat-*.ndjson"
```

**Fix:** migrar `infra/monitoring/filebeat/filebeat.yml` de `type: container` a `type: filestream`
con `parsers: [{container: ~}]` (mismo comportamiento, input soportado):
```yaml
config:
  - type: filestream
    id: "prisma-${data.docker.container.id}"
    paths:
      - "/var/lib/docker/containers/${data.docker.container.id}/*.log"
    parsers:
      - container: ~
```

---

### Keycloak nunca termina de arrancar / login da 502, con `ERROR: Failed to run import` en el log

**Síntoma:** `docker compose ps keycloak` muestra el contenedor reiniciando en loop (o "Up" por
pocos segundos antes de volver a caer); nginx devuelve `502 Bad Gateway` al llegar a
`/auth/realms/prisma/...`. El log de `keycloak` repite algo como:
```
ERROR: Failed to start server in (production) mode
ERROR: Failed to run import
ERROR: Unexpected character ('"' (code 34)): was expecting comma to separate Array entries
 at [...] (through reference chain: RealmRepresentation["clients"]->ArrayList[0]->ClientRepresentation["redirectUris"]->ArrayList[4])
```

**Causa raíz:** `infra/keycloak/realm-prisma.json` tiene JSON inválido — en este caso puntual,
faltaba una coma entre dos entradas de un array (`redirectUris`), introducida al editar el archivo
a mano para agregar la IP del servidor a la lista de hosts permitidos (antes de que
`redirectUris`/`webOrigins` pasaran a `"*"`, ver [`SECURITY.md`](SECURITY.md#endurecimientos-recientes-para-que-no-se-repitan)
ítem 10 — ese cambio, además de evitar la edición manual, elimina esta clase entera de error).
`--import-realm` no valida el archivo antes de intentar aplicarlo: si el JSON no parsea, Keycloak
directamente no completa el arranque, en loop, en cada reintento del `restart: unless-stopped`.

**Diagnóstico:**
```bash
docker compose --profile full logs --tail=100 keycloak | grep -i error
```
El mensaje de Jackson (`Unexpected character... reference chain: ...`) apunta a la clave y el
índice del array exactos donde está el problema.

**Cómo se resolvió:** corregir la sintaxis del JSON (o, mejor, no editarlo a mano por host —
usar `redirectUris: ["*"]`/`webOrigins: ["*"]`, que ya cubre cualquier IP/dominio sin tocar el
archivo) y recrear el contenedor:
```bash
docker compose --profile full up -d --force-recreate keycloak
docker compose --profile full logs -f keycloak   # confirmar que ahora sí completa el import
```
Como el import había fallado siempre (nunca llegó a completarse), no queda nada a medio importar
en `keycloak-db` — no hace falta limpiar la base para reintentar.

---

### 502 en `/auth/*` justo después de levantar el stack (o de reiniciar `keycloak`), pero Keycloak arranca bien

**Síntoma:** mismo error que el incidente anterior (`nginx` devuelve `502 Bad Gateway` en
`/auth/realms/prisma/...`), pero acá `docker compose logs keycloak` no muestra ningún `ERROR` —
termina con `Keycloak ... started in Ns. Listening on: http://0.0.0.0:8080` sin reintentos. El 502
desaparece solo si se reintenta la misma URL un rato después.

**Causa raíz:** Keycloak tarda entre 30 y 60 segundos en terminar el arranque completo (augmentation
de Quarkus + import del realm), pero `nginx` no esperaba a que estuviera listo — su `depends_on`
sólo incluía `frontend`/`backend-core`/`backend-ai`, y `keycloak` no tenía `healthcheck` definido.
Como el nombre DNS interno `keycloak` ya existe apenas se crea el contenedor (aunque el proceso
adentro todavía no esté escuchando en el puerto 8080), `nginx` arranca sin error y reenvía tráfico a
un upstream que todavía no acepta conexiones — cualquier request a `/auth/*` en esa ventana da 502.

**Cómo se resolvió:** agregar un `healthcheck` a `keycloak` contra su endpoint de salud
(`/auth/health/ready` en el management interface, puerto 9000 — requiere `KC_HEALTH_ENABLED=true`;
**ojo:** a diferencia de lo que sugiere la [guía oficial](https://www.keycloak.org/observability/health),
el path SÍ hereda el prefijo de `KC_HTTP_RELATIVE_PATH` acá configurado, así que es
`/auth/health/ready` y no `/health/ready` a secas — confirmado a mano contra el contenedor real, no
solo por la doc) y cambiar el `depends_on` de `nginx` para exigir `condition: service_healthy` en
`keycloak` en vez del `service_started` implícito. Con eso, `nginx` no llega a arrancar hasta que
Keycloak realmente está escuchando, sin ventana de 502.

---

### El backend-core no arranca

1. `docker compose logs backend-core --tail 100`
2. Verificar conexión a Postgres: `make health`
3. Si error de migración: revisar `SELECT * FROM prisma.flyway_schema_history ORDER BY
   installed_rank DESC LIMIT 5` — comparar contra los archivos `V*.sql` del checkout actual (ver el
   incidente de arriba sobre volúmenes con esquema de otra rama).

### Ollama consume toda la RAM

1. Bajar el modelo: `OLLAMA_MODEL=llama3:8b-instruct-q4_0` en `.env`
2. Reiniciar: `make restart`

## Backup y recuperación

```bash
make backup    # pg_dump comprimido a backups/postgres_<timestamp>.sql.gz
make restore   # restaura desde el backup MÁS RECIENTE en backups/
```

> **Importante — cobertura real del backup:** pese a lo que dice el comentario del target en el
> `Makefile` ("Backup completo (Postgres + MinIO)"), **`make backup` solo respalda Postgres.** Los
> archivos de evidencia en MinIO (bucket `prisma-evidences`) no tienen ningún backup automatizado
> hoy — sólo viven en el volumen `prisma-evidences-data`. Si se necesita recuperación real ante
> desastre, hay que agregar `mc mirror` (o equivalente) contra el bucket como parte de la rutina de
> backup, o al menos documentar el volumen Docker como la única copia existente.

## Rotación de secretos (JWT/DB)

1. Actualizar el valor en Keycloak y en `.env` — **excepción:** `KEYCLOAK_ADMIN_CLIENT_SECRET` no
   se puede rotar sólo en `.env` (ver [`SECURITY.md`](SECURITY.md) y
   [`Certificados-TLS.md`](Certificados-TLS.md)); hay que cambiarlo también en Keycloak (Admin
   Console → Clients → `prisma-backend` → Credentials → Regenerate) o en tu copia de
   `realm-prisma.json` antes del primer `--import-realm`.
2. `make restart`
3. Todos los usuarios deben re-loguearse.

## SLOs objetivo

- Disponibilidad: 99.5%
- Latencia p95 API: < 500 ms
- Backup Postgres: diario, retención 30 días (recordatorio: no cubre MinIO, ver arriba)

## Referencias

- Setup completo del entorno: [`Dev-Config.md`](Dev-Config.md)
- Modelo de seguridad y endurecimientos recientes: [`SECURITY.md`](SECURITY.md)
- Testing (automatizado + metodología manual que encontró varios de estos incidentes): [`Testing.md`](Testing.md)
