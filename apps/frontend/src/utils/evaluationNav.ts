/**
 * Elige a qué pregunta saltar al hacer click en una Subcategoría del navegador lateral del
 * asistente de respuestas (EvaluationRespondView). `items` son las preguntas de esa Subcategoría
 * en el orden del catálogo. Devuelve el índice DENTRO de `items`, o -1 si no hay ninguna.
 *
 * - Si queda alguna sin responder (punto blanco en el navegador), la primera de ellas.
 * - Si están todas respondidas (punto verde), la primera que se pregunte (no saltada).
 * - Si TODAS están saltadas, la primera igual (para que el click nunca quede "muerto").
 *
 * `isAnswered` debe incluir las saltadas por el modelo acumulativo (auto-falladas): coincide con
 * el criterio del punto verde, así una Subcategoría "completa" nunca cae en una pregunta a medias.
 */
export function pickEntryIndex<T>(
  items: readonly T[],
  isAnswered: (item: T) => boolean,
  isSkipped: (item: T) => boolean,
): number {
  if (items.length === 0) return -1;
  const firstUnanswered = items.findIndex((item) => !isAnswered(item));
  if (firstUnanswered >= 0) return firstUnanswered;
  const firstAsked = items.findIndex((item) => !isSkipped(item));
  return firstAsked >= 0 ? firstAsked : 0;
}
