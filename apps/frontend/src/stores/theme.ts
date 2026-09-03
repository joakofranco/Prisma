import { defineStore } from 'pinia';
import { ref } from 'vue';

export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'prisma:theme';

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>('light');

  function apply(t: Theme) {
    // Tailwind (ver el "@custom-variant dark" en main.css) activa todas las clases "dark:" según
    // esta clase en <html>, no según prefers-color-scheme -- así el toggle manda por sobre lo que
    // el sistema operativo tenga configurado.
    document.documentElement.classList.toggle('dark', t === 'dark');
  }

  // Se llama una sola vez, antes de montar la app (ver main.ts) -- evita el flash de tema
  // incorrecto que se vería si primero se pintara en claro y recién después, ya montado el
  // componente que lee el store, se corrigiera a oscuro.
  function init() {
    let stored: string | null = null;
    try {
      stored = localStorage.getItem(STORAGE_KEY);
    } catch {
      // Privado/bloqueado: sigue con el default 'light' en vez de romper el arranque de la app.
    }
    const initial: Theme =
      stored === 'dark' || stored === 'light'
        ? stored
        : window.matchMedia?.('(prefers-color-scheme: dark)').matches
          ? 'dark'
          : 'light';
    theme.value = initial;
    apply(initial);
  }

  function setTheme(t: Theme) {
    theme.value = t;
    apply(t);
    try {
      localStorage.setItem(STORAGE_KEY, t);
    } catch {
      // Per-pestaña nomás si el storage no está disponible; no es crítico.
    }
  }

  function toggle() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark');
  }

  return { theme, init, setTheme, toggle };
});
