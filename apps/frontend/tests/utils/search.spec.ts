import { describe, it, expect } from 'vitest';
import { matchesQuery, normalizeText } from '../../src/utils/search';

describe('búsqueda en desplegables', () => {
  it('ignora tildes y mayúsculas', () => {
    expect(normalizeText('  Organización ')).toBe('organizacion');
    expect(matchesQuery('Organización Demo', 'organizacion')).toBe(true);
    expect(matchesQuery('Organizacion Demo', 'ORGANIZACIÓN')).toBe(true);
  });

  it('busca en cualquier parte del texto', () => {
    expect(matchesQuery('Gestión de Riesgos', 'riesg')).toBe(true);
    expect(matchesQuery('Gestión de Riesgos', 'ges')).toBe(true);
  });

  it('una consulta vacía o de espacios coincide con todo', () => {
    expect(matchesQuery('Cualquiera', '')).toBe(true);
    expect(matchesQuery('Cualquiera', '   ')).toBe(true);
  });

  it('no coincide si el texto no está', () => {
    expect(matchesQuery('Demo Video', 'prueba')).toBe(false);
  });
});
