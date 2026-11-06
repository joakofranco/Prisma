import { describe, it, expect } from 'vitest';
import { previewKind } from '@/utils/evidencePreview';

describe('previewKind (RF-EVI-11)', () => {
  it('el PDF se muestra en un visor', () => {
    expect(previewKind('application/pdf')).toBe('pdf');
  });

  it('PNG y JPEG se muestran como imagen', () => {
    expect(previewKind('image/png')).toBe('image');
    expect(previewKind('image/jpeg')).toBe('image');
  });

  it('DOCX, TXT, Markdown y ZIP no tienen vista previa', () => {
    for (const type of [
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'text/plain',
      'text/markdown',
      'application/zip',
    ]) {
      expect(previewKind(type)).toBeNull();
    }
  });

  it('SVG no se previsualiza porque puede llevar scripts', () => {
    expect(previewKind('image/svg+xml')).toBeNull();
  });
});
