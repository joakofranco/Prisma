#!/usr/bin/env bash
# =====================================================================
# version.sh — versión de PRISMA (una sola, compartida por todos los servicios)
# ---------------------------------------------------------------------
# La versión vive en cuatro archivos, que tienen que coincidir:
#   apps/backend-core/pom.xml         <version> del proyecto (la publica /api/version)
#   apps/frontend/package.json        "version" (+ package-lock.json; se ve en la web)
#   apps/backend-ai/app/version.py    __version__ (la publica /health)
#   sonar-project.properties          sonar.projectVersion
#
# Uso:
#   scripts/version.sh check          verifica que coincidan (falla si no; lo usa el CI)
#   scripts/version.sh show           muestra la versión de cada uno
#   scripts/version.sh bump 0.7.0     cambia los cuatro a la vez
#
# Convención: MAJOR.MINOR.PATCH (SemVer). Mientras sea 0.x, MINOR sube con cada sprint/entrega y
# PATCH con las correcciones. Un tag `vMAJOR.MINOR.PATCH` despliega a producción y `v...-rcN` a
# staging (ver .github/workflows/deploy.yml): el tag y esta versión deben ser el mismo número.
# =====================================================================
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pom="$root/apps/backend-core/pom.xml"
pkg="$root/apps/frontend/package.json"
ai="$root/apps/backend-ai/app/version.py"
sonar="$root/sonar-project.properties"

# En pom.xml se toma la <version> que sigue al artifactId del proyecto (no la del parent ni la de
# las dependencias).
pom_version() {
  awk '/<artifactId>prisma-backend-core<\/artifactId>/ {getline; gsub(/.*<version>|<\/version>.*/, ""); print; exit}' "$pom" | tr -d '\r'
}
pkg_version() { sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$pkg" | head -1 | tr -d '\r'; }
ai_version() { sed -n 's/^__version__ = "\(.*\)"/\1/p' "$ai" | tr -d '\r'; }
sonar_version() { sed -n 's/^sonar\.projectVersion=\(.*\)/\1/p' "$sonar" | tr -d '\r'; }

show() {
  printf '%-34s %s\n' "apps/backend-core/pom.xml" "$(pom_version)"
  printf '%-34s %s\n' "apps/frontend/package.json" "$(pkg_version)"
  printf '%-34s %s\n' "apps/backend-ai/app/version.py" "$(ai_version)"
  printf '%-34s %s\n' "sonar-project.properties" "$(sonar_version)"
}

check() {
  local versions
  versions="$(printf '%s\n%s\n%s\n%s\n' "$(pom_version)" "$(pkg_version)" "$(ai_version)" "$(sonar_version)" | sort -u)"
  if [ "$(printf '%s\n' "$versions" | wc -l)" -ne 1 ] || [ -z "$versions" ]; then
    echo "ERROR: las versiones no coinciden:" >&2
    show >&2
    echo "Corregilas con: scripts/version.sh bump X.Y.Z" >&2
    exit 1
  fi
  echo "Versión $(pom_version): coincide en los cuatro archivos."
}

bump() {
  local v="${1:-}"
  if ! [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Uso: scripts/version.sh bump X.Y.Z   (por ejemplo 0.7.0)" >&2
    exit 2
  fi
  sed -i "/<artifactId>prisma-backend-core<\/artifactId>/{n;s#<version>[^<]*</version>#<version>$v</version>#}" "$pom"
  (cd "$root/apps/frontend" && npm version "$v" --no-git-tag-version --allow-same-version >/dev/null)
  # npm reescribe el archivo con los fines de línea de la plataforma (CRLF en Windows); el repo usa LF.
  sed -i 's/$//' "$pkg" "$root/apps/frontend/package-lock.json"
  sed -i "s/^__version__ = \".*\"/__version__ = \"$v\"/" "$ai"
  sed -i "s/^sonar\.projectVersion=.*/sonar.projectVersion=$v/" "$sonar"
  check
}

case "${1:-}" in
  check) check ;;
  show) show ;;
  bump) bump "${2:-}" ;;
  *) echo "Uso: scripts/version.sh {check|show|bump X.Y.Z}" >&2; exit 2 ;;
esac
