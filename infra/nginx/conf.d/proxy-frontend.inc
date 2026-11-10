# Destino de la location / (ver prisma-locations.inc). En desarrollo va por HTTP plano;
# docker-compose.prod.yml monta infra/nginx/tls/proxy-frontend.inc encima de este archivo
# para hablar TLS verificado con el contenedor frontend (PRI-369).
proxy_pass http://frontend_svc;
