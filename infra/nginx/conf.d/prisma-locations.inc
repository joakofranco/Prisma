# =====================================================================
# prisma-locations.inc — locations compartidas por los server blocks HTTP y HTTPS de prisma.conf
# =====================================================================
# Extraído a un include (nombre SIN ".conf": nginx.conf hace `include conf.d/*.conf`, así que un
# ".conf" acá se cargaría dos veces -- una por ese wildcard como si fuera su propio server block
# suelto -- y otra vez vía el `include` explícito de cada server) para no duplicar las locations
# entre el server de :80 y el de :443 -- ambos son formas soportadas de acceder a PRISMA (ver
# prisma.conf), así que deben proxyar exactamente igual. Lo único que cambia entre los dos es
# TLS/HSTS, que se queda en prisma.conf porque sólo aplica al server de :443.

add_header X-Frame-Options SAMEORIGIN always;
add_header X-Content-Type-Options nosniff always;

# ---- API Core ----
location /api/ {
    limit_req zone=api burst=50 nodelay;
    limit_conn addr 20;
    proxy_pass         http://backend_core;
    proxy_set_header   Host              $http_host;
    proxy_set_header   X-Real-IP         $remote_addr;
    # $remote_addr, NO $proxy_add_x_forwarded_for: este nginx es el único hop confiable frente
    # a backend-core/backend-ai/Keycloak, así que FIJA el header en vez de agregarle el IP
    # real al final de lo que ya venga -- con $proxy_add_x_forwarded_for, un cliente que
    # manda su propio "X-Forwarded-For: 1.2.3.4" queda como el primer valor de la lista, y
    # como AuditLogService.currentIp() (backend-core) toma ese header tal cual, terminaba
    # registrando en la bitácora el IP que el cliente eligiera en vez del real (spoofing).
    proxy_set_header   X-Forwarded-For   $remote_addr;
    # $scheme, NO "https" fijo: este include lo comparten el server de :80 y el de :443 (ver
    # prisma.conf), así que tiene que reflejar el protocolo real de cada request -- si no,
    # backend-core recibiría siempre "https" aunque el cliente haya entrado por HTTP.
    proxy_set_header   X-Forwarded-Proto $scheme;
    proxy_read_timeout 60s;
}

# ---- API IA / RAG ----
location /ai/ {
    limit_req zone=api burst=20 nodelay;
    proxy_pass         http://backend_ai/;
    proxy_set_header   Host              $http_host;
    proxy_set_header   X-Real-IP         $remote_addr;
    # $remote_addr, NO $proxy_add_x_forwarded_for -- ver el comentario en location /api/.
    proxy_set_header   X-Forwarded-For   $remote_addr;
    proxy_read_timeout 300s;         # RAG puede tardar
}

# ---- Keycloak (SSO) ----
location /auth/ {
    # SIN barra final en el destino: a diferencia de /api y /ai, acá se preserva el prefijo
    # /auth tal cual llegó (Keycloak corre con KC_HTTP_RELATIVE_PATH=/auth, así que espera
    # recibir ese prefijo -- ver el servicio "keycloak" en docker-compose.yml).
    proxy_pass         http://keycloak_svc;
    proxy_set_header   Host              $http_host;
    proxy_set_header   X-Real-IP         $remote_addr;
    # $remote_addr, NO $proxy_add_x_forwarded_for -- ver el comentario en location /api/.
    proxy_set_header   X-Forwarded-For   $remote_addr;
    # $scheme, NO "https" fijo -- ver el comentario en location /api/. Con hostname dinámico
    # (KC_HOSTNAME sin fijar + KC_PROXY_HEADERS=xforwarded), Keycloak arma el claim "iss" del
    # token a partir de ESTE header, así que un login por HTTP tiene que dar iss "http://..." y
    # uno por HTTPS "https://..." -- de ahí que KEYCLOAK_TRUSTED_ISSUERS (.env) liste ambas
    # variantes de cada host soportado, no sólo la https.
    proxy_set_header   X-Forwarded-Proto $scheme;
    # Con hostname dinámico (KC_HOSTNAME sin fijar + KC_PROXY_HEADERS=xforwarded), Keycloak arma
    # cada URL que genera (login, redirects) a partir de ESTE header, no del que reciba
    # directo -- sin esto, cae al Host interno keycloak:8080 y el login vuelve a romperse.
    #
    # $http_host, NO $host: $host nunca incluye el puerto, así que si NGINX_HTTPS_PORT se
    # remapea a algo distinto de 443 (p.ej. para evitar un conflicto local, ver
    # "Problemas comunes" en el README) Keycloak arma el login sin puerto y el POST del
    # formulario da connection refused -- mismo bug que en apps/frontend/nginx.conf.
    proxy_set_header   X-Forwarded-Host  $http_host;
}

# ---- Frontend (SPA Vue) ----
location / {
    proxy_pass         http://frontend_svc;
    proxy_set_header   Host              $http_host;
    proxy_set_header   X-Real-IP         $remote_addr;
    # $remote_addr, NO $proxy_add_x_forwarded_for -- ver el comentario en location /api/.
    proxy_set_header   X-Forwarded-For   $remote_addr;
}
