# Pruebas de performance con JMeter

Permite medir tiempos de respuesta, concurrencia, cálculo de madurez, generación de PDF, punto de quiebre y alertas de denegación de servicio, y deja cada corrida como evidencia (reporte HTML, resumen en Markdown y hashes). JMeter corre en un contenedor Docker: no hace falta instalar Java ni JMeter.

## Requisitos

- Docker y `make` (en Windows, Git Bash).
- El stack de PRISMA levantado (Compose o Swarm) con el catálogo cargado (`make seed`).
- Una cuenta administrativa **sin MFA** para crear los datos de prueba (ver más abajo).

## Primer uso

1. Crear `perf/data/admin.env` (no se versiona):
   ```
   PERF_ADMIN_USER=admin@prisma.local
   PERF_ADMIN_PASSWORD=<contraseña de esa cuenta>
   ```
2. `make perf-setup`: construye la imagen de JMeter y crea, de forma idempotente, la organización `PERF-ORG`, 20 evaluadores `perf-NN@prisma.local`, un responsable, una evaluación por usuario y la evaluación `PERF-EVAL-COMPLETA` con todo el catálogo respondido. Deja `perf/data/datos.properties` (con las credenciales generadas, ignorado por git).
3. `make perf CASO=CP-RND-01`.

## Casos disponibles

| Caso | Plan | Qué mide |
|---|---|---|
| CP-RND-01 | navegacion | Línea base con 1 usuario |
| CP-RND-02 | navegacion | Carga normal, 20 usuarios |
| CP-RND-03 | cuestionario | Carga del cuestionario completo con 20 usuarios |
| CP-RND-04 | madurez | Cálculo de madurez con 10 usuarios en paralelo |
| CP-RND-05 | reporte-pdf | Generación del PDF con 10 usuarios |
| CP-RND-06 | navegacion | Estrés por escalones hasta el punto de quiebre (disruptivo) |
| CP-TRV-06 | navegacion | p95 por endpoint crítico bajo carga |
| CP-TRV-07 | madurez | Cálculo de madurez con el catálogo completo |
| CP-OBS-08 | borde-dos | Ráfaga por NGINX: 429 y alerta de DoS (disruptivo) |

## Comandos

```
make perf-build                  # imagen de JMeter
make perf-setup                  # datos PERF-
make perf CASO=CP-RND-02         # un caso
make perf-all                    # casos de medición + informe consolidado
make perf-all INCLUIR_DISRUPTIVOS=1 PERF_PERMITIR_DISRUPTIVO=1
make perf-clean                  # limpia los datos PERF- (BORRAR_RESULTADOS=1 borra también perf/results)
```

## Ajustar qué se mide

Cada caso es un archivo `perf/casos/<CASO>.properties`; los valores comunes están en `_defaults.properties` y cada caso los pisa. Para uno nuevo se copia un archivo y se cambia lo necesario.

| Clave | Significado |
|---|---|
| `plan` | Plan JMeter de `perf/jmeter/` (`navegacion`, `cuestionario`, `madurez`, `reporte-pdf`, `borde-dos`) |
| `modo` | `app`: directo a `backend-core`, sin límites de NGINX. `borde`: por NGINX, con límite de tasa y fail2ban |
| `usuarios`, `rampa_s`, `duracion_s`, `iteraciones`, `pausa_ms` | Carga: usuarios virtuales, subida, duración, repeticiones (-1 = hasta cumplir la duración) y pausa entre peticiones |
| `endpoints` | Peticiones habilitadas del plan, separadas por coma |
| `escalones` | Si existe, corre una prueba por cada cantidad de usuarios y busca el punto de quiebre |
| `reporte_ruta` | Variante del reporte: `pdf`, `technical/pdf`, `executive/pdf`, `excel`, `improvement-plan/pdf` |
| `umbral.p95_ms`, `umbral.p99_ms`, `umbral.media_ms`, `umbral.error_pct`, `umbral.throughput_min` | Criterios de aceptación globales |
| `umbral.p95_ms.<petición>` | Umbral propio de una petición (por ejemplo `umbral.p95_ms.calcular-madurez=5000`) |
| `umbral.min_429`, `dos_alerta` | Solo CP-OBS-08: mínimo de respuestas 429 y alerta de Prometheus a verificar |

