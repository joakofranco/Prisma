PROYECTO DE GRADO
Licenciatura en Tecnologías de la Información

TÍTULO
Stack Tecnlógico
ÁREA
Programación
INTEGRANTES
1. De Armas, Federico
2. Araujo, Luis
3. Franco, Joaquín
FECHA
04/07/2026



Stack Tecnologico
Se propone una arquitectura web compuesta por frontend, backend, base de datos relacional, almacenamiento de evidencias y servicios complementarios. La solución prioriza seguridad, mantenibilidad, escalabilidad progresiva y facilidad de despliegue.
Diagrama del Stack Tecnológico



Frontend:
Vue.js 3 + Vite. Vue.js se propone por su curva de aprendizaje moderada, buena organización por componentes y facilidad para construir interfaces dinámicas con formularios extensos, dashboards, tablas y visualización de indicadores.
Backend principal:
Spring Boot. Se propone por su madurez, robustez y amplio ecosistema para desarrollo de APIs REST, autenticación, autorización, validación de datos, persistencia y seguridad. Spring Boot será responsable de la lógica central del sistema incluyendo gestión de usuarios, organizaciones, evaluaciones, cálculo de madurez y exposición de APIs.
Servicio especializado para recomendaciones o IA
FastAPI. Se propone como servicio especializado para funcionalidades que requieran procesamiento de datos, reglas de recomendación o análisis documental asistido por inteligencia artificial. Python cuenta con un ecosistema amplio para procesamiento de texto, análisis de datos, automatización e IA.
FastAPI puede utilizarse como microservicio complementario al backend principal. En una primera versión, este componente puede quedar limitado a un motor simple de recomendaciones basado en reglas. En versiones posteriores podría evolucionar hacia análisis de documentación, recomendaciones priorizadas, procesamiento de documentos, extracción de texto desde evidencias y análisis asistido por IA mediante la utilización de un LLM como OLLama en un entorno de RAG.
Uso de Inteligencia Artificial para el análisis documental
Como parte de la evolución de PRISMA, se propone incorporar Inteligencia Artificial para facilitar el análisis de documentos y la búsqueda de evidencias. El usuario podrá hacer preguntas en lenguaje cotidiano y el sistema responderá utilizando únicamente los documentos ya cargados en la plataforma, indicando de qué fuentes obtuvo cada dato. Esta técnica se conoce como RAG (Retrieval-Augmented Generation).
Desde el punto de vista técnico, la solución se apoya en dos componentes: Ollama, una herramienta que permite ejecutar los modelos de inteligencia artificial en los propios servidores de la plataforma, en lugar de depender de servicios externos en la nube; y FastAPI, que actúa como intermediario entre PRISMA y Ollama, gestionando de forma segura la comunicación entre ambos. De este modo, la información sensible nunca sale del entorno de PRISMA y los documentos no se utilizan para entrenar la IA: el sistema solo los consulta para responder, respetando los permisos de cada organización y dejando registro de cada consulta.
Este enfoque asiste al usuario en la interpretación de evidencias y la detección de información faltante, reduce el riesgo de respuestas sin fundamento, cada respuesta queda vinculada a sus fuentes  y aporta transparencia, en línea con los objetivos de PRISMA y las buenas prácticas de protección de datos y ciberseguridad.


Base de datos:
PostgreSQL. Se propone como base de datos relacional principal por su costo de licenciamiento y rendimiento general. Permite modelar de forma estructurada entidades como usuarios, organizaciones, roles, evaluaciones, requisitos, rúbricas, niveles de madurez, brechas y planes de mejora.
Base documental o NoSQL
PostgreSQL se utilizará tambien para almacenar información semiestructurada, especialmente respuestas dinámicas de cuestionarios, logs de auditoría, resultados de análisis documental o estructuras que puedan cambiar entre versiones del marco.
Almacenamiento de evidencias:
Para el MVP se utilizará almacenamiento local controlado con referencias desde la base de datos. Para una versión más avanzada se evaluará MinIO o almacenamiento compatible con S3, permitiendo mayor escalabilidad. Se implementarán controles mínimos como asociación de evidencias a requisitos, control de acceso por organización, registro de quién subió el archivo y trazabilidad de cambios.
Seguridad:
La seguridad es un aspecto transversal del proyecto. Se implementarán controles incluyendo autenticación segura, autorización con roles diferenciados (administrador, organización, auditor), JWT para manejo de sesiones, englobado en KeyCloak y FusionAuth, cifrado en tránsito mediante HTTPS, cifrado en reposo para datos sensibles, auditoría de accesos, segregación de datos por organización, validación de entradas y backups periódicos controlado con Grafana el monitoreo de los logs generados por LogRocket.
Infraestructura y despliegue:
Para el MVP se utilizará Docker, Docker Compose, entorno local o servidor de pruebas, repositorio Git y pipeline básico de CI/CD. Para evolución futura se evaluará Kubernetes, Terraform, despliegue en nube, balanceo de carga, almacenamiento externo, monitoreo centralizado y despliegue multiambiente. Esto se lograra utilizando las herramientas Watchtower y portainer
Gestión de Defectos y Herramientas

Se propone Bugasura como herramienta central de gestión de incidencias, desplazando opciones tradicionales por su alineación con entornos ágiles y técnicos y por unificar la planificación de pruebas con el reporte de defectos. Bugasura captura automáticamente logs del sistema, versión del navegador y sistema operativo, lo que reduce el tiempo de triaje y diagnóstico por parte del equipo de desarrollo. Facilita la inclusión nativa de evidencias visuales (anotaciones en capturas y vídeo) para reportar defectos con precisión, y su motor de IA ayuda a identificar reportes duplicados y priorizar incidencias según el impacto real. Al minimizar iteraciones por falta de información, se acelera el ciclo de vida del desarrollo, permitiendo entregas más confiables y rápidas.


####


PROYECTO DE GRADO
Licenciatura en Tecnologías de la Información

TÍTULO
Plataforma de Revisión Integral de Seguridad y Marcos de Auditorías (PRISMA)
ÁREA
Anteproyecto
INTEGRANTES
1. De Armas, Federico
2. Araujo, Luis
3. Franco, Joaquín
TUTORES
Tutor: Facundo Iglesias
Email: facundo.iglesias@utec.edu.uy
FECHA
02/07/2026


ÍNDICE
Historial de Versiones	4
Declaratoria de Autoría	5
Resumen	6
Abstract	7
Presentacion y justificacion de la idea	8
Introducción	8
Necesidad identificada	8
Revisión de antecedentes	10
Justificación	11
Objetivos Generales	12
Objetivos Específicos	12
Análisis de viabilidad	15
Viabilidad humana	15
Viabilidad legal	15
Viabilidad técnica	16
Viabilidad económico-financiera	17
Conclusión del análisis de viabilidad	18
Definiciones de Inicio	18
Ciclo de vida del proyecto	18
Metodología	20
Gestión del Product Backlog	22
Planificaciones de reuniones de equipo	25
Dedicación estimada	27
Roles	27
Análisis de Requerimientos	28
Alcance del Proyecto	28
Descripción general	28
Alcance incluido	28
Entregables principales	29
Fuera del alcance	29
Técnicas de relevamiento empleadas	30
Metodología de priorización	31
Identificación de requerimientos	31
Criterios de priorización	31
Fórmula / Revisión y validación / Matriz	31
Estructura de Desglose de Trabajo	32
Diagrama EDT	32
Diccionario EDT	34
Requerimientos Funcionales	37
Gestión de Riesgos	39
Gestión del Tiempo	41
Gestión de Costos	44
Escenarios de estimación de costos	44
Escenario optimista	44
Escenario base	44
Escenario pesimista	44
Anexos	46
Historias de Usuario	46
Stack Tecnológico	46
Minutas	46
Infraestructura	46
Ciberseguridad	46

Historial de Versiones
Version
Fecha
Descripción
Autores
0.1
25/04/2026
Versión Inicial
Federico De Armas, Fernando Araujo, Joaquin Franco
0.2
09/05/2026
Metodologías
Federico De Armas, Fernando Araujo, Joaquin Franco
1.0
30/05/2026
Reestructuración y Ajustes Alcance
Federico De Armas, Fernando Araujo, Joaquin Franco
2.0
19/06/2026
Gestión de Riesgos, Tiempo y Costos.
Federico De Armas, Fernando Araujo, Joaquin Franco
2.1
02/07/2026
Reformulación de Alcance, Viabilidad, Resumen y Declaratoria de Autoría
Federico De Armas, Fernando Araujo, Joaquin Franco





