// Regenera prisma-logo-email.png a partir del logo real de la plataforma
// (apps/frontend/src/assets/img/prisma-logo-dark.svg -- la variante de texto blanco, pensada para
// fondos oscuros como la cabecera del correo) y vuelca su base64 a stdout, listo para pegar en el
// <img src="data:image/png;base64,..."> de email.tmpl.
//
// No se usa SVG embebido directamente: Outlook de escritorio (motor de Word) no renderiza
// `<img src="data:image/svg+xml...">`, así que se rasteriza a PNG una vez acá, con Playwright
// (ya es dependencia de apps/frontend para los tests e2e), en vez de depender de un conversor de
// SVG aparte.
//
// Uso (desde la raíz del repo):
//   cd apps/frontend && NODE_PATH=$PWD/node_modules node \
//     ../../infra/monitoring/alertmanager/templates/render-logo.cjs
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const SVG_PATH = path.join(ROOT, 'apps/frontend/src/assets/img/prisma-logo-dark.svg');
const OUT_PNG = path.join(__dirname, 'prisma-logo-email.png');

(async () => {
  const svg = fs.readFileSync(SVG_PATH, 'utf8');
  const W = 360;
  const H = 124; // 2x retina; proporción cercana al viewBox 590x205 del logo original.
  const html = `<!doctype html><html><head><style>
    html,body{margin:0;padding:0;background:#0f172a;}
    .wrap{width:${W}px;height:${H}px;display:flex;align-items:center;justify-content:flex-start;box-sizing:border-box;}
    svg{height:${H - 16}px;width:auto;display:block;}
  </style></head><body><div class="wrap">${svg}</div></body></html>`;
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  await page.setContent(html);
  await page.screenshot({ path: OUT_PNG });
  await browser.close();
  const base64 = fs.readFileSync(OUT_PNG).toString('base64');
  console.log(`Logo escrito en ${OUT_PNG} (${base64.length} caracteres en base64).`);
  console.log('Pegar en email.tmpl: {{ define "prisma.logo" }}' + base64 + '{{ end }}');
})();
