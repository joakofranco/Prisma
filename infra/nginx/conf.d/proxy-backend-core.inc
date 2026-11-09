# Destino de las locations /api/ (ver prisma-locations.inc). En desarrollo va por HTTP plano;
# docker-compose.prod.yml monta infra/nginx/tls/proxy-backend-core.inc encima de este archivo
# para hablar TLS verificado con backend-core (PRI-369).
proxy_pass http://backend_core;
