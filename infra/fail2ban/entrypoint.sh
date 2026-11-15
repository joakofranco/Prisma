#!/bin/sh
set -eu

LOG=/logs/access.log
MAX_BYTES="${F2B_LOG_MAX_BYTES:-52428800}"

touch "$LOG" /banlist/f2b.conf

sed -i "s|^ignoreip.*|ignoreip = ${F2B_IGNORE_IPS:-127.0.0.1/8 ::1}|" /etc/fail2ban/jail.local

(
  while true; do
    sleep 3600
    size=$(wc -c < "$LOG" 2>/dev/null || echo 0)
    if [ "$size" -gt "$MAX_BYTES" ]; then
      : > "$LOG"
    fi
  done
) &

rm -f /var/run/fail2ban/fail2ban.sock /var/run/fail2ban/fail2ban.pid
exec fail2ban-server -xf start
