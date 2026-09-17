# 🎞️ Guion de slides — Demo Kubernetes MVP (PRISMA)

Script de la presentación que acompaña el video de la demo (ver
[`docs/Demo-Kubernetes-MVP.md`](Demo-Kubernetes-MVP.md) para el guion de comandos/terminal, y
[`docs/mcu-5.0/Kubernetes-Analisis-Viabilidad.md`](mcu-5.0/Kubernetes-Analisis-Viabilidad.md)
para el análisis completo del que sale todo el contenido de acá). Pensado para armar un PPT/Google
Slides de ~14 diapositivas: cada sección de abajo es UNA slide, con el contenido sugerido para
la diapositiva (títulos y bullets — llevar a texto corto, no pegar el párrafo completo) y las
notas del orador debajo (eso sí, para decir en cámara, no para poner en la slide).

Duración total estimada: **10-12 minutos** (6 slides de contexto ≈ 4 min + demo en vivo ≈ 8 min
intercalada con 3 slides de transición + cierre ≈ 2 min). Ajustar según el tiempo asignado a la
actividad.

---

## Slide 1 — Portada

**Contenido de la slide:**
- **PRISMA — Kubernetes como plataforma de orquestación**
- Demo del MVP en Kubernetes (minikube)
- Actividad DevOps — Semana 3 · Proyecto de grado PRISMA
- Federico De Armas · Luis Fernando Araujo · Joaquin Franco

**Notas del orador:**
> "Hola, somos el equipo de PRISMA. Esta semana evaluamos si conviene adoptar Kubernetes como
> plataforma de orquestación para nuestro proyecto, y en este video mostramos el resultado de esa
> evaluación funcionando: un MVP real desplegado en un cluster de Kubernetes."

---

## Slide 2 — Agenda

**Contenido de la slide:**
1. Qué es PRISMA (30 seg de contexto)
2. Por qué evaluamos Kubernetes
3. Qué construimos para esta demo (el MVP)
4. Demo en vivo: 3 capacidades de Kubernetes
5. Qué queda fuera, y por qué
6. Conclusión y próximos pasos

**Notas del orador:**
> "El video tiene dos partes: primero contexto y decisiones, después la demo en vivo contra un
> cluster real. No es sólo 'mirá cómo levanta'; son tres comportamientos concretos que Kubernetes
> da y Docker Compose no: auto-recuperación, actualización sin downtime, y escalado horizontal."

---

## Slide 3 — Qué es PRISMA

**Contenido de la slide:**
- Plataforma web de autoevaluación de madurez en ciberseguridad
- Marco de Ciberseguridad de AGESIC v5.0 (MCU 5.0), multi-tenant
- Arquitectura: frontend (Vue), backend-core (Spring Boot), backend-ai (FastAPI + RAG)
- 100% contenerizada — 3 imágenes propias + servicios de plataforma (Postgres, Keycloak, Redis,
  MinIO, Chroma, Ollama)

**Notas del orador:**
> "PRISMA es una herramienta para que organizaciones se autoevalúen contra el marco de
> ciberseguridad de AGESIC. Toda la arquitectura ya está contenerizada desde el arranque del
> proyecto — eso es justamente lo que hace viable evaluar Kubernetes sin partir de cero."

---

## Slide 4 — Por qué evaluamos Kubernetes

**Contenido de la slide:**
- El repo ya tenía manifiestos Kubernetes completos... que **nunca se desplegaron**
- Kustomize `base/` + overlays `dev/staging/prod`, HPA, PDB, Ingress, validados en CI
- La pregunta no era "¿se puede?" — es "¿conviene, con qué alcance, en qué momento?"

**Notas del orador:**
> "Encontramos algo particular al auditar el repo: ya existía una implementación completa de
> Kubernetes, escrita y validada en CI con kubeconform, pero jamás se había corrido contra un
> cluster real. Entonces el trabajo de esta semana no fue escribir manifiestos — fue evaluar si
> conviene operarlos, y probarlo."

---

## Slide 5 — La recomendación, en una slide

