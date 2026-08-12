# 📋 Descripción

<!-- Explicá QUÉ hace este PR y POR QUÉ. Enlazá la issue relacionada. -->

Closes #

## 🎯 Tipo de cambio

- [ ] 🆕 `feat` — Nueva funcionalidad
- [ ] 🐛 `fix` — Corrección de bug
- [ ] 🚑 `hotfix` — Corrección urgente en producción
- [ ] 📚 `docs` — Cambios sólo en documentación
- [ ] ♻️ `refactor` — Refactor sin cambio funcional
- [ ] ✅ `test` — Añade o corrige tests
- [ ] 🎨 `style` — Formato, sin cambio de lógica
- [ ] 🔧 `chore` — Mantenimiento, deps, config
- [ ] 🚀 `perf` — Mejora de rendimiento

## 📌 Áreas afectadas

- [ ] Frontend (Vue 3)
- [ ] Backend Core (Spring Boot)
- [ ] Backend AI (FastAPI)
- [ ] Base de datos / migraciones
- [ ] Infra / Docker / K8s
- [ ] CI/CD
- [ ] Documentación

## ✅ Checklist

- [ ] Mi código sigue las convenciones del proyecto (`make lint` pasa)
- [ ] Añadí tests que prueban mi cambio (cobertura ≥ 80%, 100% en motor de cálculo)
- [ ] Los tests existentes siguen pasando (`make test`)
- [ ] Actualicé la documentación afectada
- [ ] No introduje secretos, claves ni credenciales en el código
- [ ] Verifiqué que no rompe el multitenancy (aislamiento por `tenant_id`)
- [ ] Verifiqué OWASP Top 10 en cambios relevantes (BOLA/IDOR, validación de inputs)
- [ ] Si toca UI, incluyo capturas (mobile + desktop)
- [ ] El PR tiene título que sigue [Conventional Commits](https://www.conventionalcommits.org/)

## 🖼️ Capturas / Evidencias

<!-- Adjuntá capturas, GIFs o enlaces a videos si aplica. -->

## 🧪 ¿Cómo probarlo?

<!-- Pasos concretos para que el reviewer valide localmente. -->

```bash
git checkout <esta-rama>
make up
# ...
```

## 🔗 Referencias

<!-- Enlaces a docs, tickets Bugasura, RFCs, Notion, etc. -->
