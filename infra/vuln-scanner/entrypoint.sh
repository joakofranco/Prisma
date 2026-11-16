#!/usr/bin/env bash
set -uo pipefail

HORA="${VULN_HORA:-03:00}"
AL_INICIAR="${VULN_ESCANEAR_AL_INICIAR:-true}"

if ! [[ "$HORA" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]]; then
  echo "VULN_HORA debe tener el formato HH:MM (recibí '$HORA')." >&2
  exit 1
fi

escanear() {
  echo "== $(date '+%Y-%m-%d %H:%M:%S') escaneo de vulnerabilidades"
  /opt/prisma/scripts/vuln-scan.sh || echo "El escaneo terminó con error; se reintenta en el próximo horario."
}

segundos_hasta_la_hora() {
  local ahora objetivo
  ahora="$(date +%s)"
  objetivo="$(date -d "$(date +%Y-%m-%d) $HORA:00" +%s)"
  [ "$objetivo" -le "$ahora" ] && objetivo=$((objetivo + 86400))
  echo $((objetivo - ahora))
}

echo "Escaneo de vulnerabilidades programado todos los días a las $HORA (zona horaria ${TZ:-UTC})."
if [ "$AL_INICIAR" = "true" ]; then
  sleep 60
  escanear
fi

while true; do
  espera="$(segundos_hasta_la_hora)"
  echo "Próximo escaneo en $((espera / 3600)) h $(((espera % 3600) / 60)) min."
  sleep "$espera"
  escanear
done
