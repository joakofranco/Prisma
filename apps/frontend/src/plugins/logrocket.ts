// Monitoreo de frontend: session replay, errores JS y performance real de usuario. Se importa
// una sola vez por efecto secundario (ver main.ts), igual que ./chartjs.
//
// Es un SaaS de terceros (logrocket.com), no autohosteado: sin VITE_LOGROCKET_APP_ID configurado
// (hace falta una cuenta propia) queda completamente apagado. El import del SDK es dinámico a
// propósito -- sin App ID, initLogRocket() no descarga ni ejecuta una sola línea de la librería
// (~18kB gzip), no sólo la deja inicializada sin hacer nada.
const APP_ID = import.meta.env.VITE_LOGROCKET_APP_ID as string | undefined;

export async function initLogRocket(): Promise<void> {
  if (!APP_ID) return;

  const { default: LogRocket } = await import('logrocket');

  LogRocket.init(APP_ID, {
    dom: {
      // PRISMA maneja evidencia de auditorías de seguridad y datos de organizaciones -- se
      // redactan por defecto los valores de TODOS los <input>/<textarea> en el replay (quedan
      // como "•••"), no sólo los de tipo password. Mejor pecar de conservador acá.
      inputSanitizer: true,
    },
    network: {
      // El bearer token de Keycloak viaja en este header en cada request -- nunca debe llegar a
      // LogRocket, sea cual sea el endpoint.
      requestSanitizer: (request) => {
        if (request.headers?.Authorization) {
          request.headers.Authorization = '[REDACTED]';
        }
        return request;
      },
      // Las respuestas de evidencias y del catálogo pueden traer contenido sensible de la
      // auditoría en el body -- se graba que la request pasó (status/timing) pero no su
      // contenido.
      responseSanitizer: (response) => {
        if (response.url?.includes('/evidence') || response.url?.includes('/catalog')) {
          response.body = undefined;
        }
        return response;
      },
    },
  });
}

/**
 * Asocia la sesión de LogRocket ya iniciada con el usuario autenticado, para poder buscar/filtrar
 * sesiones por usuario o rol en el dashboard de LogRocket. Llamar después de un login exitoso
 * (ver stores/auth.ts) -- no-op si LogRocket está apagado.
 */
export async function identifyLogRocketUser(user: {
  id: string;
  email: string;
  roles: readonly string[];
}): Promise<void> {
  if (!APP_ID) return;

  const { default: LogRocket } = await import('logrocket');

  LogRocket.identify(user.id, {
    email: user.email,
    roles: user.roles.join(','),
  });
}