Declaratoria de Autoría
Los integrantes del equipo ITDice, Fernando Araujo, Federico de Armas y Joaquin Franco, alumnos de la Licenciatura de Tecnologías de la Información de la Universidad Tecnológica, declaramos que este Anteproyecto, entregado como parte de los requisitos académicos, es resultado de nuestro esfuerzo original y conjunto
Confirmamos que:
● Las ideas, diseños, análisis, conclusiones y cualquier otro componente intelectual del presente anteproyecto, salvo donde se haya indicado lo contrario por medio de citas precisas, son producto exclusivo de nuestro trabajo colaborativo.
● Este anteproyecto no ha sido presentado previamente para ningún otro crédito académico ni en esta ni en otra institución.
● Hemos citado de manera correcta y completa todas las fuentes consultadas en la elaboración de este anteproyecto, respetando fielmente los derechos de autor y las normativas académicas vigentes sobre integridad y ética académica.


_________________
_________________
_________________
Fernando Araujo
Federico De Armas
Joaquin Franco



Resumen
En los últimos años, la ciberseguridad ha cobrado una importancia creciente para las organizaciones, debido al aumento de la dependencia tecnológica y a la necesidad de proteger la información, gestionar riesgos y demostrar avances en seguridad. En este contexto, muchas evaluaciones de madurez continúan realizándose mediante planillas, documentos dispersos o procesos manuales, lo que dificulta la trazabilidad, la comparación de resultados y la toma de decisiones.
El objetivo de este proyecto es desarrollar PRISMA, una plataforma web orientada a facilitar la evaluación de madurez en seguridad de la información y ciberseguridad, tomando como referencia el Marco de Ciberseguridad de AGESIC v5.0. Para ello, se propone una solución que permita gestionar organizaciones, usuarios, roles, cuestionarios, evidencias, resultados, reportes y planes de mejora.
El proyecto se desarrollará mediante una metodología híbrida, iterativa e incremental, combinando planificación inicial con prácticas ágiles. En una primera etapa se priorizará un Producto Mínimo Viable, enfocado en la evaluación guiada, el registro de respuestas, el cálculo automático de madurez y la identificación de brechas. Posteriormente, se podrán incorporar funcionalidades vinculadas a auditoría, reportes avanzados, trazabilidad y análisis asistido por inteligencia artificial.
Como resultado esperado, PRISMA permitirá centralizar el proceso de evaluación, reducir tareas manuales, mejorar el respaldo de evidencias y generar información clara para la toma de decisiones. El proyecto constituye una oportunidad para aplicar conocimientos de ingeniería de software, seguridad de la información, gestión de proyectos y testing, aportando una solución tecnológica orientada a la mejora continua de la postura de seguridad organizacional.
Keywords: PRISMA, cybersecurity, maturity assessment, AGESIC, information security, traceability, evidence, improvement plans



Abstract
In recent years, cybersecurity has become increasingly important for organizations, driven by growing technological dependence and the need to protect information, manage risks, and demonstrate progress in security. In this context, many maturity assessments are still carried out using spreadsheets, scattered documents, or manual processes, which hinders traceability, the comparison of results, and decision-making.
The objective of this project is to develop PRISMA, a web platform designed to facilitate the assessment of information security and cybersecurity maturity, using the AGESIC Cybersecurity Framework v5.0 as a reference. To this end, the proposed solution enables the management of organizations, users, roles, questionnaires, evidence, results, reports, and improvement plans.
The project will be developed through a hybrid, iterative, and incremental methodology, combining upfront planning with agile practices. In the first stage, priority will be given to a Minimum Viable Product focused on guided assessment, response recording, automatic maturity calculation, and gap identification. Subsequently, features related to auditing, advanced reporting, traceability, and AI-assisted analysis may be incorporated.
As an expected outcome, PRISMA will make it possible to centralize the assessment process, reduce manual tasks, strengthen evidence support, and generate clear information for decision-making. The project also represents an opportunity to apply knowledge of software engineering, information security, project management, and testing, delivering a technological solution aimed at the continuous improvement of the organizational security posture.
Keywords: PRISMA, cybersecurity, maturity assessment, AGESIC, information security, traceability, evidence, improvement plans.

