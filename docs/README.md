# Documentación de PRISMA

La documentación está ordenada por área. `CONTRIBUTING.md` y `SECURITY.md` quedan en la raíz de `docs/` porque GitHub los reconoce en esa ubicación.

## Arquitectura — [`arquitectura/`](arquitectura/)

| Documento | Contenido |
|---|---|
| [`Arquitectura.md`](arquitectura/Arquitectura.md) | Componentes, seguridad transversal y trazabilidad. |
| [`Codigo.md`](arquitectura/Codigo.md) | Módulos y clases del monorepo y su responsabilidad. |
| [`API.md`](arquitectura/API.md) | Endpoints, roles y reglas de cada uno. |
| [`mapeo-jpa.md`](arquitectura/mapeo-jpa.md) | Mapeo objeto-relacional de cada entidad. |
| [`diagramas/casos-de-uso.md`](arquitectura/diagramas/casos-de-uso.md) | Actores y casos de uso. |
| [`diagramas/diagrama-clases.md`](arquitectura/diagramas/diagrama-clases.md) | Diagrama de clases del dominio. |
| [`diagramas/modelo-entidad-relacion.md`](arquitectura/diagramas/modelo-entidad-relacion.md) | Modelo entidad-relación. |
| [`diagramas/normalizacion-bd.md`](arquitectura/diagramas/normalizacion-bd.md) | Normalización de la base de datos. |

## Desarrollo — [`desarrollo/`](desarrollo/)

| Documento | Contenido |
|---|---|
| [`Dev-Config.md`](desarrollo/Dev-Config.md) | Puesta en marcha del entorno local por sistema operativo. |
| [`Testing.md`](desarrollo/Testing.md) | Suites automatizadas y QA manual contra el stack real. |
| [`PERFORMANCE.md`](desarrollo/PERFORMANCE.md) | Pruebas de performance con JMeter: casos, parámetros e informes. |
| [`../CONTRIBUTING.md`](CONTRIBUTING.md) | Flujo de ramas, commits, Pull Requests y estándares por tecnología. |

## Operación — [`operacion/`](operacion/)

| Documento | Contenido |
|---|---|
| [`RUNBOOK.md`](operacion/RUNBOOK.md) | Incidentes con causa raíz y procedimientos de rutina (respaldos, alertas, fail2ban, Swarm). |
| [`Ambientes-Despliegue.md`](operacion/Ambientes-Despliegue.md) | Cómo levantar cada ambiente: local, dev remoto, staging y producción. |
| [`CI-CD-Infra.md`](operacion/CI-CD-Infra.md) | Pipelines de GitHub Actions y componentes del despliegue. |
| [`PUBLICACION-CLOUDFLARE.md`](operacion/PUBLICACION-CLOUDFLARE.md) | Publicación en internet con un VPS y Cloudflare Tunnel. |
| [`Certificados-TLS.md`](operacion/Certificados-TLS.md) | HTTPS, certificado de nginx, CA interna y hostname dinámico de Keycloak. |
| [`Notificaciones-Email.md`](operacion/Notificaciones-Email.md) | Correo de cuentas (SMTP de Keycloak). |
| [`Gmail-SMTP.md`](operacion/Gmail-SMTP.md) | Usar una cuenta de Gmail como servidor SMTP. |

## Seguridad

| Documento | Contenido |
|---|---|
| [`SECURITY.md`](SECURITY.md) | Modelo de amenazas, endurecimientos, MFA, cifrado y vulnerabilidades aceptadas. |

## Proyecto — [`proyecto/`](proyecto/)

Documentos académicos y de planificación. Se conservan como registro: describen decisiones y entregas de su momento y pueden no reflejar el estado actual del código.

| Documento | Contenido |
|---|---|
| [`Proyecto.md`](proyecto/Proyecto.md) | Anteproyecto de grado: equipo, alcance, viabilidad y objetivos. |
| [`HistoriasDeUsuario.md`](proyecto/HistoriasDeUsuario.md) | Historias de usuario con criterios de aceptación. |
| [`PLAN-CAMBIOS-EQUIPO.md`](proyecto/PLAN-CAMBIOS-EQUIPO.md) | Plan de cambios del cálculo de madurez y los perfiles. |
| [`Kubernetes-Analisis-Viabilidad.md`](proyecto/Kubernetes-Analisis-Viabilidad.md) | Análisis de una migración a Kubernetes (no implementada). |
| [`Etapa 2 Proyecto/`](proyecto/Etapa%202%20Proyecto/) | Devoluciones de la Etapa 2 (base de datos, ciberseguridad y MVP). |

## Marco de Ciberseguridad — [`mcu-5.0/`](mcu-5.0/)

Fuente del catálogo MCU 5.0 de AGESIC (planillas, perfiles comunitarios y el marco en Markdown). Esta carpeta es además la base de conocimiento que se empaqueta en `backend-ai`: no mover ni agregar archivos sin revisar `make sync-knowledge-base`.

`guia-interpretacion-controles-mcu-5.0.csv`, en esta misma carpeta `docs/`, es la guía de interpretación de controles que se importa desde Configuración → Guía de controles.