**Contenido de la slide:**
| Pregunta | Respuesta |
|---|---|
| ¿Es viable técnicamente? | **Sí**, con holgura — app stateless, contenerizada, manifiestos ya validados |
| ¿Se recomienda producción en K8s ya? | **No** — sin presupuesto de cluster ni responsable de operación |
| ¿Se recomienda un piloto? | **Sí** — bajo riesgo, alto valor para validar y para la defensa |
| Esta demo es... | La Fase 1 del plan: **validar en local que funciona**, antes de un piloto en cloud |

**Notas del orador:**
> "La conclusión completa está en el documento de análisis de viabilidad. En una frase:
> técnicamente estamos listos, operativamente todavía no para producción 24/7 — así que la
> estrategia es por fases. Lo que van a ver ahora es la Fase 1: probar los manifiestos contra un
> cluster real, en local, sin comprometernos a nada más."

---

## Slide 6 — El MVP de esta demo

**Contenido de la slide:**
- Cluster: **minikube** (1 nodo, driver docker) — 4 vCPU / 6 GB
- Overlay nuevo: `infra/kubernetes/overlays/demo/`
- **Adentro:** frontend, backend-core, backend-ai (2 réplicas c/u), Postgres, Keycloak, Redis,
  Chroma
- **Afuera (a propósito):** Ollama/LLM, MinIO, Ingress+TLS — no hacen falta para mostrar cómo
  orquesta Kubernetes
- Herramienta visual: **Kubernetes Dashboard** (viene con minikube) para ver el despliegue
  gráficamente además de la terminal

**Notas del orador:**
> "Armamos un cuarto overlay, sólo para esta demo, que recorta el stack completo a lo mínimo
> necesario para mostrar la tecnología funcionando: nos ahorramos bajar el modelo de LLM de casi
> 5 gigas y levantar MinIO, y accedemos directo al frontend sin meter un Ingress con TLS.
> Volvemos a esto en un par de slides, con el porqué de cada corte."

---

## Slide 7 — Transición a demo en vivo: arranque

**Contenido de la slide:**
- `minikube start`
- `make k8s-demo-images` → build de las 3 imágenes dentro del cluster
- `make k8s-demo-up` → `kubectl apply -k overlays/demo`
- `make k8s-demo-dashboard` → Kubernetes Dashboard (complemento visual)
- *(pasar a la terminal)*

**Notas del orador:**
> "Vamos a la terminal. Primero levanto el cluster, construyo las tres imágenes propias dentro
> del daemon docker de minikube, y aplico los manifiestos con un solo comando declarativo.
> Además de la terminal, vamos a tener abierto el Kubernetes Dashboard — la interfaz gráfica
> oficial del proyecto — para ver gráficamente cada cosa que pase con los pods durante el resto
> de la demo."

*(En este punto se corta a la grabación de terminal — ver
[`docs/Demo-Kubernetes-MVP.md`](Demo-Kubernetes-MVP.md), secciones 1 a 4 — y se vuelve a las
slides para la sección 8.)*

---

## Slide 8 — Demo 1: Auto-recuperación (self-healing)

**Contenido de la slide:**
- Borramos un pod de `backend-core` a mano
- Kubernetes lo reprograma solo
- El Service nunca deja de responder (el otro pod sigue arriba)
- Compose: reinicia el contenedor, pero sin sacarlo del balanceo mientras arranca

**Notas del orador:**
> "La primera capacidad: si un pod se cae — por un bug, un OOM, lo que sea — Kubernetes lo nota
> y levanta uno nuevo solo, sin que nadie intervenga. Vamos a la terminal a probarlo matando un
> pod a mano y viendo que el servicio no se entera — y de paso lo vemos pasar de `Terminating`
> a `Running` en vivo en el Dashboard."

*(Corte a terminal — ver `Demo-Kubernetes-MVP.md`, sección 6.)*

---

## Slide 9 — Demo 2: Rolling update sin downtime

**Contenido de la slide:**
- `kubectl rollout restart deployment/backend-core`
- `maxSurge: 1, maxUnavailable: 0` → primero sube el pod nuevo, recién después baja uno viejo
- Cero downtime durante todo el despliegue
- Compose: `up -d` recrea el contenedor, hay una ventana real de caída

