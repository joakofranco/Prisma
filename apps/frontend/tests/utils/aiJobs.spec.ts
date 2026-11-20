import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import {
  nextPollDelay,
  isActive,
  jobProgressLabel,
  jobErrorMessage,
  loadStoredJobs,
  saveStoredJobs,
} from '@/utils/aiJobs';
import { installMemoryStorage } from '../helpers/memoryStorage';

describe('aiJobs: espera entre consultas', () => {
  it('arranca en 3 s y crece sin pasar de 8 s', () => {
    expect(nextPollDelay(0)).toBe(3000);
    const delays = Array.from({ length: 12 }, (_, i) => nextPollDelay(i));
    for (let i = 1; i < delays.length; i++) {
      expect(delays[i]).toBeGreaterThanOrEqual(delays[i - 1]);
    }
    expect(delays.at(-1)).toBe(8000);
    expect(Math.max(...delays)).toBe(8000);
  });

  it('un intento negativo se trata como el primero', () => {
    expect(nextPollDelay(-3)).toBe(3000);
  });
});

describe('aiJobs: estado', () => {
  it('solo en cola y corriendo están activos', () => {
    expect(isActive('QUEUED')).toBe(true);
    expect(isActive('RUNNING')).toBe(true);
    for (const s of ['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'] as const) {
      expect(isActive(s)).toBe(false);
    }
    expect(isActive(undefined)).toBe(false);
  });

  it('en cola muestra la posición si hay alguien antes', () => {
    expect(jobProgressLabel({ status: 'QUEUED', queuePosition: 3 })).toBe('En cola (posición 3)…');
    expect(jobProgressLabel({ status: 'QUEUED', queuePosition: 1 })).toBe('En cola…');
    expect(jobProgressLabel({ status: 'RUNNING', queuePosition: null })).toBe(
      'Generando sugerencia…',
    );
  });
});

describe('aiJobs: mensajes de error', () => {
  it('cada código conocido dice qué pasó y cómo seguir', () => {
    expect(jobErrorMessage({ errorCode: 'AI_UNAVAILABLE', errorMessage: 'x' })).toMatch(
      /no está disponible.*Probá de nuevo/,
    );
    expect(jobErrorMessage({ errorCode: 'PROMPT_TOO_LARGE', errorMessage: null })).toMatch(
      /Redactá la acción a mano/,
    );
    expect(jobErrorMessage({ errorCode: 'WORKER_LOST', errorMessage: null })).toMatch(
      /Volvé a pedirla/,
    );
  });

  it('un timeout de la generación tiene su propio mensaje, distinto del de IA no disponible', () => {
    const timeout = jobErrorMessage({ errorCode: 'AI_TIMEOUT', errorMessage: 'x' });
    const unavailable = jobErrorMessage({ errorCode: 'AI_UNAVAILABLE', errorMessage: 'x' });

    expect(timeout).toMatch(/tardó más de lo permitido/);
    expect(timeout).toMatch(/redactá la acción a mano/);
    expect(timeout).not.toBe(unavailable);
  });

  it('un código desconocido usa el mensaje del servidor', () => {
    expect(jobErrorMessage({ errorCode: 'OTRO', errorMessage: 'Algo puntual' })).toBe(
      'Algo puntual',
    );
  });

  it('sin código ni mensaje usa un texto genérico', () => {
    expect(jobErrorMessage({ errorCode: null, errorMessage: null })).toMatch(/Probá de nuevo/);
  });
});

describe('aiJobs: ids persistidos', () => {
  let storage: ReturnType<typeof installMemoryStorage>;
  beforeEach(() => {
    storage = installMemoryStorage();
  });
  afterEach(() => vi.restoreAllMocks());

  it('guarda y recupera por evaluación', () => {
    saveStoredJobs('eval-1', { 'c1:s1': 'job-1' });
    saveStoredJobs('eval-2', { 'c9:s9': 'job-9' });

    expect(loadStoredJobs('eval-1')).toEqual({ 'c1:s1': 'job-1' });
    expect(loadStoredJobs('eval-2')).toEqual({ 'c9:s9': 'job-9' });
    expect(loadStoredJobs('eval-3')).toEqual({});
  });

  it('guardar vacío borra la entrada', () => {
    saveStoredJobs('eval-1', { a: 'j' });
    saveStoredJobs('eval-1', {});

    expect(window.localStorage.getItem('prisma.aiGuideJobs.eval-1')).toBeNull();
  });

  it('un contenido corrupto o de otro tipo se ignora', () => {
    window.localStorage.setItem('prisma.aiGuideJobs.eval-1', '{no es json');
    expect(loadStoredJobs('eval-1')).toEqual({});

    window.localStorage.setItem('prisma.aiGuideJobs.eval-1', '[1,2]');
    expect(loadStoredJobs('eval-1')).toEqual({});

    window.localStorage.setItem('prisma.aiGuideJobs.eval-1', JSON.stringify({ a: 'j', b: 3 }));
    expect(loadStoredJobs('eval-1')).toEqual({ a: 'j' });
  });

  it('si el almacenamiento falla no lanza', () => {
    vi.spyOn(storage, 'getItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });
    vi.spyOn(storage, 'setItem').mockImplementation(() => {
      throw new Error('bloqueado');
    });

    expect(loadStoredJobs('eval-1')).toEqual({});
    expect(() => saveStoredJobs('eval-1', { a: 'j' })).not.toThrow();
  });
});
