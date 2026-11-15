import { describe, it, expect } from 'vitest';
import {
  acceptAttribute,
  extensionOf,
  isAiAnalyzable,
  isAllowedExtension,
  tooLargeMessage,
  unsupportedFormatMessage,
} from '@/utils/evidenceFormats';

const ALLOWED = ['pdf', 'zip', 'png'];

describe('evidenceFormats (RF-EVI-02)', () => {
  it('extensionOf devuelve la extensión en minúscula y sin punto', () => {
    expect(extensionOf('Acta.PDF')).toBe('pdf');
    expect(extensionOf('respaldo.tar.zip')).toBe('zip');
    expect(extensionOf('sin-extension')).toBe('');
  });

  it('acceptAttribute arma la lista para el campo de archivo', () => {
    expect(acceptAttribute(ALLOWED)).toBe('.pdf,.zip,.png');
  });

  it('isAllowedExtension compara contra la lista del backend', () => {
    expect(isAllowedExtension('a.PDF', ALLOWED)).toBe(true);
    expect(isAllowedExtension('virus.exe', ALLOWED)).toBe(false);
    expect(isAllowedExtension('sin-extension', ALLOWED)).toBe(false);
    expect(isAllowedExtension('a.svg', ALLOWED)).toBe(false);
  });

  it('el mensaje de rechazo lista los formatos vigentes', () => {
    expect(unsupportedFormatMessage(ALLOWED)).toBe(
      'Tipo de archivo no permitido. Formatos aceptados: .pdf, .zip, .png',
    );
  });

  it('el mensaje de tamaño dice el límite vigente', () => {
    expect(tooLargeMessage(50 * 1024 * 1024)).toBe(
      'El archivo supera el tamaño máximo permitido de 50 MB',
    );
    expect(tooLargeMessage(5 * 1024 * 1024)).toBe(
      'El archivo supera el tamaño máximo permitido de 5 MB',
    );
  });

  it('el ZIP y las imágenes no se analizan con IA; el resto sí', () => {
    for (const type of ['application/zip', 'image/png', 'image/jpeg']) {
      expect(isAiAnalyzable(type)).toBe(false);
    }
    for (const type of ['application/pdf', 'text/plain', 'text/markdown']) {
      expect(isAiAnalyzable(type)).toBe(true);
    }
  });
});
