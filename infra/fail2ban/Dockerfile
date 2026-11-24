FROM alpine:3.20

ARG DBIP_MES=2026-11
SHELL ["/bin/ash", "-o", "pipefail", "-c"]
RUN apk add --no-cache fail2ban curl tzdata libmaxminddb \
 && mkdir -p /usr/share/geoip \
 && { curl -fsSL "https://download.db-ip.com/free/dbip-country-lite-${DBIP_MES}.mmdb.gz" | gunzip > /usr/share/geoip/country.mmdb \
      || { echo "AVISO: no se pudo descargar la base de paises; los baneos se registran sin pais" >&2; rm -f /usr/share/geoip/country.mmdb; }; } \
 && rm -f /etc/fail2ban/jail.d/alpine-ssh.conf \
 && mkdir -p /var/run/fail2ban /data /banlist /logs

COPY fail2ban.local /etc/fail2ban/fail2ban.local
COPY jail.local /etc/fail2ban/jail.local
COPY filter.d/ /etc/fail2ban/filter.d/
COPY action.d/ /etc/fail2ban/action.d/
COPY nginx-reload.sh /usr/local/bin/nginx-reload.sh
COPY ban-log.sh /usr/local/bin/ban-log.sh
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh /usr/local/bin/nginx-reload.sh /usr/local/bin/ban-log.sh \
 && chmod +x /entrypoint.sh /usr/local/bin/nginx-reload.sh /usr/local/bin/ban-log.sh

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD fail2ban-client ping || exit 1

ENTRYPOINT ["/entrypoint.sh"]
