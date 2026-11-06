// Reglas de carga de evidencias (RF-EVI-02 y RF-EVI-04). La lista de extensiones y el tamaño máximo
// los define el backend (GET /api/evidence/allowed-formats); acá solo se usan para avisar antes de
// enviar el archivo. Quien decide siempre es el servidor: esta verificación se puede saltear.

import { formatFileSize } from '@/utils/helpers';

/** Tipos que el asistente de IA (backend-ai) no lee: no se ofrece «Analizar con IA» para ellos. */
const NOT_ANALYZABLE_TYPES = ['application/zip', 'image/png', 'image/jpeg'];

/** Extensión en minúscula y sin punto ('' si el nombre no tiene). */
export function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.');
  return dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase();
}

/** Valor para el atributo `accept` del campo de archivo: '.pdf,.zip,...'. */
export function acceptAttribute(allowed: string[]): string {
  return allowed.map((e) => `.${e}`).join(',');
}

export function isAllowedExtension(fileName: string, allowed: string[]): boolean {
  return allowed.includes(extensionOf(fileName));
}

/** Mensaje de rechazo por extensión, con la lista vigente. */
export function unsupportedFormatMessage(allowed: string[]): string {
  return `Tipo de archivo no permitido. Formatos aceptados: ${allowed.map((e) => `.${e}`).join(', ')}`;
}

/** Mensaje de rechazo por tamaño, con el límite vigente (por ejemplo «50 MB»). */
export function tooLargeMessage(maxSizeBytes: number): string {
  return `El archivo supera el tamaño máximo permitido de ${formatFileSize(maxSizeBytes)}`;
}

/** `true` si el asistente de IA puede analizar este tipo de archivo. */
export function isAiAnalyzable(fileType: string): boolean {
  return !NOT_ANALYZABLE_TYPES.includes(fileType);
}
