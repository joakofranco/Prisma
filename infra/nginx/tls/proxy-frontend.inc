proxy_pass                    https://frontend:8443;
proxy_ssl_server_name         on;
proxy_ssl_name                frontend;
proxy_ssl_protocols           TLSv1.2 TLSv1.3;
proxy_ssl_verify              on;
proxy_ssl_verify_depth        2;
proxy_ssl_trusted_certificate /etc/nginx/internal-ca.crt;
