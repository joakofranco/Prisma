import AxeBuilder from '@axe-core/playwright';
import type { Page, TestInfo } from '@playwright/test';
import { expect } from '@playwright/test';

/**
 * Nivel de conformidad exigido a los sitios del Estado uruguayo por el Decreto N° 406/022 (dic.
 * 2022): accesibilidad digital según la normativa técnica de AGESIC, equivalente a WCAG 2.1
 * niveles A y AA (https://www.impo.com.uy/bases/decretos-originales/406-2022). PRISMA es una
 * herramienta de AGESIC (Marco de Ciberseguridad v5.0), así que aplica igual.
 *
 * Se pide "2.1" explícito (no sólo "2.0") porque los tags de axe-core no son acumulativos entre
 * versiones mayores: wcag21aa cubre únicamente los criterios NUEVOS de 2.1, hay que sumar los de
 * 2.0 aparte.
 */
export const WCAG_UY_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] as const;

/**
 * Corre axe-core sobre la página actual y falla el test con un detalle legible (regla, impacto,
 * elementos afectados y ayuda) en vez del JSON crudo de axe si encuentra violaciones dentro del
 * alcance WCAG 2.1 AA exigido por el Decreto N° 406/022.
 */
export async function assertNoWcagViolations(page: Page, testInfo: TestInfo): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags([...WCAG_UY_TAGS]).analyze();

  if (results.violations.length > 0) {
    await testInfo.attach('axe-violations.json', {
      body: JSON.stringify(results.violations, null, 2),
      contentType: 'application/json',
    });
  }

  const report = results.violations
    .map((violation) => {
      const targets = violation.nodes.map((node) => `    - ${node.target.join(' ')}`).join('\n');
      return (
        `[${violation.impact ?? 'desconocido'}] ${violation.id}: ${violation.help}\n` +
        `  ${violation.helpUrl}\n${targets}`
      );
    })
    .join('\n\n');

  expect(results.violations, `Violaciones WCAG 2.1 AA encontradas:\n\n${report}`).toEqual([]);
}
