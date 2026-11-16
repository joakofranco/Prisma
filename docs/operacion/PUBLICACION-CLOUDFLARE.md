# Publicación en internet con un VPS y Cloudflare Tunnel

## Arquitectura

```
Visitante ─► Cloudflare (DNS, DDoS, WAF) ─► túnel ─► cloudflared (en el VPS) ─► 127.0.0.1:8443 ─► prisma-nginx ─► app
Administrador ─► VPN ─► INTERNAL_BIND_IP:443 ─► prisma-nginx   (Servicios, Grafana, Portainer, etc. solo por aquí)
```

- Cloudflare resuelve el DNS y absorbe los ataques de red (SYN flood, TCP flood, UDP) y los de capa 7 antes de que lleguen al VPS.
- `cloudflared` ya corre en el VPS y abre la conexión hacia Cloudflare. El VPS no necesita ningún puerto de entrada abierto.
- NGINX sigue siendo la única puerta de entrada a la aplicación, con su límite de tasa y fail2ban como segunda capa.
- Las herramientas de administración no se publican en internet: solo se llega a ellas por VPN.

## Qué hace `docker-compose.cloudflare.yml`

Se suma a los overrides de producción:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.cloudflare.yml up -d --no-build
```

- **NGINX:** publica `127.0.0.1:${TUNNEL_ORIGIN_PORT:-8443}` (por donde entra el túnel) y `${INTERNAL_BIND_IP}:443` (por donde entran los administradores por VPN). No queda ningún puerto de NGINX abierto a internet.
- **IP real del visitante:** NGINX monta `infra/nginx/cloudflare/real-ip.conf`. `cloudflared` entra por el puerto publicado, así que NGINX ve la conexión desde la puerta de enlace de `prisma-net` (`172.28.0.1`). Solo desde ahí, o desde la IP de la VPN del propio servidor (`10.8.0.1`) si el túnel entra por esa IP, confía en la cabecera `CF-Connecting-IP`. Desde cualquier otro origen (un contenedor, otro cliente de la VPN) la cabecera se ignora, por lo que no se puede falsificar.
  - El servidor de desarrollo también debe desplegarse con este overlay: sin él NGINX ve todo el tráfico público desde una misma IP (la del túnel), el límite de tasa del login se comparte entre todos los visitantes y fail2ban no banea a nadie porque esa IP está en `F2B_IGNORE_IPS`.
  - Con esto el access log, el límite de tasa, fail2ban, el `X-Forwarded-For` hacia el backend y el control de VPN de Servicios trabajan con la IP real de cada visitante.
- **Puertos:** Grafana, Prometheus, Loki, Kibana, Elasticsearch, cAdvisor, Portainer, RustFS, pgAdmin, SonarQube, Ollama, Alertmanager y Mailpit se publican solo en `INTERNAL_BIND_IP`. PostgreSQL, Redis, backend-ai, frontend y los exporters no se publican en el host.

## Variables del `.env` del VPS

| Variable | Valor |
|---|---|
| `INTERNAL_BIND_IP` | IP de la VPN en el VPS (por ejemplo `10.8.0.1`). Si no hay VPN en el VPS, `127.0.0.1` y se accede por túnel SSH. |
| `TUNNEL_ORIGIN_PORT` | Puerto local por el que `cloudflared` entra a NGINX. Por defecto `8443`. |
| `CORS_ALLOWED_ORIGINS` | Agregar `https://prisma.tudominio.com`. |
| `KEYCLOAK_TRUSTED_ISSUERS` | Agregar `https://prisma.tudominio.com/auth/realms/prisma` y `https://<INTERNAL_BIND_IP>/auth/realms/prisma` (acceso de administración por VPN). |
| `KEYCLOAK_ISSUER_URL` | `https://prisma.tudominio.com/auth`. |
| `SERVICES_ALLOWED_CIDRS` | Redes desde las que se pueden editar las URL de Servicios: solo la red de la VPN, por ejemplo `10.8.0.0/24,127.0.0.0/8`. No dejar las redes privadas genéricas. El listado de Servicios se muestra desde cualquier red. |
| `F2B_IGNORE_IPS` | IPs de administración que nunca se bloquean (por ejemplo la red de la VPN). |

## Pasos en el VPS

1. **Firewall del proveedor** (el panel del VPS, no solo `ufw`): bloquear todo el tráfico entrante. Dejar solo SSH desde tu IP o desde la VPN, y el puerto de la VPN si el VPS la aloja. Docker publica puertos saltándose `ufw`, por eso el firewall del proveedor es el que cuenta.
2. Completar el `.env` con las variables de la tabla.
3. Levantar con los tres archivos de compose (comando de arriba).
4. Comprobar desde otra máquina, fuera de la VPN, que no responde ningún puerto del VPS: `nmap -Pn <ip-del-vps>`.

## Pasos en Cloudflare

### 1. Publicar la aplicación en el túnel
Zero Trust → Networks → Tunnels → tu túnel → Published application routes (Public Hostname):

| Subdominio | Servicio | Configuración adicional |
|---|---|---|
| `prisma.tudominio.com` | `HTTPS` → `localhost:8443` | TLS → **No TLS Verify** activado (el certificado de NGINX es de una CA interna y el tráfico no sale del servidor) |

Si el túnel está gestionado por archivo (`config.yml`), el equivalente es:

