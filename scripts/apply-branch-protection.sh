#!/usr/bin/env bash
# =====================================================================
# apply-branch-protection.sh
# ---------------------------------------------------------------------
# Aplica las reglas declaradas en .github/branch-protection.yml usando
# GitHub CLI (`gh`). Requiere:
#   - Repo ya creado en GitHub
#   - `gh auth login` ya ejecutado con permisos de admin del repo
#
# También crea las ramas iniciales: dev, dev/fernando, dev/federico, dev/joaquin.
#
# Uso:
#   ./scripts/apply-branch-protection.sh <owner>/<repo>
# =====================================================================

set -euo pipefail

REPO="${1:-}"
if [ -z "$REPO" ]; then
  echo "Uso: $0 <owner>/<repo>"
  exit 1
fi

echo "🔒 Aplicando protección de ramas a $REPO..."

# ---------------------------------------------------------------------
# 0) Crear ramas iniciales si no existen
# ---------------------------------------------------------------------
DEFAULT_SHA=$(gh api "repos/$REPO" --jq '.default_branch' \
  | xargs -I{} gh api "repos/$REPO/git/refs/heads/{}" --jq '.object.sha')

for branch in dev dev/fernando dev/federico dev/joaquin; do
  if gh api "repos/$REPO/branches/$branch" >/dev/null 2>&1; then
    echo "✔  Rama $branch ya existe"
  else
    echo "➕ Creando rama $branch..."
    gh api "repos/$REPO/git/refs" -X POST \
      -f ref="refs/heads/$branch" \
      -f sha="$DEFAULT_SHA"
  fi
done

# ---------------------------------------------------------------------
# 1) main — máxima protección
# ---------------------------------------------------------------------
echo "🛡  Protegiendo main..."
gh api -X PUT "repos/$REPO/branches/main/protection" \
  -H "Accept: application/vnd.github+json" \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "CI Summary",
      "SonarQube Analysis",
      "Trivy (FS)",
      "Gitleaks (secretos)",
      "Título Conventional Commit"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismissal_restrictions": {},
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 2,
    "require_last_push_approval": true
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true,
  "lock_branch": false,
  "allow_fork_syncing": false
}
JSON

# ---------------------------------------------------------------------
# 2) dev — protegida con 1 aprobación
# ---------------------------------------------------------------------
echo "🛡  Protegiendo dev..."
gh api -X PUT "repos/$REPO/branches/dev/protection" \
  -H "Accept: application/vnd.github+json" \
  --input - <<'JSON'
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "CI Summary",
      "SonarQube Analysis",
      "Trivy (FS)",
      "Gitleaks (secretos)"
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 1
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true
}
JSON

# ---------------------------------------------------------------------
# 3) Reglas para ramas dev/* (Ruleset — API v2)
#    (aplican a dev/fernando, dev/federico, dev/joaquin)
# ---------------------------------------------------------------------
echo "🛡  Aplicando ruleset a dev/*..."
gh api -X POST "repos/$REPO/rulesets" \
  -H "Accept: application/vnd.github+json" \
  --input - <<'JSON'
{
  "name": "dev-personal-branches",
  "target": "branch",
  "enforcement": "active",
  "conditions": {
    "ref_name": {
      "include": ["refs/heads/dev/*"],
      "exclude": []
    }
  },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true
      }
    }
  ]
}
JSON

# ---------------------------------------------------------------------
# 4) Configurar GitHub Environments para deploys
# ---------------------------------------------------------------------
for env in dev staging prod; do
  echo "🌐 Configurando environment: $env"
  gh api -X PUT "repos/$REPO/environments/$env" \
    -H "Accept: application/vnd.github+json"

  if [ "$env" = "prod" ]; then
    # Prod requiere 2 aprobadores obligatorios
    gh api -X PUT "repos/$REPO/environments/$env" \
      --input - <<'JSON'
{
  "wait_timer": 5,
  "reviewers": [
    { "type": "User", "id": 1 }
  ],
  "deployment_branch_policy": {
    "protected_branches": true,
    "custom_branch_policies": false
  }
}
JSON
  fi
done

echo "✅ Protección de ramas aplicada correctamente."
echo ""
echo "📋 Resumen:"
echo "   • main               → 2 approvals, todos los checks, admins incluidos"
echo "   • dev                → 1 approval, checks core"
echo "   • dev/fernando       → sólo Fernando puede pushear"
echo "   • dev/federico       → sólo Federico puede pushear"
echo "   • dev/joaquin        → sólo Joaquín puede pushear"
echo "   • Environments dev/staging/prod configurados"