Presentacion y justificacion de la idea
Introducción
El presente anteproyecto introduce PRISMA (Plataforma de Revisión Integral de Seguridad y Marcos de Auditorías), una solución tecnológica diseñada para facilitar la evaluación integral de la madurez en seguridad de la información y ciberseguridad en organizaciones. Esta plataforma se propone como herramienta de apoyo para la interpretación, implementación y monitoreo de marcos normativos y estándares de seguridad.
PRISMA integra la carga y evaluación de cuestionarios de autoevaluación, la revisión de evidencias, el cálculo automático de niveles de madurez conforme a marcos establecidos, y la generación de reportes ejecutivos con recomendaciones priorizadas para la mejora continua. El proyecto combina metodologías ágiles de desarrollo de software con prácticas de gestión de riesgos, asegurando que la solución responda a los requisitos funcionales, normativos y técnicos del dominio de la ciberseguridad.
Necesidad identificada 
En la actualidad, las organizaciones dependen cada vez más de los sistemas de información para desarrollar sus actividades, gestionar datos, prestar servicios y tomar decisiones. Esta dependencia tecnológica ha incrementado la exposición a riesgos vinculados con incidentes de seguridad, accesos no autorizados, pérdida de información, interrupciones operativas y debilidades en los controles internos.
En este contexto, la ciberseguridad dejó de ser un aspecto exclusivamente técnico para convertirse en un componente estratégico de la gestión organizacional. Las instituciones no solo necesitan implementar herramientas de protección, sino también contar con mecanismos que les permitan conocer su situación actual, evaluar su nivel de madurez, identificar brechas, registrar evidencias y planificar acciones de mejora de forma ordenada y continua.
Sin embargo, la evaluación de la madurez en seguridad de la información suele presentar dificultades prácticas. Muchas organizaciones realizan estos procesos mediante planillas de cálculo, documentos dispersos, registros manuales o criterios de análisis poco homogéneos. Esto dificulta la trazabilidad de la información, genera diferencias de interpretación entre evaluadores y limita la posibilidad de obtener resultados claros, comparables y útiles para la toma de decisiones.
A su vez, la aplicación de marcos de referencia en ciberseguridad, como el Marco de Ciberseguridad de AGESIC v5.0, requiere interpretar requisitos, asociarlos con controles, registrar evidencias y demostrar avances en su cumplimiento. Cuando este proceso se gestiona de forma manual, resulta complejo mantener un seguimiento histórico, validar la información presentada y transformar los resultados obtenidos en planes de mejora concretos.
Entre los principales puntos críticos detectados se encuentran:
● La evaluación de madurez suele realizarse mediante planillas, documentos o registros dispersos, lo que dificulta la centralización de la información.
● La carga y revisión de evidencias no siempre se encuentra vinculada directamente a los controles evaluados, afectando la trazabilidad del proceso.
● Los criterios de evaluación pueden variar entre usuarios o evaluadores, generando resultados poco homogéneos o difíciles de comparar.
● No siempre existe un mecanismo automático para calcular el nivel de madurez global, por función, categoría o subcategoría.
● La identificación de brechas y controles no cumplidos suele requerir análisis manual, lo que aumenta el tiempo de revisión y la posibilidad de errores.
● La generación de reportes ejecutivos y técnicos puede depender de tareas manuales, dificultando la presentación clara de resultados a responsables, auditores o autoridades.
● Las organizaciones necesitan contar con planes de mejora que permitan asignar responsables, prioridades, estados y fechas objetivo para dar seguimiento a las acciones pendientes.
● La gestión de usuarios, roles y permisos resulta fundamental, ya que una misma evaluación puede involucrar administradores, responsables de organización, evaluadores internos, auditores y perfiles de consulta.
● La ausencia de una bitácora o historial de cambios limita la capacidad de auditar quién realizó una acción, cuándo la realizó y sobre qué elemento del proceso impactó.
Frente a esta situación, surge la necesidad de desarrollar una solución tecnológica que permita centralizar y ordenar el proceso de evaluación de madurez en ciberseguridad. PRISMA se plantea como una plataforma web orientada a facilitar la revisión integral de seguridad, permitiendo gestionar organizaciones, usuarios, evaluaciones, evidencias, resultados, reportes y planes de mejora dentro de un entorno más seguro, trazable y estructurado.
De esta manera, el proyecto busca contribuir a que las organizaciones puedan evaluar su postura de seguridad de forma más clara, reducir la dependencia de procesos manuales, mejorar la trazabilidad de la información y disponer de insumos confiables para la toma de decisiones y la mejora continua.
Revisión de antecedentes
Durante el relevamiento inicial realizado para la elaboración del anteproyecto, se identificó que las organizaciones suelen evaluar la seguridad de la información mediante herramientas dispersas, como planillas de cálculo, cuestionarios, documentos de control, auditorías internas y revisiones manuales. Si bien estos mecanismos pueden ser útiles, presentan limitaciones cuando se requiere centralizar la información, mantener trazabilidad, asociar evidencias y calcular niveles de madurez de forma ordenada.
Asimismo, existen marcos de referencia y buenas prácticas en ciberseguridad que orientan la evaluación de controles y niveles de cumplimiento. Sin embargo, bajo los parámetros establecidos para este proyecto y en relación con el marco analizado, no se identificaron antecedentes directos que integren en una misma solución la evaluación guiada, el registro de evidencias, el cálculo automático de madurez, la generación de reportes y el seguimiento de planes de mejora.
Por este motivo, PRISMA se plantea como una propuesta orientada a cubrir esa necesidad, transformando el proceso de evaluación de madurez en ciberseguridad en un flujo digital, estructurado y trazable.
Justificación
El desarrollo de PRISMA se justifica por la necesidad creciente que tienen las organizaciones de evaluar, ordenar y mejorar su nivel de madurez en seguridad de la información y ciberseguridad. En un contexto donde la dependencia tecnológica aumenta de forma constante, las instituciones requieren mecanismos que les permitan conocer su situación actual, identificar brechas, respaldar sus decisiones con evidencias y planificar acciones de mejora de manera estructurada.
Actualmente, muchas evaluaciones de seguridad se realizan mediante planillas de cálculo, documentos dispersos, registros manuales o criterios de análisis poco homogéneos. Esta forma de trabajo puede dificultar la trazabilidad entre requisitos, respuestas, evidencias y resultados, además de generar diferencias de interpretación entre evaluadores. Como consecuencia, las organizaciones pueden tener dificultades para conocer su nivel real de madurez, priorizar acciones correctivas y demostrar avances de forma clara y verificable.
PRISMA busca aportar valor mediante una plataforma web que permita centralizar y ordenar el proceso de evaluación. La solución permitirá gestionar organizaciones, realizar evaluaciones guiadas, registrar evidencias, calcular niveles de madurez, identificar brechas y generar planes de mejora. De esta manera, el sistema no se limita a obtener un diagnóstico puntual, sino que promueve una gestión continua, trazable y orientada a la mejora de la postura de seguridad organizacional.
Desde el punto de vista técnico, el proyecto resulta pertinente porque integra diversas áreas propias de la Licenciatura en Tecnologías de la Información, tales como ingeniería de software, análisis de requerimientos, arquitectura de sistemas, desarrollo de APIs, diseño de bases de datos, seguridad de la información, testing, gestión de defectos y metodologías ágiles. Esto convierte a PRISMA en un caso de aplicación realista, donde se combinan conocimientos técnicos, normativos y organizacionales para resolver una problemática actual.
Asimismo, el proyecto presenta un alcance viable al ser abordado de forma iterativa e incremental. En una primera etapa se priorizará el desarrollo de un Producto Mínimo Viable orientado a las funcionalidades esenciales: gestión de usuarios y organizaciones, evaluación guiada, carga básica de evidencias, cálculo de madurez, identificación de brechas y generación inicial de planes de mejora. Posteriormente, podrán incorporarse funcionalidades más avanzadas, como revisión por auditor, reportes especializados, recomendaciones priorizadas, análisis documental asistido por inteligencia artificial o búsqueda semántica de evidencias.
Por lo tanto, PRISMA se justifica tanto por su valor práctico como por su valor académico. Desde el punto de vista práctico, responde a una necesidad real vinculada a la gestión de la ciberseguridad y a la toma de decisiones basada en información ordenada y verificable. Desde el punto de vista académico, permite aplicar y articular competencias fundamentales de la carrera en el desarrollo de una solución tecnológica.
Objetivos Generales
Desarrollar e implementar una plataforma web denominada PRISMA, orientada a facilitar la evaluación de madurez en seguridad de la información y ciberseguridad de las organizaciones, tomando como referencia el Marco de Ciberseguridad de AGESIC v5.0. La solución permitirá gestionar evaluaciones de forma guiada, registrar evidencias, calcular niveles de madurez, visualizar resultados y generar planes de mejora, contribuyendo a una gestión más ordenada, segura, trazable y continua de la postura de seguridad organizacional. 
Objetivos Específicos
El equipo se plantea e identifica los siguientes objetivos específicos para el proyecto PRISMA:
● Diseñar un modelo de evaluación guiada que permita a las organizaciones analizar su nivel de madurez en seguridad de la información y ciberseguridad, tomando como referencia el Marco de Ciberseguridad de AGESIC v5.0.
● Implementar un sistema de cuestionarios estructurados que permita evaluar controles, registrar respuestas y asociarlas a funciones, categorías y subcategorías del marco utilizado.
● Desarrollar un módulo de gestión de organizaciones, usuarios y roles, permitiendo diferenciar permisos y responsabilidades entre administradores, responsables de organización, evaluadores internos, auditores y usuarios de consulta.
● Incorporar un mecanismo de cálculo automático de madurez que permita obtener resultados globales y detallados por función, categoría o subcategoría, facilitando la identificación de fortalezas y debilidades.
● Permitir el registro, carga y asociación de evidencias documentales a los controles evaluados, favoreciendo la trazabilidad y respaldo del proceso de evaluación.
● Implementar funcionalidades de seguimiento del estado de las evaluaciones, permitiendo distinguir instancias como borrador, en curso, lista para auditoría, aprobada, devuelta o archivada.
● Desarrollar un módulo de auditoría que permita revisar evaluaciones, validar evidencias, registrar observaciones y mantener un historial del proceso de revisión.
● Generar reportes ejecutivos y técnicos que presenten de forma clara los resultados obtenidos, el nivel de madurez alcanzado, las brechas detectadas y las recomendaciones de mejora.
● Incorporar planes de mejora a partir de los resultados de la evaluación, permitiendo definir acciones, responsables, prioridades, estados y fechas objetivo para su seguimiento.
● Diseñar tableros de visualización que permitan interpretar los resultados de madurez mediante indicadores, gráficos y vistas comparativas que apoyen la toma de decisiones.
● Garantizar la trazabilidad de las acciones realizadas dentro de la plataforma, registrando cambios relevantes, usuarios intervinientes, fechas, estados y operaciones efectuadas.
● Diseñar la solución considerando criterios de seguridad, privacidad, control de acceso, disponibilidad y escalabilidad, debido a la sensibilidad de la información gestionada por la plataforma.
● Documentar el proceso de análisis, diseño, desarrollo e implementación de PRISMA, de forma que pueda servir como base para futuras mejoras, ampliaciones o adaptaciones del sistema.
Estos objetivos específicos apoyan directamente el objetivo general del proyecto, orientado a facilitar la evaluación de madurez en ciberseguridad, mejorar la trazabilidad del proceso y brindar información útil para la toma de decisiones y la mejora continua de la postura de seguridad organizacional



