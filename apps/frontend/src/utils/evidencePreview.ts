// Vista previa embebida de evidencias (RF-EVI-11): solo PDF e imágenes PNG/JPEG. DOCX, TXT, MD y
// ZIP quedan solo para descargar. SVG no se previsualiza (ni se admite como evidencia): puede
// llevar scripts.

export type PreviewKind = 'pdf' | 'image';

/** Cómo se muestra un archivo de este tipo, o `null` si no tiene vista previa. */
export function previewKind(fileType: string): PreviewKind | null {
  if (fileType === 'application/pdf') return 'pdf';
  if (fileType === 'image/png' || fileType === 'image/jpeg') return 'image';
  return null;
}
