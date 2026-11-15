FROM alpine:3.20

RUN apk add --no-cache fail2ban curl tzdata \
 && rm -f /etc/fail2ban/jail.d/alpine-ssh.conf \
 && mkdir -p /var/run/fail2ban /data /banlist /logs

COPY fail2ban.local /etc/fail2ban/fail2ban.local
COPY jail.local /etc/fail2ban/jail.local
COPY filter.d/ /etc/fail2ban/filter.d/
COPY action.d/ /etc/fail2ban/action.d/
COPY nginx-reload.sh /usr/local/bin/nginx-reload.sh
COPY entrypoint.sh /entrypoint.sh
RUN sed -i 's/\r$//' /entrypoint.sh /usr/local/bin/nginx-reload.sh \
 && chmod +x /entrypoint.sh /usr/local/bin/nginx-reload.sh

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD fail2ban-client ping || exit 1

ENTRYPOINT ["/entrypoint.sh"]
