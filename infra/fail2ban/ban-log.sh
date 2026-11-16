#!/bin/sh
ip="$1"
jail="$2"
db=/usr/share/geoip/country.mmdb

case "$jail" in
  prisma-nginx-login)     motivo="Demasiados intentos de login fallidos" ;;
  prisma-nginx-ratelimit) motivo="Exceso de peticiones (limite de tasa, 429)" ;;
  prisma-nginx-scan)      motivo="Exceso de respuestas 401/403/404 (escaneo o sesion vencida)" ;;
  *)                      motivo="Regla $jail" ;;
esac

pais="desconocido"
case "$ip" in
  10.*|127.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*|::1|fe80:*|fc*|fd*)
    pais="red privada" ;;
  *)
    if [ -s "$db" ]; then
      nombre=$(mmdblookup --file "$db" --ip "$ip" country names es 2>/dev/null | sed -n 's/^ *"\(.*\)" <utf8_string>.*/\1/p' | head -n 1)
      codigo=$(mmdblookup --file "$db" --ip "$ip" country iso_code 2>/dev/null | sed -n 's/^ *"\(.*\)" <utf8_string>.*/\1/p' | head -n 1)
      if [ -n "$nombre" ]; then
        pais="$nombre ($codigo)"
      fi
    fi ;;
esac

printf 'BANEO ip=%s pais="%s" jail=%s motivo="%s"\n' "$ip" "$pais" "$jail" "$motivo" > /proc/1/fd/1