```yaml
ingress:
  - hostname: prisma.tudominio.com
    service: https://localhost:8443
    originRequest: { noTLSVerify: true }
  - service: http_status:404
```

- RustFS no se publica: las evidencias se suben y se descargan a través de `backend-core`, por el mismo dominio de la aplicación. No hace falta un subdominio para el almacenamiento ni exponer su API (`9001`) o su consola (`9002`).
- Cloudflare crea los registros DNS con el proxy activado (nube naranja). No dejes ningún registro `A` que apunte a la IP del VPS.

### 2. SSL/TLS
- SSL/TLS → Overview → modo **Full**.
- Edge Certificates → **Always Use HTTPS** activado y **HSTS** activado (la aplicación ya lo envía).

### 3. Protección contra bots y ataques
- Security → Bots → **Bot Fight Mode** activado.
- Security → Settings → **Security Level**: Medium (High durante un ataque).
- La protección DDoS de capas 3, 4 y 7 está activa por defecto y no hay que configurarla.

### 4. Reglas WAF personalizadas (Security → WAF → Custom rules)
1. **Bloquear el lanzador de Servicios desde internet** (defensa adicional al control de VPN del backend). Solo esa ruta: `/api/admin/email-settings` también cuelga de `/api/admin/` y la usa el administrador desde el dominio público:
   - Expresión: `starts_with(http.request.uri.path, "/api/admin/services")`
   - Acción: **Block**
2. **Desafío en el login:**
   - Expresión: `http.request.uri.path contains "/login-actions/authenticate"`
   - Acción: **Managed Challenge**
3. Opcional: desafiar o bloquear países desde los que no se espera tráfico.

### 5. Límite de tasa (Security → WAF → Rate limiting rules)
- Regla para el login: expresión `http.request.uri.path contains "/login-actions/authenticate"`, por ejemplo 10 peticiones cada 10 segundos por IP, acción **Block** durante el tiempo que permita el plan.
- Los planes gratuitos tienen pocas reglas y periodos fijos: priorizar el login.

### 6. Alertas (Notifications)
- Activar **HTTP DDoS Attack Alert** y **Layer 3/4 DDoS Attack Alert** hacia un correo del equipo.
- Se suman a las alertas locales de Prometheus: Cloudflare avisa de lo que absorbe en el borde y las locales avisan de lo que llega al VPS.

### 7. Keycloak
En la consola de Keycloak (acceso por VPN), realm `prisma` → Clients → `prisma-frontend`:
- **Valid redirect URIs:** `https://prisma.tudominio.com/*` y `https://<INTERNAL_BIND_IP>/*`.
- **Valid post logout redirect URIs:** las mismas.
- **Web origins:** `https://prisma.tudominio.com` y `https://<INTERNAL_BIND_IP>`.
- Quitar las URIs de `localhost` en producción.

## Verificación

1. **El VPS no responde desde internet:** `nmap -Pn <ip-del-vps>` sin puertos abiertos.
2. **La aplicación carga** en `https://prisma.tudominio.com` y el login funciona.
3. **IP real:** `docker logs prisma-nginx --tail 5` debe mostrar tu IP pública en la primera columna, no `172.28.0.1`.
4. **Control de VPN de Servicios:**
   - Desde internet, `https://prisma.tudominio.com/services` muestra el listado a un administrador, pero los enlaces no abren (apuntan a la VPN) y editar una URL debe responder 403 (`VPN_REQUIRED`).
   - Conectado a la VPN, los enlaces abren; para editar las URL hay que entrar por `https://<INTERNAL_BIND_IP>/services`.
5. **fail2ban:** hacer una ráfaga desde una IP que puedas desbloquear (`docker exec prisma-fail2ban fail2ban-client unban <IP>`) y comprobar el 429 y luego el 403.
6. **Evidencias:** subir y descargar un archivo de unos 90 MB.
7. **Alertas:** comprobar que llegan los correos de Alertmanager y las notificaciones de Cloudflare.

## Límites de Cloudflare que afectan a PRISMA

- **Tamaño de subida:** los planes Free y Pro aceptan cuerpos de hasta 100 MB. Las evidencias admiten hasta 100 MB (`EVIDENCE_MAX_SIZE`), así que un archivo cerca del tope puede fallar en Cloudflare antes de llegar a NGINX. Opciones: bajar el máximo a unos 90 MB o usar un plan con límite mayor.
- **Tiempo de espera:** Cloudflare corta con error 524 las peticiones que tardan más de unos 100 segundos sin respuesta. Las respuestas lentas de la IA podrían superarlo.
- **IP del VPS:** mientras exista algún registro (A, MX, histórico) que apunte a la IP del VPS, esa IP puede estar expuesta. Revisarlos y, si se filtró, cambiar la IP del VPS.
- Los límites exactos de cada plan cambian con el tiempo: confirmarlos en el panel.

## Durante un ataque

1. Revisar el dashboard «PRISMA · Estado general y alertas» de Grafana y Cloudflare → Security → Events.
2. Subir el Security Level a High, o activar «Under Attack» si el ataque es HTTP.
3. Bloquear en Cloudflare la IP, el ASN o el país de origen con una regla WAF.
4. Revisar lo que bloqueó fail2ban: `docker exec prisma-fail2ban fail2ban-client banned`.
5. Cuando termine, bajar el nivel de seguridad.