Análisis de viabilidad
El análisis de viabilidad del proyecto PRISMA permite determinar si la solución puede ser desarrollada de forma realista, considerando los recursos humanos disponibles, los aspectos legales, la capacidad técnica del equipo y los costos asociados al desarrollo.
PRISMA se plantea como una plataforma web orientada a facilitar la evaluación de madurez en ciberseguridad, tomando como referencia el Marco de Ciberseguridad de AGESIC v5.0. El proyecto busca centralizar evaluaciones, evidencias, resultados, reportes y planes de mejora, reduciendo el uso de planillas, documentos dispersos y procesos manuales.
Viabilidad humana
Desde el punto de vista humano, el proyecto se considera viable porque el equipo cuenta con integrantes con roles definidos y complementarios. Federico De Armas participa como Analista Funcional y QA Tester, Fernando Araujo como Arquitecto de Software y Desarrollador Full Stack, y Joaquín Franco como PM y QA Tester. Esta distribución permite cubrir tareas de análisis, planificación, arquitectura, desarrollo, pruebas y control de calidad.
Además, el equipo definió una modalidad de trabajo organizada, con reuniones de seguimiento, planificación de sprint, refinamiento de backlog, revisión técnica, Sprint Review, retrospectiva y coordinación general. También se establece una dedicación mínima de entre 20 y 25 horas semanales por integrante, lo que permite sostener el avance del proyecto durante sus distintas etapas.
Por lo tanto, se concluye que PRISMA es viable desde el punto de vista humano.
Viabilidad legal
Desde el punto de vista legal, PRISMA se considera viable, siempre que el desarrollo respete las normas vinculadas a protección de datos, privacidad, derechos de autor y uso adecuado de herramientas de software.
A diferencia de otros sistemas que pueden no manejar información sensible, PRISMA trabaja con datos relacionados a organizaciones, usuarios, roles, evaluaciones de ciberseguridad, evidencias, resultados y planes de mejora. Por este motivo, el propio proyecto contempla criterios de seguridad, privacidad, control de acceso, disponibilidad y escalabilidad debido a la sensibilidad de la información gestionada.
En relación con la Ley 18.331 de Protección de Datos Personales y Acción de Habeas Data, la plataforma deberá garantizar que cada usuario acceda únicamente a la información que le corresponde, aplicando mecanismos de autenticación, autorización, roles, permisos y trazabilidad.
También se debe considerar la Ley 17.616 de Derechos de Autor y Derechos Conexos, ya que PRISMA será un desarrollo propio del equipo. En caso de utilizar librerías, frameworks o herramientas de código abierto, deberán respetarse sus licencias correspondientes.
Además, el alcance del proyecto aclara que PRISMA no sustituirá el trabajo profesional de un auditor, no realizará auditorías formales y tampoco emitirá certificaciones oficiales del nivel de madurez de una organización. Esto ayuda a delimitar responsabilidades legales del sistema.
Por lo tanto, se concluye que PRISMA es viable desde el punto de vista legal.
Viabilidad técnica
Desde el punto de vista técnico, PRISMA se considera viable porque el proyecto cuenta con un alcance definido, una metodología adecuada y una planificación incremental.
El documento establece que PRISMA será desarrollado mediante un ciclo de vida híbrido, iterativo e incremental. Esto permite comenzar con una planificación inicial y luego avanzar en versiones funcionales sucesivas, validando y ajustando la solución durante el desarrollo. En una primera etapa se prioriza un Producto Mínimo Viable orientado a la evaluación guiada, el registro de respuestas, el cálculo del nivel de madurez y la visualización básica de resultados. Luego se podrán incorporar funcionalidades más avanzadas como evidencias, auditoría, reportes, planes de mejora, trazabilidad y comparación histórica.
También se definió un backlog organizado por incrementos. El primer incremento incluye las funcionalidades base, como organizaciones, usuarios, roles, catálogo MCU 5.0, evaluaciones, respuestas, cálculo de madurez y resultados básicos. Los incrementos posteriores incorporan evidencias, auditoría, trazabilidad, planes de mejora, reportes y funcionalidades avanzadas.
Si bien el proyecto presenta desafíos técnicos, especialmente en seguridad, control de acceso, multi-tenancy, cálculo de madurez, trazabilidad y gestión de evidencias, estos pueden ser abordados de forma progresiva mediante sprints, pruebas funcionales, pruebas de integración y revisiones técnicas.
Por lo tanto, se concluye que PRISMA es viable desde el punto de vista técnico.
Viabilidad económico-financiera
Desde el punto de vista económico-financiero, PRISMA se considera viable porque se trata de un proyecto académico desarrollado por los propios integrantes del equipo, por lo que no requiere contratación externa de recursos humanos para su ejecución.
El documento realiza una estimación de costos tomando como base el período comprendido entre el 03/08/2026 y el 04/12/2026, equivalente a 90 días hábiles y 720 horas estimadas de trabajo. Para valorizar el esfuerzo se utiliza una tarifa de referencia de USD 30 por hora técnica. A partir de la estimación PERT, se calcula un esfuerzo esperado de 747 horas y un costo total referencial de USD 28.092, incluyendo horas, servicios y reserva.
Sin embargo, este valor debe interpretarse como una referencia profesional del esfuerzo necesario para desarrollar PRISMA, no necesariamente como un gasto directo del equipo. Al ser un proyecto de grado, el principal recurso invertido será el tiempo de los integrantes. Los costos reales pueden reducirse utilizando herramientas de código abierto, entornos locales, servicios gratuitos o infraestructura de bajo costo durante la etapa inicial.
Por lo tanto, se concluye que PRISMA es viable desde el punto de vista económico-financiero.

Conclusión del análisis de viabilidad
Luego de analizar los aspectos humanos, legales, técnicos y económico-financieros, se concluye que el proyecto PRISMA es viable.
El equipo cuenta con roles definidos, una dedicación semanal estimada y una modalidad de trabajo organizada. Legalmente, el proyecto puede desarrollarse siempre que se respeten las normas de protección de datos, privacidad, derechos de autor y el alcance declarado del sistema. Técnicamente, PRISMA puede construirse de forma progresiva mediante un enfoque híbrido, iterativo e incremental. Finalmente, desde el punto de vista económico, el proyecto no requiere grandes inversiones iniciales, ya que el principal recurso es el trabajo del equipo y el uso de herramientas accesibles o de bajo costo.
Por todo lo anterior, se considera viable continuar con el desarrollo del proyecto PRISMA.
Definiciones de Inicio
Ciclo de vida del proyecto
Para el desarrollo del proyecto PRISMA se adoptará un ciclo de vida híbrido, iterativo e incremental. Este enfoque permite combinar una planificación inicial estructurada con la flexibilidad necesaria para ajustar la solución a medida que se profundiza el análisis técnico, funcional y normativo del sistema.
La elección de este ciclo de vida resulta adecuada debido a las características del proyecto, ya que PRISMA no solo implica el desarrollo de una plataforma web, sino también la interpretación de un marco de ciberseguridad, la definición de criterios de evaluación, el cálculo de niveles de madurez, la gestión de evidencias, la trazabilidad de acciones y la generación de reportes y planes de mejora. Estos aspectos requieren una etapa inicial de análisis y planificación, pero también validaciones progresivas durante el desarrollo.
En una primera instancia, el componente predictivo del ciclo de vida permitirá establecer las bases del proyecto, definiendo el alcance, los objetivos, los requerimientos principales, la arquitectura general, las tecnologías a utilizar y la planificación de entregables. Esta etapa resulta fundamental para ordenar el trabajo del equipo, reducir incertidumbres iniciales y asegurar que la solución se mantenga alineada con el propósito del proyecto.
Por otra parte, el componente iterativo permitirá revisar y ajustar periódicamente los requisitos, las reglas de evaluación, los criterios de madurez y las funcionalidades desarrolladas. A medida que el equipo avance, será posible validar decisiones, corregir desvíos y mejorar la solución en función de los aprendizajes obtenidos durante el proceso.
El enfoque incremental permitirá construir PRISMA en versiones funcionales sucesivas. En una primera etapa se desarrollará un producto mínimo viable orientado a la evaluación guiada, el registro de respuestas, el cálculo del nivel de madurez y la visualización básica de resultados. Posteriormente, se podrán incorporar funcionalidades más avanzadas, como la gestión de evidencias, auditoría, reportes ejecutivos y técnicos, planes de mejora, trazabilidad y comparación histórica de evaluaciones.
De esta forma, el ciclo de vida seleccionado permite entregar valor de manera progresiva, mantener control sobre el avance del proyecto y responder de forma ordenada ante posibles cambios o ajustes. Además, favorece la validación continua de la plataforma, asegurando que PRISMA evolucione de manera coherente con las necesidades identificadas y con los objetivos definidos para la evaluación de madurez en ciberseguridad.

