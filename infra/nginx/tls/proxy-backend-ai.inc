# PRODUCCION (PRI-369): nginx -> backend-ai por TLS verificado contra la CA interna
# (scripts/generate-internal-ca.sh). Reemplaza conf.d/proxy-backend-ai.inc.
proxy_pass                    https://backend_ai/;
proxy_ssl_server_name         on;
proxy_ssl_name                backend-ai;
proxy_ssl_protocols           TLSv1.2 TLSv1.3;
proxy_ssl_verify              on;
proxy_ssl_verify_depth        2;
proxy_ssl_trusted_certificate /etc/nginx/internal-ca.crt;
