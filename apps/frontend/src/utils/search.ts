// Búsqueda dentro de los desplegables (BaseSelect / BaseMultiSelect): ignora tildes y mayúsculas,
// así "organizacion" encuentra "Organización".

// Quita diacríticos (tildes) tras normalizar a NFD.
const DIACRITICS = new RegExp('[\u0300-\u036f]', 'g');

export function normalizeText(text: string): string {
  return text.normalize('NFD').replace(DIACRITICS, '').toLowerCase().trim();
}

/** true si `label` contiene `query` (sin distinguir tildes ni mayúsculas); una consulta vacía coincide. */
export function matchesQuery(label: string, query: string): boolean {
  const q = normalizeText(query);
  return q === '' || normalizeText(label).includes(q);
}