Sin editar archivos, se pueden pisar por variables de entorno: `USERS`, `RAMP`, `DURATION`, `ITERACIONES`, `PAUSA_MS`, `ENDPOINTS`, `ESCALONES`, `MODO`. Ejemplo:

```
make perf CASO=CP-RND-02 USERS=50 DURATION=600
```

Para agregar una petición nueva se abre el `.jmx` con la GUI de JMeter, se agrega el *sampler* dentro del bloque `API` con su condición de `endpoints` y se lista en el `.properties`.

## Destino

Por defecto la carga va a `backend-core:8080` desde la red Docker del stack (se detecta `prisma-swarm-net` o `prisma-net`; se fuerza con `PERF_NETWORK`). Con `BASE_URL` se apunta a otra URL, siempre por el borde:

```
make perf CASO=CP-RND-02 BASE_URL=https://prisma.home-uy.site PERF_PERMITIR_REMOTO=1
```

Un destino que no sea local exige `PERF_PERMITIR_REMOTO=1`. Los casos disruptivos (CP-RND-06 y CP-OBS-08) exigen `PERF_PERMITIR_DISRUPTIVO=1`: degradan el servicio y fail2ban puede banear la IP del generador (`docker exec prisma-fail2ban fail2ban-client unban <IP>`). **No correrlos en un servidor compartido sin acordar la ventana con el equipo.**

Para medir en un servidor remoto detrás de Cloudflare, el límite de NGINX (30 peticiones/s por IP) y de Cloudflare distorsionan la medición de la aplicación: conviene correr JMeter dentro de la red del servidor.

## Reporte

Cada corrida crea `perf/results/<fecha>-<caso>/` con:

- **`informe.pdf`: el informe completo de la corrida** (veredicto, tabla por petición, gráficas de tiempo de respuesta, throughput, errores, p95 contra umbral, distribución de tiempos y códigos de respuesta, métricas del servidor y parámetros). Es el archivo para adjuntar como evidencia. Al terminar, `make perf` imprime su ruta.
- `resumen.md`: veredicto (cumple / no cumple), tabla por petición (muestras, media, p90, p95, p99, máximo, error %, peticiones/s), códigos de respuesta y parámetros.
- `html/index.html`: dashboard nativo de JMeter con gráficas.
- `servidor.md`: CPU, heap, conexiones Hikari y p95 del servidor en la ventana de la prueba, si Prometheus responde.
- `resultados.jtl`, `jmeter.log`, `efectivo.properties` (la configuración exacta usada) y `hashes.sha256`.

`make perf-all` agrega `perf/results/<fecha>-informe-consolidado.pdf` (portada con la tabla de todos los casos y, a continuación, el detalle y las gráficas de cada uno) y `<fecha>-informe-consolidado.md` con una fila por caso. `make` termina con error si algún caso no cumple.

CP-RND-06 crea una carpeta `paso-NNNN` por escalón y un `resumen.md` con la tabla usuarios / p95 / error y el punto de quiebre. Su `informe.pdf` incluye las curvas de latencia, error y throughput contra usuarios concurrentes, y el detalle de cada escalón.

## Detalles

- Los usuarios PERF- se autentican con el cliente `prisma-backend` (password grant); el secreto se toma de `KEYCLOAK_ADMIN_CLIENT_SECRET` del `.env` o de `PERF_CLIENT_SECRET`.
- `make perf-clean` solo toca recursos con prefijo `PERF-`. PRISMA no permite borrar evaluaciones ni usuarios con respuestas cargadas: las evaluaciones se conservan y los usuarios se deshabilitan, y `make perf-setup` los reutiliza y rehabilita. Por eso los datos PERF- no se acumulan al repetir el ciclo.
- Las métricas del servidor requieren que Prometheus y Alertmanager sean alcanzables desde la red Docker; si no lo son, el reporte lo indica y el caso se evalúa igual.
- CP-OBS-08 verifica las respuestas 429 y consulta `DosRateLimitTriggered` en Alertmanager. La alerta depende del perfil de observabilidad levantado y de su tiempo de evaluación.
