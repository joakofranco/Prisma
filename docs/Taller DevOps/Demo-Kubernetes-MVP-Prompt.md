# 🤖 Prompt — generar el PPT con IA

Prompt listo para pegar en una herramienta de generación de presentaciones con IA (Gamma, Tome,
Canva Magic Design, Copilot/ChatGPT con plugin de slides, Plus AI para Google Slides, etc.) y
obtener el `.pptx`/`.gslides` de una sola vez. El contenido detallado slide por slide (para
editar a mano o copiar/pegar una por una) está en
[`docs/Demo-Kubernetes-MVP-Slides.md`](Demo-Kubernetes-MVP-Slides.md) — este prompt es la
versión condensada, pensada para que la IA genere el diseño visual solo.

---

## Prompt

```
Generá una presentación de 14 diapositivas en español (rioplatense, tono técnico pero
conversacional) para un video de una actividad de DevOps de un proyecto de grado universitario.
Título del proyecto: PRISMA, una plataforma web de autoevaluación de madurez en ciberseguridad
contra el Marco de Ciberseguridad de AGESIC v5.0. Autores: Federico De Armas, Luis Fernando
Araujo, Joaquin Franco.

Tema de la presentación: evaluamos si conviene adoptar Kubernetes como plataforma de
orquestación para PRISMA, y esta presentación acompaña un video donde mostramos un MVP real
desplegado en un cluster de Kubernetes (minikube), demostrando en vivo tres capacidades que
Kubernetes da y Docker Compose no: auto-recuperación de pods, actualizaciones sin downtime, y
escalado horizontal.

Estilo visual: profesional/técnico, minimalista, paleta oscura o azul/gris tipo "cloud native"
(sin usar literalmente los logos de Kubernetes/Docker/AWS por temas de licencia, pero sí
iconografía genérica de contenedores/cluster/nodos). Poco texto por slide (bullets cortos, máx.
4-5 por slide), dejando el desarrollo para las notas del orador. Incluir un diagrama simple de
arquitectura en la slide 6 (frontend/backend-core/backend-ai como cajas "stateless" con 2
réplicas cada una, apuntando a Postgres/Keycloak/Redis/Chroma como cajas "con estado, 1
réplica") y un diagrama de línea de tiempo/fases en la slide 13.

Generá cada slide con: (1) título, (2) contenido/bullets, (3) notas del orador (texto para decir
en cámara, no para mostrar en pantalla). Estructura exacta de las 14 slides:

1. PORTADA — "PRISMA — Kubernetes como plataforma de orquestación" / "Demo del MVP en
   Kubernetes (minikube)" / "Actividad DevOps — Semana 3 · Proyecto de grado PRISMA" / los 3
   nombres de los autores.

2. AGENDA — 6 puntos: qué es PRISMA, por qué evaluamos Kubernetes, qué construimos para esta
   demo, demo en vivo (3 capacidades), qué queda fuera y por qué, conclusión y próximos pasos.

3. QUÉ ES PRISMA — plataforma de autoevaluación de madurez en ciberseguridad (marco AGESIC MCU
   5.0), multi-tenant; arquitectura: frontend Vue, backend-core Spring Boot, backend-ai
   FastAPI+RAG; 100% contenerizada.

4. POR QUÉ EVALUAMOS KUBERNETES — el repo ya tenía manifiestos K8s completos (Kustomize base +
   overlays dev/staging/prod, HPA, PDB, Ingress, validados en CI) que nunca se habían
   desplegado contra un cluster real; la pregunta no era "¿se puede?" sino "¿conviene, con qué
   alcance, en qué momento?".

5. LA RECOMENDACIÓN, EN UNA SLIDE — tabla de 4 filas: ¿Viable técnicamente? Sí, con holgura.
   ¿Producción en K8s ya? No, sin presupuesto ni responsable de operación. ¿Se recomienda un
   piloto? Sí, bajo riesgo. Esta demo es la Fase 1 del plan: validar en local antes de un
   piloto en cloud.

6. EL MVP DE ESTA DEMO — cluster minikube (1 nodo, 8 vCPU/14GB); overlay nuevo
   infra/kubernetes/overlays/demo/; adentro: frontend/backend-core/backend-ai (2 réplicas c/u),
   Postgres, Keycloak, Redis, Chroma; afuera a propósito: Ollama/LLM, MinIO, Ingress+TLS;
   herramienta visual: Kubernetes Dashboard (incluido en minikube). [Acá va el diagrama de
   arquitectura descrito arriba.]

7. TRANSICIÓN A DEMO EN VIVO: ARRANQUE — comandos: minikube start, build de las 3 imágenes
   dentro del cluster, apply de los manifiestos, apertura del Kubernetes Dashboard. (Slide de
   "vamos a la terminal", breve.)

8. DEMO 1: AUTO-RECUPERACIÓN (SELF-HEALING) — borramos un pod de backend-core a mano;
   Kubernetes lo reprograma solo; el Service nunca deja de responder; contraste con Compose
   (reinicia el contenedor pero sin sacarlo del balanceo mientras arranca).

9. DEMO 2: ROLLING UPDATE SIN DOWNTIME — kubectl rollout restart; la política ya configurada
   sube el pod nuevo antes de bajar uno viejo (cero downtime); contraste con Compose (`up -d`
   recrea el contenedor, ventana real de caída).

10. DEMO 3: ESCALADO HORIZONTAL — ya existe un HorizontalPodAutoscaler apuntando a
    backend-core; en la demo se dispara el mismo gesto a mano (kubectl scale); en un cluster
    real escalaría solo de 2 a 8 réplicas según CPU/memoria.

11. QUÉ QUEDA FUERA DE ESTE MVP, Y POR QUÉ — tabla de 4 filas: Ollama/LLM (modelo pesado, no
    aporta a mostrar orquestación), MinIO (sin uso en el guion), Ingress+TLS (port-forward
    alcanza), alta disponibilidad real de las bases de datos (fuera de alcance, 1 réplica por
    diseño).

12. CONCLUSIÓN DE LA EVALUACIÓN — técnicamente viable con holgura (validado ahora contra un
    cluster real); operativamente no listos para producción 24/7 hoy (sin rol DevOps, sin
    presupuesto); recomendación: completar y validar manifiestos (✅ esta demo), piloto
    time-boxed en un cluster gestionado antes de la defensa, producción condicionada a 3 gates
    (sponsor, responsable de operación, runbook).

13. PRÓXIMOS PASOS — Fase 2: piloto time-boxed (1-2 semanas) en GKE Autopilot/AKS con créditos
    educativos, midiendo arranque en frío/comportamiento del HPA/costo; Fase 3 (condicional):
    producción en K8s sólo si hay sponsor+responsable+runbook; mientras tanto producción sigue
    en 1 VPS con docker-compose.prod.yml. [Acá va el diagrama de línea de tiempo de fases
    descrito arriba.]

14. CIERRE — repo con los manifiestos y la documentación completa; agradecimiento; espacio para
    preguntas.

Generá también las notas del orador de cada slide como un texto natural para decir en cámara
(2-4 oraciones por slide), no como una repetición de los bullets.
```