Metodología
Para el desarrollo del proyecto PRISMA se utilizará una metodología híbrida con predominio ágil, basada principalmente en Scrum y complementada con prácticas tradicionales de planificación, documentación, gestión de riesgos y control de calidad. Esta elección permite combinar la flexibilidad necesaria para adaptarse a cambios o ajustes durante el desarrollo, con una estructura ordenada que facilite el seguimiento del proyecto, la definición de entregables y el cumplimiento de los objetivos establecidos.
La adopción de un enfoque ágil resulta adecuada debido a la naturaleza del proyecto, ya que PRISMA involucra aspectos técnicos, funcionales y normativos que requieren validación progresiva. La plataforma deberá contemplar la evaluación de madurez en seguridad de la información y ciberseguridad, la interpretación del Marco de Ciberseguridad de AGESIC v5.0, la gestión de evidencias, el cálculo de resultados, la trazabilidad de acciones, la generación de reportes y la construcción de planes de mejora. Por este motivo, resulta conveniente trabajar en ciclos cortos que permitan revisar avances, validar decisiones y realizar ajustes de forma continua.
El trabajo se organizará mediante sprints de dos semanas, en los cuales el equipo definirá un objetivo concreto, seleccionará las tareas prioritarias del backlog, desarrollará las funcionalidades correspondientes, realizará pruebas y revisará los resultados obtenidos. Al finalizar cada sprint, se evaluará el incremento desarrollado, se identificarán oportunidades de mejora y se ajustará la planificación de las siguientes iteraciones.
Como parte de la metodología, se mantendrá un backlog del producto compuesto por requerimientos, historias de usuario, tareas técnicas y criterios de aceptación. Este backlog será revisado periódicamente para priorizar las funcionalidades de mayor valor, comenzando por aquellas necesarias para el producto mínimo viable, como la evaluación guiada, el registro de respuestas, el cálculo de madurez y la visualización básica de resultados. Posteriormente, se podrán incorporar funcionalidades más avanzadas, como gestión de evidencias, auditoría, reportes, planes de mejora y trazabilidad.
Si bien Scrum será utilizado como marco principal de trabajo, se incorporarán prácticas tradicionales para fortalecer la gestión del proyecto. Entre ellas se incluyen la planificación inicial del alcance, la definición de roles y responsabilidades, la documentación técnica y funcional, la identificación de riesgos, el seguimiento de avances y la validación de entregables. Estas prácticas permitirán mantener una dirección clara y asegurar que el desarrollo se mantenga alineado con los objetivos del proyecto.
La gestión de riesgos tendrá un papel importante dentro de la metodología, debido a que PRISMA trabajará con información sensible y con procesos relacionados a la seguridad de la información. Por este motivo, se considerarán riesgos vinculados a la privacidad de los datos, control de acceso, trazabilidad, interpretación normativa, disponibilidad del sistema, calidad del software y cumplimiento de los requerimientos definidos. Estos riesgos serán revisados durante el avance del proyecto, permitiendo tomar acciones preventivas o correctivas cuando sea necesario.
La comunicación del equipo será constante y estará apoyada en reuniones periódicas de seguimiento, planificación, revisión y retrospectiva. Estas instancias permitirán coordinar tareas, detectar bloqueos, evaluar el progreso y mejorar la forma de trabajo. Además, se promoverá la documentación de decisiones relevantes, avances técnicos y resultados de cada etapa, con el fin de mantener trazabilidad durante todo el proceso de desarrollo.
En cuanto al control de calidad, se realizarán revisiones funcionales y técnicas durante cada iteración, verificando que las funcionalidades desarrolladas cumplan con los criterios definidos. También se contemplarán pruebas sobre los módulos principales de la plataforma, especialmente aquellos relacionados con autenticación, roles, evaluación, cálculo de madurez, gestión de evidencias, reportes y trazabilidad.
Gestión del Product Backlog
Para organizar y priorizar el desarrollo de PRISMA se elaboró un Product Backlog que reúne las funcionalidades, necesidades del negocio, tareas técnicas y condiciones de calidad identificadas durante el relevamiento.
El backlog fue construido a partir de la revisión documental del Marco de Ciberseguridad de AGESIC v5.0, las entrevistas realizadas con referentes del área, el análisis del proceso actual de evaluación y las necesidades identificadas por el equipo. Los requerimientos definidos en la especificación constituyeron la base para la elaboración de épicas, historias de usuario y tareas técnicas.
El backlog será gestionado de forma evolutiva durante el proyecto. Al inicio de cada sprint se seleccionarán los elementos de mayor prioridad que se encuentren suficientemente definidos. Durante las instancias de refinamiento se revisará su alcance, se incorporarán criterios de aceptación, se dividirán elementos de gran tamaño y se ajustará su prioridad de acuerdo con las validaciones realizadas y con el avance del proyecto.

Organización del backlog por incrementos
El backlog de PRISMA se organizara inicialmente en cuatro incrementos:
Incremento 1 – Producto Mínimo Viable
Comprende las funcionalidades fundamentales para registrar organizaciones y usuarios, gestionar roles, cargar el catálogo del MCU 5.0, crear evaluaciones, responder controles, calcular la madurez y visualizar los resultados básicos.
Incremento 2 – Evidencias, auditoría y trazabilidad
Incorpora la carga y gestión de evidencias, el proceso de revisión por auditores, las observaciones, los cambios de estado y el registro de las acciones relevantes en la bitácora.
Incremento 3 – Planes de mejora y reportes
Incluye la generación de planes de mejora, la asignación de responsables y plazos, los tableros de seguimiento y la generación de reportes ejecutivos y técnicos.
Incremento 4 – Funcionalidades avanzadas
Contempla funcionalidades de menor prioridad o mayor complejidad, como la autenticación en dos factores, la importación de nuevas versiones del catálogo, la comparación histórica de evaluaciones, la firma de reportes y otras mejoras avanzadas.
La elaboración del backlog permite transformar las necesidades generales del proyecto en elementos de trabajo concretos y priorizados. También facilitara la identificación de dependencias entre módulos y la definición de un orden de construcción coherente.
Como resultado inicial del análisis, se determinó que la evaluación guiada, el cálculo de madurez, la gestión de organizaciones y el control de acceso constituyen la base del Producto Mínimo Viable. En cambio, las evidencias, la auditoría, los reportes especializados y los planes de mejora dependen de esa base y serán incorporados progresivamente.
El backlog no será considerado un documento estático, sino una herramienta de gestión que evolucionará a partir de las validaciones con los interesados, los resultados de cada sprint y los riesgos técnicos o funcionales identificados durante el desarrollo.
A continuación queda el enlace al backlog inicial
Backlog
Planificaciones de reuniones de equipo
El equipo ITDICE ha definido una planificación de reuniones orientada a mantener una comunicación fluida, controlar el avance del proyecto y asegurar el cumplimiento de las tareas asignadas en cada etapa. Esta dinámica resulta fundamental para el desarrollo de PRISMA, ya que el proyecto involucra aspectos técnicos, funcionales y normativos que requieren coordinación permanente entre los integrantes.
La distribución del trabajo se realizará de forma equitativa, considerando las habilidades y responsabilidades de cada miembro del equipo. Todos los integrantes participarán en actividades de análisis, documentación, diseño, desarrollo, pruebas y presentación de avances. Cada integrante deberá mantener informado al resto del equipo sobre el estado de sus tareas, comunicar posibles bloqueos y solicitar apoyo cuando sea necesario.
La comunicación interna se realizará principalmente mediante WhatsApp para intercambios rápidos, correo electrónico para comunicaciones formales y Google Meet de UTEC para reuniones virtuales. Además, se utilizarán herramientas colaborativas para el seguimiento de tareas, documentación y control de avances del proyecto.
Dado que la metodología de trabajo se basa en un enfoque híbrido con predominio ágil, se establecerán reuniones periódicas vinculadas al ciclo de desarrollo por sprints. Estas instancias permitirán planificar el trabajo, revisar avances, ajustar el backlog, validar funcionalidades y mejorar la forma de trabajo del equipo.
Se definen las siguientes instancias de coordinación:
Reunión
Periodicidad
Modalidad
Duración estimada
Objetivo
Daily / seguimiento breve
Lunes a viernes
Virtual
15 min
Sincronizar avances, bloqueos y tareas del día
Sprint Planning
Cada 2 semanas
Virtual
1 a 1,5 h
Definir objetivo del sprint y tareas
Refinamiento de backlog
Semanal
Virtual
1 h
Ajustar requisitos, historias de usuario y criterios
Revisión técnica
Semanal
Virtual
1 h
Analizar arquitectura, seguridad y base de datos
Sprint Review
Cada 2 semanas
Virtual
1 h
Mostrar incremento desarrollado
Retrospectiva
Cada 2 semanas
Virtual
30 min
Evaluar mejoras en la forma de trabajo
Coordinación general
Martes y jueves
Virtual
2 a 2,5 h
Trabajo colaborativo y seguimiento general



Dedicación estimada
Cada integrante se compromete a una dedicación semanal mínima de entre 20 y 25 horas, contemplando actividades individuales, reuniones de equipo, desarrollo, documentación, investigación, pruebas y preparación de entregables.
La dedicación podrá aumentar en semanas cercanas a entregas formales, revisiones del anteproyecto, defensas o hitos relevantes del Proyecto Final de Carrera.
Roles
Federico De Armas:
Analista Funcional, QA tester
Fernando Araujo:
Arquitecto de Software, Desarrollo Full Stack
Joaquin Franco:
PM, QA Tester


