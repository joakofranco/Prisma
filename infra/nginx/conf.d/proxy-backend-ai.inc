# Destino de la location /ai/ (ver prisma-locations.inc). En desarrollo va por HTTP plano;
# docker-compose.prod.yml monta infra/nginx/tls/proxy-backend-ai.inc encima de este archivo
# para hablar TLS verificado con backend-ai (PRI-369).
proxy_pass http://backend_ai/;
