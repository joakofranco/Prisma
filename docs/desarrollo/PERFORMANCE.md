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
| CP-RND-02 | navegacion | Carga normal, 20 usuarios durante 15 minutos, con lecturas y guardado de respuestas |
| CP-RND-03 | cuestionario | Carga del cuestionario completo con 20 usuarios |
| CP-RND-04 | madurez | Cálculo de madurez con 10 usuarios en paralelo |
| CP-RND-05 | reporte-pdf | Generación del PDF con 5 usuarios; valida que sea application/pdf y pese más de 10 KB |
| CP-RND-06 | navegacion | Estrés por escalones hasta el punto de quiebre (disruptivo) |
| CP-TRV-06 | navegacion | p95 por endpoint crítico bajo carga |
| CP-TRV-07 | madurez | Cálculo de madurez con el catálogo completo |
| CP-OBS-08 | borde-dos | Ráfaga por NGINX: 429 y alerta DosRateLimitTriggered o RequestFloodSpike en 5 minutos como máximo (disruptivo) |

### Tiempo en el servidor (CP-RND-04, CP-TRV-07 y CP-RND-05)

El requisito de cálculo de madurez habla del tiempo en el servidor, y la tabla de cada informe mide desde el generador de carga (incluye red y cliente). Si Prometheus es accesible, el resumen agrega el p95 medido en el servidor sobre toda la corrida (`http_server_requests_seconds_bucket` de la ruta `calculate`, o de `reports` en el PDF). Es informativo: el veredicto sigue calculándose con la tabla del generador, hasta que el equipo decida cuál de las dos medidas es la del requisito. Para que haya dato, Prometheus tiene que scrapear backend-core y ser alcanzable desde el generador (`PROMETHEUS_URL`).

### Reporte PDF (CP-RND-05)

- **Validaciones:** cada respuesta debe ser `application/pdf` y pesar más de 10 KB; si no, cuenta como error.
- **PDF de muestra:** la corrida guarda el primer PDF válido en `muestra.pdf`, dentro de la carpeta del informe, y el resumen indica su tamaño, sus páginas y si tiene encabezado y cierre PDF correctos. Un PDF inválido o ausente hace que el caso no cumpla. Conviene abrirlo para confirmar que tiene contenido.
- **Primera generación aparte:** la primera petición de cada usuario a su evaluación se mide como `reporte-primera-generacion` y las repeticiones como `reporte`. Cada usuario virtual usa su propia evaluación aprobada: `make perf-setup` crea 5 (`PERF_EVAL_APROBADAS`). El backend no tiene caché de reportes; la primera generación sirve para ver el costo de arrancar en frío.

### Alertas de saturación (CP-OBS-08)

- **Antes de la ráfaga** se comprueba que lleguen Prometheus, Alertmanager y Mailpit. Si no responden, el caso se detiene sin lanzar la carga y sugiere las URL de la VPN: `PROMETHEUS_URL=http://10.8.0.1:9090 ALERTMANAGER_URL=http://10.8.0.1:9093 MAILPIT_URL=http://10.8.0.1:8025`. En los demás casos solo advierte, porque sin Prometheus el informe no trae métricas del servidor.
- **Para que cumpla:** respuestas 429 como mínimo (`umbral.min_429`), que se dispare `DosRateLimitTriggered` o `RequestFloodSpike` en 5 minutos o menos desde el inicio de la ráfaga (`dos_alerta_max_s`) y que el correo llegue a Mailpit.
- **Evidencia:** el resumen indica en cuántos segundos se disparó la alerta y deja `evidencia-alerta.json` y `evidencia-correo.json` en la carpeta del informe, además de la captura que se saque a mano de Alertmanager y de Mailpit.
- **Orden:** `make perf-all INCLUIR_DISRUPTIVOS=1` lo corre al final, porque fail2ban banea la IP del generador. Para liberarla: `docker exec <contenedor-fail2ban> fail2ban-client unban <IP>`.
- **Excluir casos:** `EXCLUIR=CP-RND-04` en `make perf-all` salta los casos indicados, por ejemplo uno que está esperando una optimización.

### Fases con 1 usuario y recuperación tras el estrés

