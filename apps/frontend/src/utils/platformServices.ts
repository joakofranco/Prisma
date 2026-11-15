import cadvisor from '@/assets/services/cadvisor.svg';
import elasticsearch from '@/assets/services/elasticsearch.svg';
import grafana from '@/assets/services/grafana.svg';
import keycloak from '@/assets/services/keycloak.svg';
import kibana from '@/assets/services/kibana.svg';
import loki from '@/assets/services/loki.svg';
import ollama from '@/assets/services/ollama.svg';
import pgadmin from '@/assets/services/pgadmin.svg';
import portainer from '@/assets/services/portainer.svg';
import prometheus from '@/assets/services/prometheus.svg';
import rustfs from '@/assets/services/rustfs.svg';
import sonarqube from '@/assets/services/sonarqube.svg';
import type { PlatformService } from '@/types';

/** Ícono de cada software, por la `key` del servicio (los íconos son de simple-icons, CC0). */
export const SERVICE_ICONS: Record<string, string> = {
  cadvisor,
  elasticsearch,
  grafana,
  keycloak,
  kibana,
  loki,
  ollama,
  pgadmin,
  portainer,
  prometheus,
  rustfs,
  sonarqube,
};

/** Categorías en el orden en que se muestran. */
export const SERVICE_CATEGORIES: { key: string; label: string }[] = [
  { key: 'OBSERVABILITY', label: 'Observabilidad' },
  { key: 'INFRASTRUCTURE', label: 'Infraestructura' },
  { key: 'SECURITY', label: 'Seguridad e identidad' },
  { key: 'STORAGE', label: 'Almacenamiento' },
  { key: 'DATA', label: 'Datos' },
  { key: 'QUALITY', label: 'Calidad de código' },
  { key: 'AI', label: 'Inteligencia artificial' },
];

export function categoryLabel(category: string): string {
  return SERVICE_CATEGORIES.find((c) => c.key === category)?.label ?? 'Otros';
}

/** Orden estable: por categoría (en el orden de SERVICE_CATEGORIES) y, dentro, el del servidor. */
export function sortServices(services: PlatformService[]): PlatformService[] {
  const rank = (category: string) => {
    const i = SERVICE_CATEGORIES.findIndex((c) => c.key === category);
    return i === -1 ? SERVICE_CATEGORIES.length : i;
  };
  return services
    .map((s, index) => ({ s, index }))
    .sort((x, y) => rank(x.s.category) - rank(y.s.category) || x.index - y.index)
    .map(({ s }) => s);
}

/** Misma regla que PlatformServicesService (backend): http(s), con host, sin credenciales. */
export function validateServiceUrl(raw: string): string {
  const value = raw.trim();
  if (!value) return 'La URL es obligatoria';
  if (value.length > 500) return 'La URL no puede superar los 500 caracteres';
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return 'Ingresá una URL completa, por ejemplo http://localhost:3000';
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:')
    return 'La URL debe empezar con http:// o https://';
  if (url.username || url.password) return 'La URL no puede incluir usuario ni contraseña';
  return '';
}

/** Un host (nombre o IP) sin esquema, puerto ni ruta. */
export function validateServicesHost(raw: string): string {
  const value = raw.trim();
  if (!value) return 'El host es obligatorio';
  const hostname =
    /^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$/;
  const ipv6 = /^\[[0-9A-Fa-f:.]+\]$/;
  if (!hostname.test(value) && !ipv6.test(value))
    return 'Ingresá solo el nombre o la IP, sin http://, puerto ni ruta (ej. 10.8.0.1)';
  return '';
}