---

## Notas de uso

- **Si la herramienta no soporta notas del orador en el mismo prompt** (algunas sólo generan el
  contenido visible), corré el prompt igual para el diseño y las slides, y después completá las
  notas a mano copiándolas de
  [`docs/Demo-Kubernetes-MVP-Slides.md`](Demo-Kubernetes-MVP-Slides.md) — ahí están ya escritas
  una por una.
- **Si la herramienta pide un tema/plantilla aparte del prompt** (Canva, Google Slides): elegir
  una plantilla oscura o "tech/cloud" simple, sin gráficos de stock de personas ni oficinas —
  la idea es que compita bien al lado de las capturas de terminal del video, no que distraiga.
- El prompt asume que la IA puede generar diagramas simples propios; si la herramienta elegida
  no soporta diagramas (o los genera mal), es más prolijo hacer esas dos slides (6 y 13) a mano
  y dejar que la IA sólo resuelva el resto — son las únicas dos con contenido visual más allá de
  texto/bullets.
- Si preferís revisar/editar el contenido de cada slide **antes** de pasarlo a una herramienta de
  diseño, usá [`docs/Demo-Kubernetes-MVP-Slides.md`](Demo-Kubernetes-MVP-Slides.md) en su lugar
  — tiene el mismo contenido desglosado y con más detalle que el prompt de acá arriba.
