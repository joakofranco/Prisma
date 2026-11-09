# BUG-12 (PRI-369): TLS verificado en el tramo interno nginx -> Keycloak (8443).
# El certificado de Keycloak lo emite scripts/generate-internal-ca.sh; la CA se monta en
# /etc/nginx/internal-ca.crt (ver servicio "nginx" en docker-compose.yml).
proxy_ssl_server_name         on;
proxy_ssl_name                keycloak;
proxy_ssl_protocols           TLSv1.2 TLSv1.3;
proxy_ssl_verify              on;
proxy_ssl_verify_depth        2;
proxy_ssl_trusted_certificate /etc/nginx/internal-ca.crt;
