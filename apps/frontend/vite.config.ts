import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  // Tailwind v4 usa configuración "CSS-first" (`@import 'tailwindcss'` en main.css) y necesita
  // este plugin para generar las clases utilitarias — sin él, Vite solo resuelve el import
  // literal y el CSS final queda casi vacío (nada de flex/grid/spacing/colores), rompiendo el
  // renderizado visual de toda la app aunque el HTML/JS funcionen bien.
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: { host: '0.0.0.0', port: 5173 },
  build: { outDir: 'dist', sourcemap: true },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts', 'src/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      // 'json-summary' es el que necesita davelosert/vitest-coverage-report-action (CI) para
      // publicar el resumen de cobertura en el PR; sin él el job de CI falla al no encontrar
      // coverage/coverage-summary.json.
      reporter: ['text', 'json', 'json-summary', 'html'],
    },
  },
});
