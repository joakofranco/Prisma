# Escaneo de vulnerabilidades y tablero de Grafana

PRISMA ya escanea vulnerabilidades en el pipeline de GitHub (Trivy, OWASP Dependency-Check, Gitleaks; ver [`CI-CD-Infra.md`](CI-CD-Infra.md)), pero esos resultados quedan en GitHub. Este documento describe el escaneo que se corre desde la infraestructura propia con `make vuln-scan` y cómo se ven sus resultados en Grafana.

## Cómo funciona

```
make vuln-scan ──► Trivy (contenedor) ──► vuln-reports/*.json ──► metricas.prom
                                                                       │
                              Grafana ◄── Prometheus ◄── Pushgateway ◄─┘
                  «PRISMA · Vulnerabilidades»   alertas (Alertmanager)
```

1. `scripts/vuln-scan.sh` escanea con Trivy las imágenes de la stack que haya en el equipo (a mano, las que lista `docker compose config --images`; en el servicio diario, las de los contenedores en ejecución) y las dependencias del frontend (npm) y de backend-ai (pip). Las dependencias Java de backend-core se cubren al escanear su imagen.
2. `scripts/vuln_metrics.py` convierte los informes en métricas `prisma_vuln_*` y en `vuln-reports/resumen.md`.
3. El script las publica en el Pushgateway (perfil `observability`, puerto `127.0.0.1:9091`) y Prometheus las recoge con el job `pushgateway`.
4. El tablero **PRISMA · Vulnerabilidades** de Grafana muestra el resumen, el detalle por objetivo, la evolución en el tiempo y la tabla de críticas y altas con enlace a NVD.
5. Las reglas de `infra/monitoring/prometheus/rules/vulnerabilidades.yml` avisan por Alertmanager.

## Ejecución automática diaria

El servicio `vuln-scanner` del compose (perfiles `observability` y `full`, se levanta con `make up`) corre el mismo escaneo **todos los días a las 03:00** y publica el resultado en Grafana. Escanea las imágenes de los contenedores que están en ejecución en ese equipo y las dependencias del frontend y de backend-ai. También escanea 1 minuto después de arrancar, para que el tablero tenga datos desde el primer momento.

Se configura en `.env`:

| Variable | Valor por defecto | Para qué |
|---|---|---|
| `VULN_HORA` | `03:00` | Hora del escaneo diario, en la zona horaria `TZ` |
| `VULN_ESCANEAR_AL_INICIAR` | `true` | Escanear también al levantar el servicio |

Los logs del escaneo se ven con `docker logs prisma-vuln-scanner`. Si un escaneo falla, el servicio lo reintenta al día siguiente y la alerta `EscaneoDeVulnerabilidadesFallido` avisa.

Para escanear fuera de horario, `make vuln-scan` lo hace en el momento.

## Uso manual

```
make vuln-scan                                    # imágenes y dependencias
make vuln-scan VULN_SOLO=imagenes                 # solo imágenes
make vuln-scan VULN_IMAGENES=redis:7-alpine       # solo esas imágenes
make vuln-scan VULN_SOLO_CON_PARCHE=1             # cuenta solo lo que ya tiene versión corregida
make vuln-scan VULN_FALLAR=1                      # termina con error si hay críticas con parche
make vuln-scan VULN_SIN_PUSH=1                    # solo el informe local, sin publicar en Grafana
```

`make help` lista todos los parámetros. Los informes quedan en `vuln-reports/` (ignorado por git): un JSON de Trivy por objetivo, `metricas.prom` y `resumen.md`.

La primera ejecución descarga la base de vulnerabilidades de Trivy, que queda en el volumen `prisma-trivy-cache`. Si GitHub Container Registry limita las descargas, se puede indicar un espejo con `TRIVY_DB_REPOSITORY` y `TRIVY_JAVA_DB_REPOSITORY`.

## Métricas

| Métrica | Contenido |
|---|---|
| `prisma_vuln_total{tipo,objetivo,severidad,parche}` | Cantidad por objetivo y severidad (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `UNKNOWN`); `parche` es `si` cuando ya existe versión corregida |
| `prisma_vuln_detalle{...,cve,paquete,instalada,corregida}` | Una serie por cada vulnerabilidad crítica o alta, limitada a las 400 más graves (`VULN_DETALLE_MAXIMO`) |
| `prisma_vuln_scan_success{tipo,objetivo}` | 1 si el objetivo se pudo escanear |
| `prisma_vuln_scan_timestamp_seconds{tipo,objetivo}` | Momento del último escaneo |

## Alertas

| Alerta | Cuándo | Severidad |
|---|---|---|
| `VulnerabilidadCriticaConParche` | Un objetivo tiene alguna crítica con versión corregida | critical |
| `VulnerabilidadesAltasConParche` | Un objetivo tiene altas con versión corregida durante 1 hora | warning |
| `EscaneoDeVulnerabilidadesDesactualizado` | Pasaron más de 8 días desde el último escaneo | warning |
| `EscaneoDeVulnerabilidadesFallido` | No se pudo escanear un objetivo | warning |

## Alcance y límites

- Grafana muestra el resultado del último escaneo, manual o diario; no es un monitoreo continuo entre escaneos. Si el servicio `vuln-scanner` no está levantado, la alerta de desactualización avisa a los 8 días.
- Escanea las imágenes **de los contenedores del equipo donde se ejecuta**; el servicio automático de cada servidor revisa las suyas.
- `vuln-scanner` monta el socket de Docker para leer las imágenes (igual que otros servicios de monitoreo) y el código de `apps/frontend` y `apps/backend-ai` en solo lectura.
- Trivy solo conoce las vulnerabilidades publicadas; no reemplaza una revisión de configuración ni una prueba de intrusión.
- El Pushgateway guarda las métricas en el volumen `prisma-pushgateway-data` y corre como `root` dentro del contenedor para poder escribir en él; solo publica en `127.0.0.1`.
- Docker Swarm (`docker-stack.yml`) no incluye el stack de observabilidad: allí el escaneo funciona con `VULN_SIN_PUSH=1` y deja el informe local.
