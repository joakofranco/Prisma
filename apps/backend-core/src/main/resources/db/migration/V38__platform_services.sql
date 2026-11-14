-- Lanzador de servicios de la infraestructura (menú «Servicios», solo Administrador de Plataforma
-- y solo desde la VPN). Cada fila es un servicio con su URL editable; default_url guarda la URL
-- de fábrica para poder restablecerla. El host de los valores iniciales es localhost: se cambia
-- desde la pantalla según la máquina anfitriona.
CREATE TABLE prisma.platform_services (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    service_key  VARCHAR(40)  NOT NULL UNIQUE,
    name         VARCHAR(80)  NOT NULL,
    description  VARCHAR(200) NOT NULL,
    category     VARCHAR(30)  NOT NULL,
    url          VARCHAR(500) NOT NULL,
    default_url  VARCHAR(500) NOT NULL,
    sort_order   INTEGER      NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO prisma.platform_services
    (service_key, name, description, category, url, default_url, sort_order) VALUES
    ('grafana',       'Grafana',       'Tableros de métricas y logs de la plataforma',          'OBSERVABILITY', 'http://localhost:3000',            'http://localhost:3000',            10),
    ('prometheus',    'Prometheus',    'Métricas, consultas y estado de los targets',            'OBSERVABILITY', 'http://localhost:9090',            'http://localhost:9090',            20),
    ('loki',          'Loki',          'Agregador de logs (se consulta desde Grafana)',          'OBSERVABILITY', 'http://localhost:3100/ready',      'http://localhost:3100/ready',      30),
    ('kibana',        'Kibana',        'Exploración de la bitácora indexada en Elasticsearch',   'OBSERVABILITY', 'http://localhost:5601',            'http://localhost:5601',            40),
    ('elasticsearch', 'Elasticsearch', 'Motor de búsqueda e índices de logs',                    'OBSERVABILITY', 'http://localhost:9200',            'http://localhost:9200',            50),
    ('cadvisor',      'cAdvisor',      'Uso de recursos de cada contenedor',                     'OBSERVABILITY', 'http://localhost:8082',            'http://localhost:8082',            60),
    ('portainer',     'Portainer',     'Administración de contenedores y volúmenes de Docker',   'INFRASTRUCTURE','https://localhost:9443',           'https://localhost:9443',           70),
    ('keycloak',      'Keycloak',      'Consola de administración de identidades y accesos',     'SECURITY',      'https://localhost/auth/admin/',    'https://localhost/auth/admin/',    80),
    ('rustfs',        'RustFS',        'Consola del almacenamiento de objetos (evidencias)',     'STORAGE',       'http://localhost:9002',            'http://localhost:9002',            90),
    ('pgadmin',       'pgAdmin',       'Administración de las bases de datos PostgreSQL',        'DATA',          'http://localhost:5050',            'http://localhost:5050',            100),
    ('sonarqube',     'SonarQube',     'Calidad y seguridad del código',                         'QUALITY',       'http://localhost:9003',            'http://localhost:9003',            110),
    ('ollama',        'Ollama',        'Servidor de modelos de IA locales',                      'AI',            'http://localhost:11434',           'http://localhost:11434',           120);