Análisis de Requerimientos
Para el desarrollo de PRISMA se realizó un análisis de requerimientos con el objetivo de identificar y organizar las funcionalidades necesarias para una plataforma orientada a la evaluación de madurez en seguridad de la información y ciberseguridad.
Este análisis permite definir el alcance del sistema, contemplando aspectos como la gestión de usuarios y roles, evaluaciones, evidencias, cálculo de madurez, reportes, trazabilidad y planes de mejora.
A partir de este relevamiento, los requerimientos fueron clasificados en funcionales y no funcionales, permitiendo diferenciar las capacidades del sistema de los criterios de calidad vinculados a seguridad, privacidad, usabilidad y escalabilidad.
Alcance del Proyecto
Descripción general
PRISMA es una plataforma web orientada a la evaluación integral de la madurez en ciberseguridad de las organizaciones, tomando como referencia el Marco de Ciberseguridad de AGESIC v5.0. El proyecto abarca el análisis, diseño, desarrollo, prueba, documentación e implantación de una solución que permita centralizar el proceso de evaluación, reemplazando el uso de planillas de cálculo y registros dispersos por un entorno seguro, estructurado y trazable.

El alcance se aborda de forma iterativa e incremental, priorizando un Producto Mínimo Viable (MVP) sobre el cual se incorporan funcionalidades de mayor complejidad a lo largo de cuatro incrementos definidos por el equipo.
Alcance incluido
El proyecto contempla el desarrollo de las siguientes capacidades:

Gestión de organizaciones, usuarios y roles, con perfiles diferenciados (administrador, responsable de organización, evaluador interno, auditor y visualizador) y aislamiento de datos entre organizaciones (multi-tenancy).
Estructuración del catálogo MCU 5.0 en funciones, categorías, subcategorías, requisitos y controles, con anclaje de la versión vigente en cada evaluación generada.
Evaluación guiada de madurez mediante cuestionarios estructurados, con registro de respuestas y comentarios, y control del ciclo de vida de cada evaluación (borrador, en curso, lista para auditoría, en auditoría, aprobada, devuelta o archivada).
Cálculo automático del nivel de madurez, global y detallado por función, categoría y subcategoría, con identificación de brechas y niveles objetivo.
Gestión de evidencias, permitiendo la carga y asociación de documentación de respaldo a los controles evaluados.
Módulo de auditoría para la revisión de evaluaciones, la validación de evidencias y el registro de observaciones.
Generación de planes de mejora a partir de las brechas detectadas, con acciones, responsables, prioridades, estados y fechas objetivo.
Reportes técnicos y ejecutivos que presenten resultados, gráficos, brechas y recomendaciones de mejora.
Tableros de visualización con indicadores para la interpretación rápida de resultados.
Trazabilidad y bitácora de las acciones relevantes realizadas dentro de la plataforma.
Seguridad y control de acceso, contemplando autenticación, autorización y protección de la información sensible.
Entregables principales
Documentación del proyecto: anteproyecto, especificación de requerimientos bajo el estándar IEEE 830, y planes de gestión de riesgos, tiempo y costos.
Plataforma web funcional, desplegada en ambientes de testing, pre-producción y producción.
Base de datos relacional y no relacional (PostgreSQL) .
Configuración de integración y despliegue continuo (CI/CD) y de alta disponibilidad con replicación de datos.
Manuales de usuario y documentación técnica de soporte y mantenimiento.
Fuera del alcance
Quedan explícitamente excluidos de esta versión inicial:

La validación automática del contenido, la veracidad o la suficiencia de las evidencias cargadas.
La realización de auditorías formales o la sustitución del trabajo profesional de un auditor.
La certificación oficial del nivel de madurez de una organización.
El desarrollo de una aplicación móvil nativa (Android o iOS).
La integración automática con nuevas versiones del marco de ciberseguridad.
El soporte multi-idioma; la versión inicial se limita al idioma español.
La integración con otros marcos, normas o estándares distintos al MCU 5.0.
La validación criptográfica avanzada de documentos (firmas digitales, certificados, sellos de tiempo o autenticidad legal de los archivos).
Técnicas de relevamiento empleadas
Para el análisis de requerimientos del proyecto PRISMA se emplearon distintas técnicas de relevamiento, orientadas a comprender el proceso de evaluación de madurez en seguridad de la información y ciberseguridad, así como las necesidades funcionales y técnicas que debe contemplar la plataforma.
En primer lugar, se realizó una revisión documental del Marco de Ciberseguridad de AGESIC v5.0, con el objetivo de identificar su estructura, funciones, categorías, subcategorías, controles y criterios de madurez. Esta revisión permitió establecer la base sobre la cual se organizarán las evaluaciones dentro del sistema.
Asimismo, se mantuvo una entrevista con Fabiana Santellán, Gerente de Gestión y Auditoría del área de Seguridad de la Información de AGESIC. Esta instancia permitió obtener una visión más cercana sobre la aplicación del marco, los procesos de evaluación, la importancia de la trazabilidad, el registro de evidencias y los desafíos que enfrentan las organizaciones al momento de medir su nivel de madurez en ciberseguridad.
También se realizó una entrevista con un gerente de la empresa Novatec, con el objetivo de conocer una perspectiva del sector privado sobre la gestión de la seguridad de la información, la evaluación de controles, la documentación de evidencias y la necesidad de contar con herramientas que faciliten el seguimiento y la mejora continua.

Metodología de priorización
Se definió una estrategia inicial para ordenar los requerimientos de acuerdo con su importancia y urgencia, tomando como base criterios previamente ponderados. No obstante, esta priorización se encuentra aún en etapa de análisis, ya que será necesario realizar una validación posterior con el cliente.
Identificación de requerimientos
Los requerimientos fueron clasificados en dos categorías
Funcionales
Relacionados con las funcionalidades que el sistema debe ofrecer.
No funcionales
Referidos a aspectos como rendimiento, seguridad, usabilidad, disponibilidad, entre otros.
Criterios de priorización
Se definió una estrategia inicial para priorizar los requerimientos del proyecto PRISMA, considerando su importancia, urgencia e impacto dentro de la solución. Sin embargo, dicha priorización aún se encuentra en etapa de análisis. Para cerrar los criterios y la fórmula de priorización, será necesario realizar una validación posterior con el cliente y/o referentes del área. A partir de esa validación, se ajustará el orden de los requerimientos para asegurar que responda al alcance y objetivos del proyecto. 

Fórmula / Revisión y validación / Matriz
Esta información está siendo desarrollada por el equipo, a su vez que está siendo validada con el cliente para poder reflejar fielmente las necesidades y expectativas. Una vez terminada dicha validación se presentará en este documento.
Estructura de Desglose de Trabajo 
Para organizar el trabajo de manera eficiente y visualizar claramente el alcance del proyecto, se desarrolló una Estructura de Desglose de Trabajo (EDT) que fragmenta el sistema en componentes funcionales y técnicos. Esta estructura permite una planificación detallada, facilita el seguimiento del avance y mejora la asignación de responsabilidades a los miembros del equipo.
Diagrama EDT
El EDT propuesto se estructura jerárquicamente desde los componentes generales del sistema hasta las tareas específicas que forman parte de cada módulo. Las categorías principales incluyen:
1.1 Gestión de Proyecto y Metodología
Planificación
Gestión de Riesgos y Calidad
Gestión de Documentación
1.2 Relevamiento y Análisis de Requerimientos
Reunión con el cliente
Identificación de Requerimientos Funcionales y NO Funcionales
Especificación de requerimientos IEEE830
1.3 Implementación de Plataforma
Diseño de Base de Datos PostgreSQL 
Backend
Frontend
Autenticación
1.4 Módulos Funcionales de la Plataforma
Perfiles
Usuarios
Dashboard
Gráficos interactivos
Evaluación
Configuración del Marco
Cálculo de Madurez
Plan de Mejora
Reportes
1.5 Testing
Plan de Testing
Pruebas Funcionales
Registros de Fallas
Corrección de Errores Reportados
Pruebas de Integración
1.6 Configuración
Configuración de Entornos de Producción/Pre Producción/Testing
Configuraciones CI/CD
Precarga de de datos iniciales
Configuración de alta disponibilidad y replicación de datos
1.7 Soporte y Mantenimiento
Documentación
Soporte Técnico
Actualizaciones




Diccionario EDT

