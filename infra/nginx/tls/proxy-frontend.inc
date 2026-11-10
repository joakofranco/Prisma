# PRODUCCION (PRI-369): nginx -> frontend por TLS verificado contra la CA interna
# (scripts/generate-internal-ca.sh). Reemplaza conf.d/proxy-frontend.inc. El puerto TLS del
# frontend es el 8443, distinto del 80 del upstream frontend_svc, por eso apunta directo al host.
proxy_pass                    https://frontend:8443;
proxy_ssl_server_name         on;
proxy_ssl_name                frontend;
proxy_ssl_protocols           TLSv1.2 TLSv1.3;
proxy_ssl_verify              on;
proxy_ssl_verify_depth        2;
proxy_ssl_trusted_certificate /etc/nginx/internal-ca.crt;
