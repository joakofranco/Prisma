**![][image1]**  
**Análisis de viabilidad**  
**Kubernetes como plataforma de orquestación**  
>   
> **Actividad DevOps — Semana 3**  
> Proyecto de grado **PRISMA**  
>   
> **Integrantes**  
>   
> **[Federico De Armas](mailto:alejandro.dearmas@estudiantes.utec.edu.uy)**  
> **[Luis Fernando Araujo](mailto:luis.araujo@estudiantes.utec.edu.uy)**  
> **[Joaquin Franco](mailto:joaquin.franco@estudiantes.utec.edu.uy)**  
>   
>   
>   
>   
Evaluación de la viabilidad técnica, operativa y económica de adoptar Kubernetes como plataforma de orquestación de contenedores para PRISMA, tomando como base el estado real del repositorio (docker-compose.yml, infra/kubernetes/, .github/workflows/).

[**1\. Descripción de la actividad	3**](#1.-descripción-de-la-actividad)

[Descripción de la actividad	3](#descripción-de-la-actividad)

[**2\. Estado actual del proyecto	3**](#2.-estado-actual-del-proyecto)

[2.1 Arquitectura	3](#2.1-arquitectura)

[2.2 Despliegue actual	4](#2.2-despliegue-actual)

[2.3 CI/CD (GitHub Actions)	5](#2.3-ci/cd-\(github-actions\))

[2.4 Artefactos Kubernetes ya versionados (infra/kubernetes/)	5](#2.4-artefactos-kubernetes-ya-versionados-\(infra/kubernetes/\))

[2.5 Deuda técnica conocida que bloquea un deploy K8s real	5](#2.5-deuda-técnica-conocida-que-bloquea-un-deploy-k8s-real)

[**3\. Análisis de pros y contras	6**](#3.-análisis-de-pros-y-contras)

[3.1 Pros en el proyecto PRISMA	6](#3.1-pros-en-el-proyecto-prisma)

[3.2 Contras para el proyecto PRISMA	7](#3.2-contras-para-el-proyecto-prisma)

[**4\. Factibilidad técnica y operativa	8**](#4.-factibilidad-técnica-y-operativa)

[4.1 Técnica (Alta)	8](#4.1-técnica-\(alta\))

[4.2 Operativa (Baja-Media)	9](#4.2-operativa-\(baja-media\))

[4.3 Económica	9](#4.3-económica)

[**5\. Requisitos de infraestructura y recursos	10**](#5.-requisitos-de-infraestructura-y-recursos)

[5.1 Dimensionamiento (suma de requests de los manifiestos actuales)	10](#5.1-dimensionamiento-\(suma-de-requests-de-los-manifiestos-actuales\))

[5.2 Topología recomendada por ambiente	11](#5.2-topología-recomendada-por-ambiente)

[5.3 Componentes de plataforma necesarios en el cluster	11](#5.3-componentes-de-plataforma-necesarios-en-el-cluster)

[5.4 ¿Gestionado o self-managed?	11](#5.4-¿gestionado-o-self-managed?)

[**6\. Estrategia de implementación sugerida (por fases)	12**](#6.-estrategia-de-implementación-sugerida-\(por-fases\))

[**7\. Impacto en el equipo y flujo de trabajo	14**](#7.-impacto-en-el-equipo-y-flujo-de-trabajo)

[7.1 ¿Cambia el CI/CD?	14](#7.1-¿cambia-el-ci/cd?)

[7.2 ¿Qué capacitación se necesita?	14](#7.2-¿qué-capacitación-se-necesita?)

[7.3 Flujo de trabajo diario	15](#7.3-flujo-de-trabajo-diario)

[**8\. Consideraciones de seguridad y monitoreo	15**](#8.-consideraciones-de-seguridad-y-monitoreo)

[8.1 Gestión de secretos	15](#8.1-gestión-de-secretos)

[8.2 RBAC (Control de Acceso Basado en Roles)	15](#8.2-rbac-\(control-de-acceso-basado-en-roles\))

[8.3 Aislamiento de red — NetworkPolicy	16](#8.3-aislamiento-de-red-—-networkpolicy)

[8.4 Endurecimiento de pods	17](#8.4-endurecimiento-de-pods)

[8.5 Monitoreo y observabilidad	17](#8.5-monitoreo-y-observabilidad)

[8.6 Backups y continuidad	18](#8.6-backups-y-continuidad)

[**9\. YAML utilizados	18**](#9.-yaml-utilizados)

[9.1 Ya existentes en el repositorio (infra/kubernetes/)	18](#9.1-ya-existentes-en-el-repositorio-\(infra/kubernetes/\))

[9.2 Manifiestos nuevos recomendados (Fase 1\)	19](#9.2-manifiestos-nuevos-recomendados-\(fase-1\))

[**10\. Conclusión y recomendación final	23**](#10.-conclusión-y-recomendación-final)

[**11\. Justificación de la decisión	25**](#11.-justificación-de-la-decisión)

# 1\. Descripción de la actividad {#1.-descripción-de-la-actividad}

## Descripción de la actividad {#descripción-de-la-actividad}

El equipo evalúa si conviene implementar Kubernetes (K8s) como plataforma de orquestación para PRISMA. El análisis parte de un hecho concreto: el repositorio ya contiene una implementación Kubernetes completa (manifiestos Kustomize base/ \+ overlays/{dev,staging,prod}/, HPA, PDB, Ingress con cert-manager, validación en CI con kubeconform) que nunca se desplegó contra un cluster real. La pregunta, entonces, no es "¿se puede?", sino "¿conviene operar, con qué alcance y en qué momento?".

# 2\. Estado actual del proyecto {#2.-estado-actual-del-proyecto}

## 2.1 Arquitectura {#2.1-arquitectura}

PRISMA es una arquitectura de microservicios enteramente contenerizada. Plataforma web para autoevaluación de madurez en ciberseguridad contra el Marco de Ciberseguridad de AGESIC v5.0 (MCU 5.0), multi-tenant, con workflow de auditoría y planes de mejora.

| Componente | Tecnología | Estado | Persistencia |
| :---- | :---- | :---- | :---- |
| frontend | Vue 3 \+ Vite \+ Nginx | **stateless** | — |
| backend-core | Spring Boot 3.5 / JDK 25 \+ JPA \+ Flyway | **stateless** | Postgres / Redis / MinIO |
| backend-ai | FastAPI \+ LangChain \+ Ollama \+ ChromaDB | **stateless** (desde que el índice se separó a chroma) | vía servicio chroma |
| chroma | ChromaDB standalone | **con estado** | PVC 10 GB (índice vectorial RAG) |
| postgres | PostgreSQL 17 | **con estado** | PVC 50 GB |
| keycloak \+ keycloak-db | Keycloak 26 \+ PostgreSQL 17 | **con estado** | PVC 10 GB |
| minio | MinIO (S3-compatible) | **con estado** | PVC 50 GB (evidencias) |
| ollama | Ollama (Llama 3 8B local) | **con estado** | PVC 30 GB (modelos) |
| redis | Redis 7 | declarado, **sin uso activo** en el código | \- |
| Observabilidad | Prometheus, Grafana, Loki/Promtail, Elasticsearch/Kibana/Filebeat, cAdvisor, exporters | \- | volúmenes locales |
| Proxy | Nginx (TLS terminación) | \- | \- |

**Consecuencia clave para K8s**

Toda la capa de aplicación (frontend, backend-core, backend-ai) es stateless y escalable horizontalmente. El estado real está acotado a 5 servicios (postgres, keycloak-db, minio, chroma, ollama), cada uno hoy de nodo único por diseño.

## 2.2 Despliegue actual {#2.2-despliegue-actual}

Cuatro ambientes, tres mecanismos distintos.

| Ambiente | Mecanismo | Trigger | Estado real |
| :---- | :---- | :---- | :---- |
| **Local** | docker compose \--profile full up | Manual | En uso diario |
| **Dev remoto** | SSH \+ docker compose en un servidor único | push a dev | Configurado, **nunca disparado** |
| **Staging** | kubectl apply \-k overlays/staging | tag v\*.\*.\*-rc\* | Configurado, **nunca desplegado** |
| **Producción** | kubectl apply \-k overlays/prod \+ 2 aprobadores | tag v\*.\*.\* | Configurado, **nunca desplegado** |

## 

## 2.3 CI/CD (GitHub Actions) {#2.3-ci/cd-(github-actions)}

* ci.yml: lint \+ tests \+ build de las 3 apps; valida los manifiestos K8s con kubeconform y YAML con yamllint en cada cambio a infra.  
* build-and-push.yml: publica las 3 imágenes en GHCR.  
* deploy.yml: un único workflow que resuelve el destino (dev → SSH/compose; staging/prod → Kustomize) y usa GitHub Environments para exigir aprobación en prod.  
* security.yml: Gitleaks, Trivy (FS \+ imágenes), OWASP Dependency-Check, CodeQL.

## 2.4 Artefactos Kubernetes ya versionados (infra/kubernetes/) {#2.4-artefactos-kubernetes-ya-versionados-(infra/kubernetes/)}

* Kustomize base/ \+ overlays/{dev,staging,prod}/ (namespaces prisma-dev|staging|prod).  
* backend-core: Deployment 2 réplicas (4 en prod) \+ HPA (2→8, CPU 70 % / mem 80 %) \+ PDB (minAvailable: 1\) \+ RollingUpdate maxUnavailable: 0 \+ securityContext endurecido (runAsNonRoot, readOnlyRootFilesystem, drop: \[ALL\], allowPrivilegeEscalation: false).  
* frontend, backend-ai: Deployment 2 réplicas \+ Service.  
* postgres, keycloak-db, minio, ollama: StatefulSet con volumeClaimTemplates.  
* chroma: Deployment 1 réplica, strategy: Recreate, PVC RWO.  
* ingress.yaml: Ingress NGINX \+ cert-manager.io/cluster-issuer: letsencrypt \+ rutas /api, /ai, /auth, /.  
* Secreto fuera de Kustomize a propósito (secret.example.yaml como plantilla; prisma-secrets se crea a mano una vez por namespace para que kubectl apply \-k . de CI no lo pise).  
* Métricas: anotaciones prometheus.io/scrape ya presentes en backend-core.

## 2.5 Deuda técnica conocida que bloquea un deploy K8s real {#2.5-deuda-técnica-conocida-que-bloquea-un-deploy-k8s-real}

1. MINIO\_PUBLIC\_ENDPOINT ausente en base/configmap.yaml: las URLs firmadas de descarga de evidencia quedarían firmadas con http://minio:9000 (Service interno, irresoluble desde el navegador) → el botón "Descargar" roto en cualquier deploy real. Además el Ingress no expone MinIO por ningún path: hay que decidir cómo se sirve la descarga (path dedicado con rewrite-target o proxy a través de backend-core).  
2. Probe de Keycloak sin verificar contra la 26.7 (/health/ready en el management port 9000): no se pudo levantar el contenedor en el entorno de desarrollo para confirmar el path.  
3. evidences-pvc es ReadWriteMany con storageClassName: standard. La StorageClass standard de la mayoría de proveedores (GKE pd-standard, EKS gp2/gp3) es RWO → el PVC nunca se montaría en 2 pods de backend-core. RWX real requiere EFS / Filestore / NFS / CephFS.  
4. Sin NetworkPolicy: todos los pods se hablan entre sí sin restricción.  
5. Secretos placeholder (cambiar en producción) todavía en .env / realm de Keycloak.  
6. commonLabels de Kustomize está deprecado (usar labels: con includeSelectors).

# 3\. Análisis de pros y contras {#3.-análisis-de-pros-y-contras}

## 3.1 Pros en el proyecto PRISMA {#3.1-pros-en-el-proyecto-prisma}

| \# | Beneficio | Ejemplo concreto |
| :---- | :---- | :---- |
| **P1** | **Escalado horizontal automático.** El HorizontalPodAutoscaler ya definido lleva backend-core de 2 a 8 réplicas según CPU/memoria. | Cierre de trimestre: varias organizaciones suben evidencia y corren el cálculo de madurez el mismo día. Con Compose habría que escalar a mano (y container\_name fijo lo impide — ver docker-compose.prod.yml). |
| **P2** | **Auto-recuperación (self-healing).** liveness/readiness/startupProbe en todos los Deployments; K8s reinicia y re-programa pods caídos. | backend-ai sufre un OOM durante la indexación de un PDF grande: K8s lo reinicia y el segundo pod sigue respondiendo. Compose sólo reinicia el contenedor, sin sacarlo del balanceo mientras arranca. |
| **P3** | **Rolling updates sin downtime.** maxUnavailable: 0 \+ maxSurge: 1 \+ PDB minAvailable: 1\. | Deploy de una corrección en horario laboral sin cortar una auditoría en curso. Con Compose el up \-d recrea el contenedor y hay una ventana de indisponibilidad. |
| **P4** | **Despliegue declarativo y reproducible multi-ambiente.** Kustomize base \+ un overlay por ambiente: staging y prod comparten el 90 % del manifiesto, sólo cambian host, réplicas y recursos. | Levantar un ambiente nuevo (p. ej. demo para un cliente) \= un overlay de \~20 líneas, no rehacer un docker-compose.override. |
| **P5** | **Gestión nativa de config y secretos.** ConfigMap \+ Secret montados como env/volumen; rotación con kubectl edit secret sin .env en el disco del servidor ni redeploy de imagen. | Rotar JWT\_SECRET o la password de Postgres sin dejar la credencial anterior en un archivo del host. |
| **P6** | **Portabilidad / sin lock-in.** Los mismos manifiestos corren en minikube, EKS, GKE, AKS u on-premise. | Si AGESIC exigiera alojamiento en Uruguay (Ley 18.331), se migra de un proveedor a otro sin reescribir el despliegue. |
| **P7** | **Ecosistema de operación maduro.** cert-manager renueva el TLS de Let's Encrypt automáticamente (hoy es el script manual scripts/generate-nginx-cert.sh); Prometheus Operator descubre los targets por las anotaciones que ya están; kubectl rollout undo para rollback en un comando. | El certificado no vence sin que nadie se acuerde de renovarlo. |

## 3.2 Contras para el proyecto PRISMA {#3.2-contras-para-el-proyecto-prisma}

| \# | Costo / riesgo | Ejemplo concreto |
| :---- | :---- | :---- |
| **C1** | **Complejidad operativa y curva de aprendizaje.** kubectl, Kustomize, ingress-nginx, cert-manager, RBAC, PV/PVC, CNI, PodSecurity… para un equipo de 3 personas sin DevOps dedicado. | El repo ya arrastra 6 TODOs de infra sin resolver. Sumar la operación de un cluster multiplica esa superficie. |
| **C2** | **Costo de infraestructura.** Un cluster gestionado con capacidad para ollama (pide 8 Gi, límite 16 Gi) \+ Postgres \+ Keycloak \+ resto ≈ **2–3 nodos de 4 vCPU / 16 GB** → **USD 150–350/mes** \+ Load Balancer \+ almacenamiento persistente \+ egress. Proyecto académico **sin presupuesto asignado**. | Compose sobre 1 VPS de USD 20–40/mes cubre la carga actual (pocas organizaciones, decenas de usuarios). |
| **C3** | **Los componentes con estado no ganan HA "gratis".** postgres, keycloak, minio, chroma, ollama corren a 1 réplica por diseño. K8s **no** los hace redundantes: HA real \= Patroni/CloudNativePG (Postgres), MinIO distribuido (4+ nodos), Keycloak clustering (JGroups \+ KC\_CACHE\_STACK=kubernetes), modo distribuido de Chroma. | Poner replicas: 2 sobre el mismo PVC RWO ni siquiera se programa; si se pudiera, serían dos Postgres corrompiendo el mismo directorio. Documentado en docs/Kubernetes-Minikube-Portainer.md. |
| **C4** | **Recursos locales para desarrollo.** minikube start \--cpus=4 \--memory=8192; con la stack completa (Ollama incluido) no entra cómodo en 16 GB de RAM. | Empeora el loop de desarrollo frente a docker compose \--profile app (que levanta sólo lo necesario en segundos). |
| **C5** | **Overhead desproporcionado a la carga real.** PRISMA hoy no tiene un problema de escala: es una herramienta de autoevaluación con uso por ráfagas. | Un docker-compose.prod.yml sobre un VPS con backups sirve esa carga con años de margen. K8s añade decenas de partes móviles sin beneficio proporcional hoy. |
| **C6** | **Superficie de seguridad nueva.** API server expuesto, RBAC del cluster, secretos en etcd en base64 (sin cifrado en reposo por defecto), NetworkPolicy inexistentes hoy (todo pod habla con todo), imágenes con :latest en varios manifiestos (minio, ollama, chroma-init). | Hay que endurecer el cluster además de la app: kube-bench, PodSecurity restricted, default-deny de red, pinneo de imágenes. |
| **C7** | **Mantenimiento continuo con fecha de caducidad del equipo.** Kubernetes libera \~3 versiones/año con deprecaciones de API; hay que parchear nodos, ingress-nginx, cert-manager. | El equipo se disuelve al terminar el proyecto de grado: quedaría un cluster sin responsable. Un VPS con Watchtower (ya en el stack) es mucho más "dejalo andando". |

# 4\. Factibilidad técnica y operativa {#4.-factibilidad-técnica-y-operativa}

## 4.1 Técnica (Alta) {#4.1-técnica-(alta)}

| Pregunta | Respuesta |
| :---- | :---- |
| ¿La aplicación está contenerizada? | **Sí, totalmente.** 3 Dockerfiles multi-stage (infra/docker/), imágenes publicadas en GHCR por CI. Todo servicio de terceros usa imagen oficial. |
| ¿Monolítica o microservicios? | **Microservicios.** 3 servicios propios \+ servicios de plataforma desacoplados por red. |
| ¿La capa de app es stateless? | **Sí.** frontend, backend-core, backend-ai no guardan estado en el proceso — toda la persistencia está en Postgres/Redis/MinIO/Chroma. El refactor que movió el índice RAG a un servicio chroma aparte ya eliminó el último obstáculo para correr backend-ai con \>1 réplica. |
| ¿Existen los manifiestos? | **Sí**, base \+ 3 overlays, validados en CI con kubeconform en cada cambio. |
| ¿Health checks? | **Sí**, liveness/readiness/startup en todos los Deployments; backend-core expone Spring Actuator (/actuator/health/{liveness,readiness}). |
| Bloqueantes técnicos | Los 6 puntos de la deuda técnica. Ninguno es de fondo: son de configuración (endpoint público de MinIO, StorageClass RWX, probe de Keycloak). Estimado: 1–2 días de trabajo. |

## 

## 4.2 Operativa (Baja-Media) {#4.2-operativa-(baja-media)}

| Factor | Situación |
| :---- | :---- |
| Experiencia del equipo en K8s en producción | Ninguna verificable (los ambientes K8s nunca se desplegaron). |
| Rol de operación / on-call | No existe. |
| Presupuesto para cluster gestionado | No asignado. |
| Continuidad post-entrega | El equipo se disuelve; no hay quién mantenga el cluster. |
| Runbook de incidentes para K8s | docs/RUNBOOK.md existe pero está escrito para el stack Compose. |

**Conclusión de factibilidad**

Técnicamente PRISMA está *listo* para Kubernetes; operativamente el equipo no está en condiciones de operar un cluster de producción de forma sostenida. Esta brecha es la que define la recomendación.

## 4.3 Económica {#4.3-económica}

| Opción | Costo mensual estimado (USD) | Nota |
| :---- | :---- | :---- |
| VPS único \+ docker-compose.prod.yml (actual objetivo "prod") | 20 – 40 | Cubre la carga actual. Sin HA. |
| minikube local (dev / validación) | 0 | Sólo consume RAM del equipo. |
| GKE Autopilot / EKS — piloto *time-boxed* con créditos gratuitos | 0 – 50 | Créditos de free tier / cuenta educativa; apagable fuera de uso. |
| Cluster gestionado permanente (3 nodos, LB, storage, egress) | 150 – 350+ | **Sin fuente de financiamiento hoy.** |

# 

# 5\. Requisitos de infraestructura y recursos {#5.-requisitos-de-infraestructura-y-recursos}

## 5.1 Dimensionamiento (suma de requests de los manifiestos actuales) {#5.1-dimensionamiento-(suma-de-requests-de-los-manifiestos-actuales)}

| Servicio | CPU req | Mem req | Almacenamiento |
| :---- | :---- | :---- | :---- |
| backend-core ×2 (×4 prod) | 1 000 m | 2 Gi | — |
| backend-ai ×2 | 1 000 m | 4 Gi | — |
| frontend ×2 | 100 m | 128 Mi | — |
| chroma ×1 | 250 m | 512 Mi | 10 Gi RWO |
| postgres ×1 | 500 m | 1 Gi | 50 Gi RWO |
| keycloak ×1 | 500 m | 1 Gi | — |
| keycloak-db ×1 | 250 m | 512 Mi | 10 Gi RWO |
| minio ×1 | 250 m | 512 Mi | 50 Gi RWO |
| **ollama ×1** | **2 000 m** | **8 Gi** | 30 Gi RWO |
| evidences-pvc | — | — | **50 Gi RWX** |
| **Total app (sin observabilidad)** | **≈ 6.6 vCPU** | **≈ 18 Gi** | **≈ 200 Gi \+ 50 Gi RWX** |

Con overhead del sistema (kubelet, CNI, ingress, cert-manager, Prometheus) y colchón para picos: mínimo práctico ≈ 8 vCPU / 24 GB RAM utilizables.

## 

## 5.2 Topología recomendada por ambiente {#5.2-topología-recomendada-por-ambiente}

| Ambiente | Nodos | Perfil por nodo | Tipo de cluster |
| :---- | :---- | :---- | :---- |
| **dev / validación** | 1 (minikube, driver docker) | 4 vCPU / 8 GB | Local, make k8s-dev |
| **staging (piloto)** | 2 workers | 4 vCPU / 16 GB | **Gestionado** (GKE Autopilot / EKS) |
| **prod (sólo si se aprueba)** | 3 workers (tolerancia a caída de 1 nodo) | 4 vCPU / 16 GB | **Gestionado** |

## 5.3 Componentes de plataforma necesarios en el cluster {#5.3-componentes-de-plataforma-necesarios-en-el-cluster}

* **Ingress controller**: ingress-nginx (los manifiestos ya asumen kubernetes.io/ingress.class: nginx).  
* **cert-manager** \+ ClusterIssuer letsencrypt (ya referenciado en ingress.yaml).  
* **StorageClass RWX** para evidences-pvc: EFS CSI (AWS) / Filestore CSI (GCP) / NFS. **Bloqueante actual.**  
* **metrics-server** para que el HPA de backend-core funcione.  
* **DNS**.  
* **Registry**: GHCR (ya en uso) \+ imagePullSecret si el repo pasa a privado.

## 5.4 ¿Gestionado o self-managed? {#5.4-¿gestionado-o-self-managed?}

Gestionado, sin discusión, si se avanza. Un cluster self-managed (kubeadm, k3s en varios nodos) añade a este equipo la operación del control plane, etcd, backups de etcd, upgrades coordinados y certificados internos — trabajo de un rol que no existe.

| Proveedor | A favor para PRISMA | En contra |
| :---- | :---- | :---- |
| **GKE Autopilot** (Google) | Facturación por pod, escala a cero, upgrades y seguridad de nodos gestionados, créditos educativos generosos | Menos control fino de nodos; costo por pod puede sorprender con Ollama |
| **EKS** (AWS) | Ecosistema, EFS para el RWX, créditos AWS Educate | Control plane con costo fijo (\~USD 73/mes) aún vacío; más piezas a configurar |
| **AKS** (Azure) | Control plane gratis, créditos de estudiante Azure | Menos experiencia del equipo con Azure |
| **k3s en 1 VPS** | Barato, ligero, válido para el piloto | Nodo único \= sin HA real; poco mejor que Compose para el esfuerzo |

Recomendación: GKE Autopilot o AKS para el piloto (control plane sin costo fijo / facturación granular \+ créditos educativos).

# 6\. Estrategia de implementación sugerida (por fases) {#6.-estrategia-de-implementación-sugerida-(por-fases)}

Principio rector: cada fase entrega valor y es reversible. Nada obliga a la siguiente.

**Fase 0 — Contenerización y manifiestos**

* 3 apps contenerizadas, imágenes en GHCR.  
* Kustomize base \+ overlays dev/staging/prod.  
* Validación en CI (kubeconform, yamllint, hadolint).  
* deploy.yml con lógica de destino y GitHub Environments.


**Fase 1 — Cerrar la deuda y validar en local *(esfuerzo: 2–3 días)***

1. Resolver los bloqueantes de la deuda técnica:  
   - Añadir MINIO\_PUBLIC\_ENDPOINT al ConfigMap **y** decidir la vía de descarga (recomendado: endpoint proxy en backend-core, sin exponer el object store).  
   - Definir evidences como StorageClass RWX real por overlay, o cambiar backend-core a que escriba siempre en MinIO (sin PVC compartido).  
   - Verificar el probe de Keycloak 26.7.  
   - Agregar NetworkPolicy default-deny \+ allows.  
   - Pinnear todas las imágenes :latest.  
2. make k8s-dev contra **minikube**; kubectl apply \-k overlays/dev de punta a punta.  
3. Ensayo de resiliencia: borrar 1 pod de backend-core/backend-ai y confirmar que /actuator/health y /ai/health siguen respondiendo por el Service.  
4. Documentar el resultado en docs/Kubernetes-Minikube-Portainer.md.

**Entregable:** evidencia real de que los manifiestos se despliegan y la app funciona en K8s.

**Fase 2 — Piloto gestionado *time-boxed* (staging) *(1–2 semanas, antes de la defensa)***

1. Crear cluster GKE Autopilot / AKS con créditos educativos.  
2. ingress-nginx \+ cert-manager \+ StorageClass RWX \+ metrics-server.  
3. prisma-secrets a mano en prisma-staging.  
4. Deploy vía tag vX.Y.Z-rc1 → deploy.yml corre kubectl apply \-k overlays/staging.  
5. Medir: tiempo de arranque en frío, comportamiento del HPA bajo carga sintética (p. ej. k6), consumo real, costo/día.  
6. Apagar el cluster al terminar la medición.

**Entregable:** informe con métricas reales más captura del cluster para la defensa.

**Fase 3 — Producción en K8s *(CONDICIONAL — sólo si se cumplen los 3 gates)***

- **Gate 1:** existe un sponsor real (AGESIC, cliente, cátedra) que financia el cluster.  
- **Gate 2:** hay una persona designada y con acceso para operarlo y responder incidentes.  
- **Gate 3:** docs/RUNBOOK.md extendido con procedimientos K8s (rollback, restore de PVC, rotación de secretos, upgrade de cluster).

Si no se cumplen: producción \= 1 VPS con docker-compose.prod.yml (que ya existe y está pensado para eso), con backups make backup a almacenamiento externo. Los manifiestos K8s se conservan versionados y validados para el día que los gates se cumplan.

Mecanismos de rollback por fase

| Situación | Mecanismo |
| :---- | :---- |
| Deploy con bug (app) | kubectl rollout undo deployment/\<x\> \-n prisma-\<env\> (K8s guarda revisionHistoryLimit). |
| Imagen mala | Re-deploy con el tag anterior (GHCR conserva todas las versiones). |
| Migración de BD mala | make restore desde el último make backup (Flyway no revierte solo). |
| Manifiesto mal aplicado | git revert del cambio en infra/kubernetes/ → CI valida → re-apply. |
| Piloto entero sale mal | Se apaga el cluster; nada en local/prod-VPS se tocó. |

# 7\. Impacto en el equipo y flujo de trabajo {#7.-impacto-en-el-equipo-y-flujo-de-trabajo}

## 7.1 ¿Cambia el CI/CD? {#7.1-¿cambia-el-ci/cd?}

**Poco.** La estructura ya está: build-and-push.yml (GHCR) \+ deploy.yml (multi-destino) \+ los overlays. Cambios concretos:

- Cargar el secreto KUBE\_CONFIG en GitHub (kubeconfig con un ServiceAccount de deploy de permisos mínimos — no cluster-admin).  
- Configurar los GitHub Environments staging/prod con reviewers reales.  
- Añadir a ci.yml (opcional): kustomize build | kubeconform \- strict con esquemas CRD (cert-manager, HPA v2) además del kubeconform actual.  
- Post-deploy: los kubectl rollout status ya están en deploy.yml.

## 7.2 ¿Qué capacitación se necesita? {#7.2-¿qué-capacitación-se-necesita?}

| Tema | Audiencia | Horas estimadas |
| :---- | :---- | :---- |
| Conceptos core (Pod, Deployment, Service, Ingress, PVC) | Todo el equipo | 8 |
| kubectl para debugging (logs, describe, exec, port-forward, rollout) | Todo el equipo | 6 |
| Kustomize (base/overlays/patches) | 1 responsable de infra | 6 |
| Operación: HPA, PDB, NetworkPolicy, RBAC, cert-manager | 1 responsable de infra | 12 |
| Incidentes en un cluster gestionado | 1 responsable de infra | 8 |

**Enfoque recomendado**: formación básica para los 3 (para poder diagnosticar), profundidad sólo en 1 responsable. Total ≈ 20 h/equipo \+ 26 h del responsable.

## 

## 7.3 Flujo de trabajo diario {#7.3-flujo-de-trabajo-diario}

- **Desarrollo local: no cambia.** Sigue siendo docker compose \--profile app. Kubernetes local (minikube) es *opt-in*, sólo para probar cambios de infra/kubernetes/.  
- **Branching: no cambia.** Trunk-based con ramas cortas.  
- **Releases:** pasa a importar que un tag v\* dispara un deploy real → hace falta disciplina de versionado semántico y changelog.

# 8\. Consideraciones de seguridad y monitoreo {#8.-consideraciones-de-seguridad-y-monitoreo}

## 8.1 Gestión de secretos {#8.1-gestión-de-secretos}

| Hoy | Recomendado para K8s |
| :---- | :---- |
| .env en el host (Compose); prisma-secrets creado a mano por namespace, fuera de Kustomize | Mantener el patrón "fuera de Kustomize" \+ añadir Sealed Secrets (bitnami) o External Secrets Operator contra un vault, para poder versionar el secreto cifrado en git |
| Sin cifrado en reposo | Habilitar EncryptionConfiguration de etcd (o usar un gestionado que ya lo trae: GKE/EKS/AKS cifran etcd por defecto) |
| Placeholders change\_me\_in\_production | Rotar todo antes del primer deploy fuera de localhost (ya señalado en docs/SECURITY.md) |
| Secreto de client de Keycloak hardcodeado en realm-prisma.json | Rotarlo en la Admin Console tras el primer import y copiarlo a prisma-secrets |

## 8.2 RBAC (Control de Acceso Basado en Roles) {#8.2-rbac-(control-de-acceso-basado-en-roles)}

**Dos planos distintos, no confundir:**

1. **RBAC de la aplicación (ya implementado).** Keycloak \+ roles PRISMA\_ADMIN, RESPONSABLE\_ORG, EVALUADOR\_INTERNO, AUDITOR, VISOR; aislamiento multi-tenant validado en cada operación del backend; auditores acotados a organizaciones asignadas. No cambia con K8s.  
     
2. **RBAC del cluster (a crear).** Principio de mínimo privilegio:  
   

| Sujeto | Permiso | Alcance |
| :---- | :---- | :---- |
| ServiceAccount de deploy.yml | Role con create/update/patch/get sobre deployments,services,configmaps,ingresses,jobs,hpa | Sólo prisma-\<env\> (Role, no ClusterRole) |
| ServiceAccount de cada app (backend-core, etc.) | Ninguno sobre el API server — automountServiceAccountToken: false (ninguna app de PRISMA llama a la API de K8s) | — |
| Equipo — rol "dev" | RoleBinding a edit | prisma-dev |
| Equipo — rol "prod" | RoleBinding a view (+ 1 admin nominal) | prisma-prod |
| Prometheus | ClusterRole de sólo lectura sobre pods,endpoints,nodes/metrics | Cluster |

El agente de Portainer descrito en docs/Kubernetes-Minikube-Portainer.md usa cluster-admin, eso es sólo para la **gestión visual** desde Portainer, no debe ser el sujeto que usa el pipeline de deploy.

## 8.3 Aislamiento de red — NetworkPolicy {#8.3-aislamiento-de-red-—-networkpolicy}

Hoy: **ninguna** (todo pod habla con todo). Mínimo recomendado — default-deny \+ allows explícitos:

frontend → backend-core, backend-ai  
backend-core → postgres, redis, minio, keycloak, backend-ai  
backend-ai → chroma, ollama, backend-core  
keycloak → keycloak-db  
(ingress-nginx) → frontend, backend-core, backend-ai, keycloak  
DNS (kube-system) → todos

## 

## 8.4 Endurecimiento de pods {#8.4-endurecimiento-de-pods}

- backend-core ya trae runAsNonRoot, readOnlyRootFilesystem, drop: \[ALL\], allowPrivilegeEscalation: false → replicar a frontend, backend-ai, chroma.  
- Aplicar Pod Security Admission nivel restricted como label del namespace (pod-security.kubernetes.io/enforce: restricted).  
- Pinnear imágenes (minio, ollama, minio/mc, chromadb/chroma — varias en :latest).  
- ResourceQuota \+ LimitRange por namespace para que un pod sin limits no ahogue el nodo.

## 8.5 Monitoreo y observabilidad {#8.5-monitoreo-y-observabilidad}

| Capa | Hoy (Compose) | En K8s |
| :---- | :---- | :---- |
| Métricas | Prometheus \+ Grafana; anotaciones prometheus.io/scrape en backend-core | kube-prometheus-stack (Prometheus Operator) \+ ServiceMonitor; añade métricas de cluster/nodo/kubelet |
| Logs | Loki \+ Promtail; Elasticsearch/Kibana/Filebeat (perfil elastic) | Promtail como DaemonSet, o el stack Elastic ya definido; mismo LogQL/Kibana |
| Recursos por contenedor | cAdvisor \+ node-exporter | Integrados en kubelet \+ kube-state-metrics |
| Alertas | Reglas Prometheus | Añadir: CrashLoopBackOff, HPA maxReplicas alcanzado, PVC \> 85 %, CertificateExpiringSoon (cert-manager), KubeNodeNotReady |
| Dashboards | Grafana provisionado (infra/monitoring/grafana) | Reusar \+ dashboards estándar de K8s (ID 315, 6417\) |
| Postura del cluster | — | kube-bench (CIS), Polaris / Trivy k8s en CI (Trivy ya está para imágenes) |
| Escaneo de imágenes | Trivy en security.yml | Sin cambios; opcional: admission controller que rechace imágenes con CVE críticos |

## 8.6 Backups y continuidad {#8.6-backups-y-continuidad}

- Postgres: make backup/make restore ya existen → programar como CronJob en el cluster, destino a MinIO/S3 externo.  
- PVCs (chroma, minio, ollama): snapshots de volumen del proveedor, o Velero para backup completo de namespace.  
- El índice chroma y los modelos de ollama son reconstruibles (no requieren backup crítico: el RAG se re-indexa, los modelos se re-descargan).

# 9\. YAML utilizados {#9.-yaml-utilizados}

## 9.1 Ya existentes en el repositorio (infra/kubernetes/) {#9.1-ya-existentes-en-el-repositorio-(infra/kubernetes/)}

| Archivo | Contenido |
| :---- | :---- |
| base/namespace.yaml | Namespace prisma |
| base/configmap.yaml | ConfigMap prisma-config \+ PVC evidences-pvc (RWX 50 Gi) |
| base/secret.example.yaml | Plantilla del Secret prisma-secrets (no se aplica; se crea a mano) |
| base/postgres.yaml | StatefulSet \+ Service headless \+ PVC 50 Gi |
| base/redis.yaml | Deployment \+ Service |
| base/keycloak.yaml | StatefulSet keycloak-db \+ Deployment keycloak \+ Services |
| base/minio.yaml | StatefulSet \+ Service (consola sólo interna) |
| base/minio-init-job.yaml | Job de creación de buckets |
| base/backend-core.yaml | Deployment 2 réplicas \+ Service \+ HPA (2→8) \+ PDB \+ securityContext restringido |
| base/chroma.yaml | Deployment 1 réplica (Recreate) \+ PVC 10 Gi \+ Service |
| base/backend-ai.yaml | Deployment 2 réplicas \+ Service (usa CHROMA\_SERVER\_HOST) |
| base/ollama.yaml | StatefulSet \+ PVC 30 Gi \+ Service |
| base/frontend.yaml | Deployment 2 réplicas \+ Service |
| base/ingress.yaml | Ingress NGINX \+ cert-manager (/api, /ai, /auth, /) |
| base/kustomization.yaml | configMapGenerator del realm de Keycloak \+ resources |
| overlays/dev/ | Namespace prisma-dev, patches.yaml (recursos reducidos), host dev.\*, tags dev |
| overlays/staging/ | Namespace prisma-staging, host staging.\*, tags staging |
| overlays/prod/ | Namespace prisma-prod, 4 réplicas de backend-core, más recursos, tags prod |

Validación sin cluster:

kubectl kustomize infra/kubernetes/overlays/dev     \> /dev/null  
kubectl kustomize infra/kubernetes/overlays/staging \> /dev/null  
kubectl kustomize infra/kubernetes/overlays/prod    \> /dev/null

## 9.2 Manifiestos nuevos recomendados (Fase 1\) {#9.2-manifiestos-nuevos-recomendados-(fase-1)}

A agregar en infra/kubernetes/base/ y referenciar desde kustomization.yaml.

**base/namespace.yaml — endurecer con Pod Security Admission:**

apiVersion: v1  
kind: Namespace  
metadata:  
  name: prisma  
  labels:  
    name: prisma  
    pod-security.kubernetes.io/enforce: restricted  
    pod-security.kubernetes.io/enforce-version: latest  
    pod-security.kubernetes.io/warn: restricted

**base/networkpolicy.yaml — default-deny \+ allows mínimos:**

apiVersion: networking.k8s.io/v1  
kind: NetworkPolicy  
metadata: { name: default-deny-ingress }  
spec:  
  podSelector: {}  
  policyTypes: \[Ingress\]  
\---  
apiVersion: networking.k8s.io/v1  
kind: NetworkPolicy  
metadata: { name: allow-dns-egress }  
spec:  
  podSelector: {}  
  policyTypes: \[Egress\]  
  egress:  
    \- to:  
        \- namespaceSelector:  
            matchLabels: { kubernetes.io/metadata.name: kube-system }  
      ports:  
        \- { protocol: UDP, port: 53 }  
        \- { protocol: TCP, port: 53 }  
\---  
apiVersion: networking.k8s.io/v1  
kind: NetworkPolicy  
metadata: { name: allow-backend-core-ingress }  
spec:  
  podSelector: { matchLabels: { app.kubernetes.io/name: backend-core } }  
  policyTypes: \[Ingress\]  
  ingress:  
    \- from:  
        \- podSelector: { matchLabels: { app.kubernetes.io/name: frontend } }  
        \- podSelector: { matchLabels: { app.kubernetes.io/name: backend-ai } }  
        \- namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: ingress-nginx } }  
      ports:  
        \- { protocol: TCP, port: 8080 }  
\---  
apiVersion: networking.k8s.io/v1  
kind: NetworkPolicy  
metadata: { name: allow-postgres-ingress }  
spec:  
  podSelector: { matchLabels: { app.kubernetes.io/name: postgres } }  
  policyTypes: \[Ingress\]  
  ingress:  
    \- from:  
        \- podSelector: { matchLabels: { app.kubernetes.io/name: backend-core } }  
      ports:  
        \- { protocol: TCP, port: 5432 }  
\# … análogo para redis, minio, keycloak-db, chroma, ollama

**base/resourcequota.yaml — techo por namespace:**

apiVersion: v1  
kind: ResourceQuota  
metadata: { name: prisma-quota }  
spec:  
  hard:  
    requests.cpu: "12"  
    requests.memory: 32Gi  
    limits.cpu: "24"  
    limits.memory: 48Gi  
    persistentvolumeclaims: "12"  
\---  
apiVersion: v1  
kind: LimitRange  
metadata: { name: prisma-defaults }  
spec:  
  limits:  
    \- type: Container  
      default: { cpu: "500m", memory: 512Mi }  
      defaultRequest: { cpu: "100m", memory: 128Mi }

**base/rbac-deploy.yaml — ServiceAccount de CI con permiso mínimo:**  
apiVersion: v1  
apiVersion: v1  
kind: ServiceAccount  
metadata: { name: prisma-deployer }  
\---  
apiVersion: rbac.authorization.k8s.io/v1  
kind: Role  
metadata: { name: prisma-deployer }  
rules:  
  \- apiGroups: \["apps"\]  
    resources: \["deployments", "statefulsets"\]  
    verbs: \["get", "list", "watch", "create", "update", "patch"\]  
  \- apiGroups: \[""\]  
    resources: \["services", "configmaps", "persistentvolumeclaims"\]  
    verbs: \["get", "list", "watch", "create", "update", "patch"\]  
  \- apiGroups: \["networking.k8s.io"\]  
    resources: \["ingresses"\]  
    verbs: \["get", "list", "watch", "create", "update", "patch"\]  
  \- apiGroups: \["batch"\]  
    resources: \["jobs"\]  
    verbs: \["get", "list", "watch", "create", "delete"\]  
  \- apiGroups: \["autoscaling"\]  
    resources: \["horizontalpodautoscalers"\]  
    verbs: \["get", "list", "watch", "create", "update", "patch"\]  
\---  
apiVersion: rbac.authorization.k8s.io/v1  
kind: RoleBinding  
metadata: { name: prisma-deployer }  
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: Role, name: prisma-deployer }  
subjects:  
  \- { kind: ServiceAccount, name: prisma-deployer, namespace: prisma }

**Parche a los Deployments sin token de API (patch de overlay o base):**  
spec:  
spec:  
  template:  
    spec:  
      automountServiceAccountToken: false

**base/postgres-backup-cronjob.yaml — backup programado:**

apiVersion: batch/v1  
kind: CronJob  
metadata: { name: postgres-backup }  
spec:  
  schedule: "0 3 \* \* \*"  
  jobTemplate:  
    spec:  
      template:  
        spec:  
          restartPolicy: OnFailure  
          containers:  
            \- name: dump  
              image: postgres:17-alpine  
              command: \["/bin/sh", "-c"\]  
              args:  
                \- \>  
                  pg\_dump \-h postgres \-U prisma prisma | gzip |  
                  mc pipe local/prisma-backups/postgres\_$(date \+%F\_%H%M).sql.gz  
              envFrom:  
                \- secretRef: { name: prisma-secrets }

# 10\. Conclusión y recomendación final {#10.-conclusión-y-recomendación-final}

Implementar parcialmente o de forma gradual.

| Componente | Decisión |
| :---- | :---- |
| Manifiestos K8s versionados \+ validados en CI | Mantener y completar (Fase 1). Bajo costo, alto valor: portabilidad, disciplina de despliegue declarativo y son parte del entregable académico. |
| Desarrollo local | Seguir con docker compose. minikube sólo *opt-in* para probar infra/kubernetes/. |
| Validación de manifiestos | minikube on-demand (make k8s-dev), no permanente. |
| Piloto en cluster gestionado (staging) | Sí, *time-boxed* (Fase 2), con créditos educativos, apagándolo al terminar. Genera evidencia real para la defensa. |
| Producción permanente en K8s | No por ahora. Sólo si se cumplen los 3 gates de la Fase 3 (sponsor que financie \+ responsable de operación \+ runbook). Mientras tanto, prod \= 1 VPS con docker-compose.prod.yml. |

¿Es viable?

- **Técnicamente: sí, con holgura.** La app está contenerizada, es de microservicios, la capa de aplicación es stateless, los manifiestos existen y pasan kubeconform en CI. La deuda pendiente es de configuración, 2-3 días.  
- **Operativamente: no de forma sostenida hoy.** Equipo de 3 sin DevOps dedicado, sin presupuesto de cluster, que se disuelve al terminar el grado. Operar producción en K8s sin un responsable es el principal riesgo.  
- **Económicamente: sólo el piloto.** Un cluster gestionado permanente (USD 150–350/mes) no tiene financiación; el piloto con créditos gratuitos, sí.

Riesgos y mitigación

| Riesgo | Probabilidad | Impacto | Mitigación |
| :---- | :---- | :---- | :---- |
| Costo del cluster sin financiar | Alta | Alto | Piloto con free tier / créditos; apagar fuera de uso; Autopilot (paga por pod) |
| Curva de aprendizaje frena otras entregas | Media | Medio | Formación básica a los 3, profundidad en 1 responsable; time-box estricto del piloto |
| Estado sin HA (Postgres/Keycloak/MinIO/Chroma) | Media | Alto | Backups probados (make backup → CronJob a S3 externo); asumir RTO/RPO de MVP; HA real fuera de alcance documentada |
| Bloqueantes de deuda técnica subestimados | Media | Medio | Resolverlos y validarlos en minikube antes de tocar un cluster pago (Fase 1 es prerequisito de Fase 2\) |
| Cluster huérfano post-entrega | Alta | Medio | No poner prod en K8s sin responsable designado (Gate 2); manifiestos quedan listos para retomar |
| Lock-in de proveedor | Baja | Bajo | Kustomize genérico; evitar recursos propietarios; el único acoplamiento sería la StorageClass RWX |

¿Se recomienda un piloto?

Sí, explícitamente. Un piloto *time-boxed* en staging sobre un cluster gestionado (1–2 semanas, créditos gratuitos) es la forma de bajo riesgo de: (a) validar los manifiestos contra un cluster real, (b) medir consumo y comportamiento del HPA, (c) producir evidencia concreta para la defensa del proyecto de grado, sin comprometer al equipo a una operación permanente.

# 11\. Justificación de la decisión {#11.-justificación-de-la-decisión}

1. **El trabajo pesado ya está hecho y no tiene sentido descartarlo.** La contenerización completa y los manifiestos Kustomize (base \+ 3 overlays, HPA, PDB, hardening de backend-core, Ingress con cert-manager) representan un esfuerzo ya invertido y validado en CI. Descartar Kubernetes perdería ese activo; adoptarlo a ciegas para producción sumaría una operación que el equipo no puede sostener. La implementación parcial preserva el activo sin asumir el pasivo.  
     
2. **La factibilidad técnica y la operativa apuntan en direcciones opuestas, y la operativa manda en el corto plazo.** No hay rol de DevOps, no hay presupuesto de cluster, y el equipo tiene fecha de disolución. Poner producción en Kubernetes crearía un sistema crítico sin dueño.  
     
3. **La carga real de PRISMA no lo exige.** Es una herramienta de autoevaluación con uso por ráfagas y pocas organizaciones. docker-compose.prod.yml sobre un VPS con backups cubre esa carga con años de margen. El valor de Kubernetes (escala, self-healing, rolling updates) es real pero hoy desproporcionado al problema.  
     
4. **El piloto captura casi todo el beneficio académico con casi ningún riesgo.** El entregable de grado se beneficia de demostrar un despliegue real en Kubernetes; eso se logra con un piloto de dos semanas, sin comprometer una operación 24/7.  
     
5. **La decisión es reversible y escalonada.** Si aparece un sponsor que financie el cluster y designe un responsable, los tres gates de la Fase 3 se cumplen y la promoción a producción en Kubernetes es directa, los manifiestos ya están listos y probados—. Si no aparece, no se perdió nada: prod sigue en el VPS y los manifiestos quedan como IaC versionada.

PRISMA está técnicamente listo para Kubernetes y ya lo tiene modelado, se recomienda completarlo y validarlo, hacer un piloto acotado, y posponer la producción en Kubernetes hasta que exista quién la financia y quién la opere.  


[image1]: <data:image/png;base64,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>