Código
Tarea
Descripción
1.1
Gestión de Proyecto y Metodología
Actividades generales de dirección, administración y control del proyecto.
1.1.1
Planificación
Definición de cronogramas, asignación de recursos y estimación de tiempos.
1.1.2
Gestión de Documentación
Organización, almacenamiento y control de versiones de todos los documentos del proyecto.
1.2
Relevamiento y Análisis de Requerimientos
Fase de comprensión y definición detallada de lo que el sistema debe hacer.
1.2.1
Reuniones con el cliente
Encuentros orientados a entender las necesidades del negocio y de los usuarios finales.
1.2.2
Identificación de RF y RNF
Detección y listado de los Requerimientos Funcionales (qué hará el sistema) y No Funcionales (rendimiento, seguridad, etc.).
1.2.3
Especificación de Requerimientos (IEEE830)
Redacción formal del documento que detalla todos los requerimientos siguiendo el estándar internacional IEEE 830.
1.3
Implementación de Plataforma
Construcción de la base técnica y arquitectónica del sistema de software.
1.3.1
Diseño de Base de Datos PostgreSQL
Modelado y estructuración de las bases de datos relacionales y no relacionales (PostgreSQL).
1.3.2
Backend
Desarrollo de la lógica del servidor, procesamiento de datos y creación de APIs.
1.3.3
Frontend
Desarrollo de la interfaz visual y la experiencia de usuario con la que interactuará el cliente.
1.3.4
Autenticación
Implementación del sistema de login, control de acceso y seguridad de credenciales.
1.4
Módulos Funcionales Plataforma
Desarrollo de las características y funcionalidades específicas del sistema.
1.4.1
Perfiles
Gestión de roles, permisos y niveles de acceso dentro de la plataforma.
1.4.2
Dashboard
Creación del panel principal que muestra un resumen visual de la información más relevante.
1.4.3
Gráficos Interactivos
Desarrollo de visualizaciones dinámicas de datos para facilitar el análisis al usuario.
1.4.4
Usuarios
Módulo administrativo para crear, editar, suspender y eliminar cuentas de personas.
1.4.5
Evaluación
Funcionalidad que permite ejecutar, registrar y guardar los resultados de las evaluaciones en el sistema.
1.4.6
Configuración del Marco
Módulo para ajustar los parámetros, criterios o la metodología base (framework) que utiliza la plataforma.
1.4.7
Cálculo de Madurez
Algoritmo que procesa los datos ingresados para determinar el nivel de madurez o resultado final.
1.4.8
Plan de Mejora
Herramienta para gestionar acciones correctivas, recomendaciones o pasos a seguir tras las evaluaciones.
1.4.9
Reportes
Generación y exportación de informes detallados con los resultados obtenidos en el sistema.
1.5
Testing
Actividades de control de calidad para asegurar que el software funcione sin errores.
1.5.1
Plan de Testing
Diseño de la estrategia, escenarios y casos de prueba que se van a ejecutar.
1.5.2
Pruebas Funcionales
Verificación práctica de que cada botón, módulo y función del sistema haga lo que se espera.
1.5.3
Registro de Fallas
Documentación y reporte detallado de los errores (bugs) encontrados durante las pruebas.
1.5.4
Corrección de Errores Reportados
Resolución en el código por parte del equipo de desarrollo de los bugs encontrados.
1.5.5
Pruebas de Integración
Comprobación de que los distintos módulos y servicios funcionen correctamente al interactuar entre sí.
1.6
Configuración
Preparación de la infraestructura y entornos donde operará el software.
1.6.1
Configuración de Entornos Prod/Pre-Prod/Testing
Preparación de los servidores físicos o en la nube para las fases de pruebas, pre-producción y producción en vivo.
1.6.2
Configuración CI/CD
Automatización del proceso de integración y despliegue continuo del código fuente.
1.6.3
Precarga de datos Iniciales
Inserción en la base de datos de la información base (semilla) necesaria para que el sistema pueda empezar a usarse.
1.6.4
Configuración de alta disponibilidad y replicación
Ajustes en servidores y bases de datos para asegurar que el sistema no se caiga y tenga respaldos inmediatos.
1.7
Soporte y Mantenimiento
Tareas posteriores a la entrega para asegurar la estabilidad y uso continuo del sistema.
1.7.1
Documentación
Creación de manuales de usuario y guías técnicas sobre el uso de la plataforma final.
1.7.2
Soporte Técnico
Asistencia directa a los usuarios y administradores para resolver dudas o problemas de uso.
1.7.3
Actualizaciones
Aplicación de parches de seguridad, optimizaciones de rendimiento y mejoras menores.


Requerimientos Funcionales
Se presentan a continuación los requerimientos funcionales más relevantes del sistema, seleccionados por su aporte directo al valor del producto y a los objetivos principales del proyecto. Estos requerimientos representan las funcionalidades centrales de la solución, permitiendo transformar un proceso de evaluación de madurez en ciberseguridad que podría realizarse de forma manual y dispersa, en una plataforma centralizada, segura, trazable y orientada a la mejora continua.
El sistema contempla el requerimiento RF-ORG-06 — Protección de información / multi-tenancy, el cual resulta fundamental para garantizar que cada organización acceda únicamente a la información que le corresponde. Este aspecto aporta valor al cliente al asegurar la confidencialidad de sus datos, evaluaciones, evidencias y resultados, evitando que usuarios de otras organizaciones puedan visualizar información ajena.
Como base del proceso de evaluación, se incorpora el requerimiento RF-MCU-01 — Estructuración jerárquica del catálogo MCU 5.0, mediante el cual el sistema organizará el marco de referencia en funciones, categorías, subcategorías, requisitos y controles. Esto permite que la organización trabaje sobre una estructura clara, ordenada y alineada al Marco de Ciberseguridad MCU 5.0, reduciendo la dependencia de planillas o documentos aislados.
A su vez, el requerimiento RF-MCU-09 — Anclaje de versión en evaluaciones generadas permite que cada evaluación quede asociada a la versión del catálogo vigente al momento de su creación. Esto aporta trazabilidad y consistencia histórica, ya que los resultados obtenidos podrán interpretarse en función de la versión del marco utilizada, incluso si posteriormente se incorporan nuevas versiones o actualizaciones.
Dentro del flujo principal de uso, el requerimiento RF-EVA-02 — Generación de cuestionario de evaluación permite automatizar la creación del cuestionario a partir del catálogo MCU 5.0 vigente. Esta funcionalidad aporta valor al cliente al reducir el trabajo manual, minimizar errores y guiar al evaluador durante el proceso de diagnóstico.
Complementariamente, el requerimiento RF-EVA-09 — Estados de evaluación permite controlar el ciclo de vida de cada evaluación, contemplando estados como borrador, en curso, lista para auditoría, en auditoría, aprobada, devuelta o archivada. Esto facilita el seguimiento del proceso y permite ordenar el trabajo entre responsables, evaluadores y auditores.
Una vez completada la evaluación, el requerimiento RF-CALC-04 — Cálculo de madurez global permite obtener un resultado consolidado sobre el nivel de madurez de la organización. Este indicador representa uno de los principales valores del sistema, ya que brinda una visión clara del estado actual de la organización en materia de ciberseguridad y sirve como insumo para la toma de decisiones.
Además del diagnóstico general, el requerimiento RF-CALC-07 — Indicadores de brecha y nivel objetivo permite identificar el nivel actual de cada subcategoría, el siguiente nivel objetivo y los controles pendientes para alcanzarlo. De esta forma, la plataforma no solo muestra el resultado de la evaluación, sino que también orienta a la organización sobre qué aspectos debe mejorar para avanzar en su madurez.
Para respaldar el proceso de evaluación, se incorpora el requerimiento RF-EVI-01 — Carga y asociación de evidencias, que permite adjuntar documentación vinculada a los controles evaluados. Esta funcionalidad aporta valor al cliente al fortalecer la confiabilidad del diagnóstico, facilitar auditorías y contar con respaldo documental sobre las respuestas registradas.
Luego de identificar las brechas, el requerimiento RF-PLN-01 — Generación automática de plan por brechas permite transformar los resultados de la evaluación en un plan de mejora concreto. Esta funcionalidad es especialmente relevante, ya que convierte el diagnóstico en acciones prácticas, ayudando a la organización a priorizar esfuerzos y avanzar hacia mejores niveles de cumplimiento y madurez.
Finalmente, el requerimiento RF-REP-01 — Emisión de reporte ejecutivo gerencial permite generar informes claros y resumidos para la alta dirección, incluyendo información como el nivel de madurez global, principales brechas y acciones recomendadas. Este reporte facilita la comunicación de resultados y permite que los responsables de la organización cuenten con información útil para la toma de decisiones estratégicas.
En conjunto, estos requerimientos funcionales representan el principal valor del producto: permitir que una organización evalúe su madurez en ciberseguridad, identifique brechas, respalde sus resultados con evidencias, genere planes de mejora y obtenga reportes ejecutivos que faciliten la gestión y la toma de decisiones.
El detalle completo de los requerimientos funcionales y no funcionales se encuentra disponible en los anexos a continuación
Requerimientos Funcionales
Requerimientos No Funcionales