**Notas del orador:**
> "La segunda: desplegar una versión nueva sin cortar a nadie. La configuración ya dice
> 'nunca bajes un pod viejo antes de tener el nuevo arriba y sano' — lo vemos en vivo mientras
> un loop de pedidos sigue respondiendo 200 todo el tiempo, y en el Dashboard el contador de
> réplicas listas nunca baja de 2."

*(Corte a terminal — ver `Demo-Kubernetes-MVP.md`, sección 7.)*

---

## Slide 10 — Demo 3: Escalado horizontal

**Contenido de la slide:**
- Ya existe un `HorizontalPodAutoscaler` (`backend-core-hpa`) apuntando a `backend-core`
- En esta demo: escala manual (`kubectl scale --replicas=4`) — mismo gesto que automatiza el HPA
- En un cluster real: 2→8 réplicas según CPU/memoria, sin intervención humana

**Notas del orador:**
> "La tercera: escalar horizontalmente cuando sube la demanda. El HPA ya está definido en el
> manifiesto y haría esto solo bajo carga real; en el video lo disparamos a mano para que se vea
> claro, pero es exactamente el mismo mecanismo. En el Dashboard se ve la card de backend-core
> subir de 2 a 4 réplicas y volver, en vivo."

*(Corte a terminal — ver `Demo-Kubernetes-MVP.md`, sección 8.)*

---

## Slide 11 — Qué queda fuera de este MVP, y por qué

**Contenido de la slide:**
| Fuera | Por qué |
|---|---|
| Ollama / LLM | ~5 GB de modelo, no aporta a mostrar orquestación; RAG degrada sin romper |
| MinIO | Sin uso en el guion de la demo; sólo afecta la subida de evidencia |
| Ingress + cert-manager + TLS | `kubectl port-forward` al Service alcanza para la demo |
| Alta disponibilidad real de Postgres/Keycloak/Chroma | Fuera de alcance de este MVP — 1 réplica por diseño (ver análisis, C3) |

**Notas del orador:**
> "Somos explícitos con lo que NO estamos mostrando. Nada de esto es una limitación de
> Kubernetes — es una decisión de alcance para esta demo puntual. El documento de análisis de
> viabilidad detalla qué haría falta para cada uno de estos puntos en un cluster de producción
> real."

---

## Slide 12 — Conclusión de la evaluación

**Contenido de la slide:**
- **Técnicamente:** viable, con holgura — validado ahora contra un cluster real
- **Operativamente:** no listos para producción 24/7 hoy (sin rol DevOps, sin presupuesto)
- **Recomendación:** completar y validar los manifiestos (✅ esta demo), piloto *time-boxed* en
  un cluster gestionado antes de la defensa, producción en K8s condicionada a 3 gates (sponsor,
  responsable de operación, runbook)

**Notas del orador:**
> "En resumen: lo que acabamos de mostrar cierra la Fase 1 de nuestro plan — la app funciona en
> Kubernetes de punta a punta. El siguiente paso, si el equipo decide seguir, es un piloto corto
> en un cluster gestionado con créditos educativos, no comprometernos de una a operar producción
> sin quien la sostenga."

---

## Slide 13 — Próximos pasos

**Contenido de la slide:**
- Fase 2: piloto *time-boxed* (1-2 semanas) en GKE Autopilot / AKS, con créditos educativos
- Medir: arranque en frío, comportamiento real del HPA bajo carga, costo/día
- Fase 3 (condicional): producción en K8s — sólo si hay sponsor + responsable + runbook extendido
- Mientras tanto: producción sigue en 1 VPS con `docker-compose.prod.yml`

**Notas del orador:**
> "Nada de esto obliga al paso siguiente — cada fase es reversible. Si en algún momento aparece
> quien financie el cluster y quien lo opere, la promoción a producción está lista: los
> manifiestos ya están escritos, versionados y, ahora, probados."

---

## Slide 14 — Cierre

**Contenido de la slide:**
- Repo: manifiestos en `infra/kubernetes/`
- Documentación: `docs/mcu-5.0/Kubernetes-Analisis-Viabilidad.md` · `docs/Demo-Kubernetes-MVP.md`
- ¡Gracias! Preguntas

**Notas del orador:**
> "Todo lo que mostramos está versionado en el repo, documentado paso a paso para que cualquiera
> del equipo lo pueda repetir. Gracias por ver la demo — quedamos abiertos a preguntas."