- **CP-RND-03, CP-RND-04 y CP-RND-05** corren primero con 1 usuario y después con la carga completa (`fases_usuarios=1,20`, `1,10` y `1,5` en su `.properties`). Cada fase deja su propia carpeta (`...-CP-RND-03-1u`, `...-CP-RND-03-20u`), su informe y su fila en el consolidado, y el caso cumple solo si cumplen todas. Con `USERS=n` se corre una única fase con esa cantidad.
- **CP-RND-06** termina, después del último escalón, con una corrida de recuperación de 5 minutos con 1 usuario (`recuperacion_s`, `recuperacion_usuarios`), sin reiniciar nada. Se considera recuperado si su p95 queda dentro de un 20 % (`recuperacion_tolerancia_pct`) del p95 del último CP-RND-01 que haya en `perf/results`; si no hay ninguno, el informe lo indica y la recuperación no se evalúa. Un caso no recuperado figura como no cumple.

### Umbrales de aceptación vigentes (RNF-PERF)

| Medición | Umbral | Requisito |
|---|---|---|
| Lecturas (dashboard, evaluaciones, detalle, resumen, respuestas, resultados, catálogo en carga normal) | p95 ≤ 500 ms | RNF-PERF-01 |
| Guardar respuesta (`guardar-respuesta`) | p95 ≤ 1500 ms | RNF-PERF-02 |
| Cuestionario completo (`catalogo` en CP-RND-03) | p95 ≤ 3000 ms | RNF-PERF-03 |
| Cálculo de madurez (`calcular-madurez`) | p95 ≤ 200 ms | RNF-PERF-04 |
| Reporte PDF (`reporte`) | p95 ≤ 10000 ms | RNF-PERF-06 |
| Errores | ≤ 1 % (0 % en CP-RND-01 y CP-TRV-07) | SLO de monitoreo |

Están en `perf/casos/*.properties`. En el estrés (CP-RND-06) el punto de quiebre es el primer escalón con p95 mayor a 500 ms o más de 1 % de errores.

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

### Definir el p95 aceptable de cada caso antes de ejecutar

`make perf-setup` termina preguntando, caso por caso (CP-RND-01 a 06, CP-TRV-06 y 07), el p95 aceptable en milisegundos. Enter conserva el valor que se muestra entre corchetes (el último guardado o el del caso). Las respuestas quedan en `perf/data/umbrales.properties` y las usan `make perf` y `make perf-all`: el informe marca cada caso como cumple o no cumple contra ese p95. Para cambiarlos más tarde sin recrear los datos:

```
make perf-umbrales
```

Sin terminal interactiva, o con `PERF_SIN_PREGUNTAS=1`, no pregunta y conserva los valores vigentes. Solo se guarda lo que difiere del caso. Un p95 distinto del propio del caso vale para todas sus peticiones, también las que tenían un umbral propio (por ejemplo `calcular-madurez`); si se deja el valor que muestra, esos umbrales propios se mantienen.

### Fijar los umbrales de aceptación (RNF) al ejecutar

Los criterios con los que el informe decide si la prueba pasa se pueden definir en cada ejecución, sin editar los `.properties`:

| Variable | Criterio |
|---|---|
| `UMBRAL_P95_MS`, `UMBRAL_P99_MS`, `UMBRAL_MEDIA_MS` | Tiempos de respuesta máximos aceptados, en milisegundos |
| `UMBRAL_ERROR_PCT` | Porcentaje máximo de peticiones con error |
| `UMBRAL_THROUGHPUT_MIN` | Peticiones por segundo mínimas |
| `UMBRAL_MIN_429` | Solo CP-OBS-08: mínimo de respuestas 429 esperadas |

```
make perf CASO=CP-RND-02 UMBRAL_P95_MS=1500 UMBRAL_ERROR_PCT=0.5
make perf-all UMBRAL_P95_MS=2000 UMBRAL_ERROR_PCT=1
```

- El valor indicado reemplaza al guardado con `make perf-setup` y al del caso y se aplica a todas las peticiones, incluso a las que tenían un umbral propio (`umbral.p95_ms.<petición>`).
- Con `make perf-all` rige para todos los casos de la corrida. En el estrés (CP-RND-06) también define dónde se corta la escalada y qué se considera punto de quiebre.
- Un valor que no es un número se rechaza antes de empezar.
- El informe (`resumen.md` y `informe.pdf`) lista los umbrales usados y, en `umbrales_de_la_corrida`, cuáles se definieron en la ejecución.

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