Gestión de Riesgos
Se presenta a continuación el análisis de riesgos más relevante del proyecto, seleccionado por su aporte directo a la viabilidad, calidad y éxito del producto. Esta gestión de riesgos representa una de las bases fundamentales de la solución, permitiendo transformar un proceso de desarrollo que podría estar expuesto a incertidumbres, desvíos y vulnerabilidades, en un proyecto controlado, predecible y alineado con los objetivos estratégicos de PRISMA.
El sistema contempla el riesgo  R06 — Fallas de autenticación, autorización o separación de datos, el cual resulta crítico para garantizar la confidencialidad y el aislamiento de la información entre organizaciones. Abordar este riesgo aporta valor al cliente al asegurar que cada entidad acceda únicamente a sus propios datos, evaluaciones, evidencias y reportes, evitando exposiciones indebidas que comprometan la confianza en la plataforma.
Como base del proceso de evaluación, se aborda el riesgo  R04 — Interpretación incorrecta del Marco de Ciberseguridad de AGESIC v5.0, mediante el cual se documentan criterios, se valida con referentes y se establece una matriz de trazabilidad. Esto permite que la organización trabaje sobre una base normativa sólida, reduciendo la dependencia de interpretaciones subjetivas o documentación desactualizada, y asegurando la validez de los resultados obtenidos.
A su vez, el riesgo R05 — Errores en el cálculo automático de madurez se gestiona mediante la definición clara de fórmulas, la creación de casos de prueba y la validación manual de resultados. Esto aporta confiabilidad y precisión al sistema, ya que los niveles de madurez calculados podrán utilizarse con total seguridad para la toma de decisiones, incluso en escenarios de evaluaciones complejas o múltiples.
Dentro del flujo principal de desarrollo, el riesgo  R01 — Definición incorrecta o demasiado amplia del MVP se controla delimitando explícitamente las funcionalidades mínimas y postergando características avanzadas. Esta acción aporta valor al cliente al evitar desvíos en el cronograma, sobrecarga del equipo y pérdida de foco en los objetivos centrales del proyecto.
Complementariamente, el riesgo  R02 — Crecimiento no controlado del alcance se mitiga mediante un backlog priorizado y un mecanismo formal de control de cambios. Esto facilita el seguimiento del proyecto y permite ordenar el trabajo entre el equipo de desarrollo, los responsables funcionales y los interesados, asegurando que cada entregable aporte valor sin comprometer la planificación inicial.
Una vez identificados los riesgos, el riesgo  R11 — Ejecución insuficiente de pruebas se gestiona mediante un plan de testing desde etapas tempranas, criterios de aceptación por historia de usuario y pruebas de regresión. Este enfoque representa uno de los principales valores del sistema, ya que brinda una visión clara del estado de calidad del producto y sirve como insumo para garantizar la robustez de la plataforma.
Además del diagnóstico general, el riesgo  R07 — Exposición de evidencias o información sensible se aborda restringiendo accesos, validando formatos y registrando operaciones. De esta forma, la plataforma no solo protege la información, sino que también orienta a la organización sobre cómo gestionar adecuadamente los activos de información, fortaleciendo la confianza en el sistema.
Para respaldar el proceso de desarrollo, se incorpora la gestión del riesgo  R16 — Documentación insuficiente, que se mitiga documentando decisiones, arquitectura, reglas de cálculo y pruebas. Esta funcionalidad aporta valor al cliente al facilitar el mantenimiento, la validación y la presentación del proyecto, evitando pérdidas de conocimiento y garantizando la continuidad operativa.
Luego de identificar las brechas en el proyecto, el riesgo  R09 — Baja disponibilidad de integrantes del equipo se controla distribuyendo responsabilidades, compartiendo conocimiento y documentando avances. Esta acción es especialmente relevante, ya que convierte la vulnerabilidad organizacional en una fortaleza, ayudando al equipo a mantener la productividad incluso en escenarios de ausencia o rotación.
Finalmente, el riesgo  R10 — Escasa disponibilidad de referentes externos se gestiona planificando validaciones, documentando supuestos y avanzando por incrementos. Este enfoque facilita la comunicación con los interesados y permite que los responsables del proyecto cuenten con información útil para la toma de decisiones estratégicas, incluso cuando la retroalimentación externa no esté disponible de inmediato.
En conjunto, estos riesgos gestionados representan el principal valor del producto: permitir que el proyecto PRISMA evolucione de forma ordenada, segura, trazable y alineada con los objetivos definidos, minimizando incertidumbres y maximizando las posibilidades de éxito.
El detalle completo de los riesgos identificados, su análisis cualitativo y cuantitativo, y los planes de respuesta se encuentra disponible en el anexo a continuación.
Gestión de Riesgos
Gestión del Tiempo 
En esta sección se presenta la planificación temporal del proyecto PRISMA, con el objetivo de organizar las actividades necesarias para su ejecución dentro del período establecido. La gestión del tiempo permite al equipo definir una secuencia de trabajo, estimar duraciones, asignar responsables y controlar el avance del proyecto durante sus distintas etapas.
Para esta entrega se elaboró un cronograma definitivo utilizando la herramienta ProjectLibre. En dicho cronograma se representan las principales fases del proyecto, las tareas asociadas a cada etapa, las fechas de inicio y finalización, las duraciones estimadas, los hitos relevantes y los recursos asignados.
El cronograma contempla no solo las actividades de desarrollo de la plataforma, sino también tareas de gestión, relevamiento, análisis de requerimientos, documentación, testing, configuración, soporte y mantenimiento. De esta manera, se busca reflejar una planificación más completa y realista del trabajo que deberá realizar el equipo.
El proyecto se planifica desde el 03/08/2026 hasta el 04/12/2026, considerando jornadas de trabajo de lunes a viernes, con una dedicación estimada de 8 horas diarias. 



Para poder visualizarlo mejor le recomendamos ver el archivo adjunto
PDF
POD


Gestión de Costos
Escenarios de estimación de costos
La estimación de costos del proyecto PRISMA se realizó tomando como base el cronograma definido para el período comprendido entre el 03/08/2026 y el 04/12/2026, equivalente a 90 días hábiles de trabajo y un esfuerzo estimado de 720 horas.
Para la valorización de las actividades se utiliza una tarifa de referencia de USD 30 por hora técnica, valor representativo del mercado para tareas de análisis, desarrollo de software, testing, configuración de infraestructura y soporte técnico especializado.
Además de las horas de trabajo, se consideran costos asociados a servicios tecnológicos, infraestructura, herramientas de apoyo, almacenamiento y despliegue de ambientes necesarios para la ejecución del proyecto.
Escenario optimista
Este escenario asume que los requerimientos permanecen estables durante todo el proyecto, que no existen retrasos significativos en las validaciones y que el retrabajo es mínimo. Bajo estas condiciones se estima una reducción del 15% en el esfuerzo total respecto al cronograma base.
Escenario base
Corresponde al escenario considerado más probable. Se asume que las actividades se desarrollan conforme a la planificación establecida, manteniendo las 720 horas estimadas y contemplando una reserva moderada para cubrir riesgos operativos y ajustes menores durante la ejecución.
Escenario pesimista
Este escenario contempla situaciones como cambios de alcance, incremento en la complejidad técnica, defectos detectados durante las pruebas, retrasos en validaciones o necesidad de retrabajo. En consecuencia, se estima un incremento sustancial en las horas requeridas y una mayor reserva para la gestión de riesgos, la cantidad de horas corresponde a un cambio del calendario, tomando en cuenta 8 horas diarias, de lunes a domingo, lo que resulta en 992 horas.

En base a estos valores, y la fórmula de PERT llegamos al valor de 747 horas por lo que definimos como costo del proyecto lo siguiente

Escenario
Horas
Costo horas
Servicios
Reserva 
Costo Total
PERT
747
USD 22.410
USD 1.000
USD 4.682
USD 28.092


La estimación obtenida permite dimensionar el esfuerzo económico necesario para desarrollar e implantar una solución con las características de PRISMA en un entorno profesional, proporcionando una referencia para la planificación y el control financiero del proyecto.


Anexos
Historias de Usuario
Historias de Usuario
Stack Tecnológico
Stack
Minutas
Directorio de minutas
Infraestructura
Relevamiento
Ciberseguridad
Infraestructura y planeamiento

