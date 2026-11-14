package uy.edu.prisma.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.PlatformService;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.PlatformServiceRepository;
import uy.edu.prisma.web.dto.Dto.PlatformServiceDto;

/**
 * Lanzador de servicios de la infraestructura (Grafana, Portainer, Keycloak admin...). Solo el
 * Administrador de Plataforma lo ve y solo desde la VPN (ver VpnAccessGuard en el controller). Las
 * URLs dependen de la máquina anfitriona, por eso son editables: una a una o cambiando el host de
 * todas de una vez (conserva esquema, puerto y ruta de cada una).
 */
@Service
@Transactional
public class PlatformServicesService {

  public static final int MAX_URL_LENGTH = 500;

  // Nombre de host (con puntos y guiones), IPv4 o IPv6 entre corchetes. Sin esquema, puerto ni
  // ruta.
  private static final Pattern HOST =
      Pattern.compile(
          "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
              + "|\\[[0-9A-Fa-f:.]+\\])$");

  private final PlatformServiceRepository repo;
  private final AuditLogService auditLog;

  public PlatformServicesService(PlatformServiceRepository repo, AuditLogService auditLog) {
    this.repo = repo;
    this.auditLog = auditLog;
  }

  @Transactional(readOnly = true)
  public List<PlatformServiceDto> list() {
    return repo.findAllByOrderBySortOrderAsc().stream()
        .map(PlatformServicesService::toDto)
        .toList();
  }

  public PlatformServiceDto updateUrl(UUID id, String rawUrl) {
    PlatformService service = find(id);
    String url = validateUrl(rawUrl);
    changeUrl(service, url);
    return toDto(service);
  }

  public PlatformServiceDto reset(UUID id) {
    PlatformService service = find(id);
    changeUrl(service, service.getDefaultUrl());
    return toDto(service);
  }

  /** Cambia el host de todas las URLs (p. ej. de localhost a la IP de la máquina anfitriona). */
  public List<PlatformServiceDto> replaceHost(String rawHost) {
    String host = rawHost == null ? "" : rawHost.trim();
    if (!HOST.matcher(host).matches()) {
      throw new InvalidRequestException(
          "El host debe ser un nombre o una IP sin esquema, puerto ni ruta (ej. 10.8.0.1)");
    }
    List<PlatformService> services = repo.findAllByOrderBySortOrderAsc();
    for (PlatformService service : services) {
      changeUrl(service, withHost(service.getUrl(), host));
    }
    return services.stream().map(PlatformServicesService::toDto).toList();
  }

  private void changeUrl(PlatformService service, String url) {
    if (url.equals(service.getUrl())) {
      return;
    }
    String before = service.getUrl();
    service.setUrl(url);
    service.setUpdatedAt(OffsetDateTime.now());
    repo.save(service);
    auditLog.record(
        "UPDATE",
        "platform-service",
        "{\"service\":\"%s\",\"from\":\"%s\",\"to\":\"%s\"}"
            .formatted(service.getServiceKey(), json(before), json(url)));
  }

  private PlatformService find(UUID id) {
    return repo.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Servicio no encontrado: " + id));
  }

  static String validateUrl(String raw) {
    String url = raw == null ? "" : raw.trim();
    if (url.isEmpty()) {
      throw new InvalidRequestException("La URL es obligatoria");
    }
    if (url.length() > MAX_URL_LENGTH) {
      throw new InvalidRequestException(
          "La URL no puede superar los %d caracteres".formatted(MAX_URL_LENGTH));
    }
    URI uri = parse(url);
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new InvalidRequestException("La URL debe empezar con http:// o https://");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new InvalidRequestException("La URL no tiene un host válido");
    }
    if (uri.getUserInfo() != null) {
      throw new InvalidRequestException("La URL no puede incluir usuario ni contraseña");
    }
    return url;
  }

  static String withHost(String url, String host) {
    URI uri = parse(url);
    StringBuilder out = new StringBuilder(uri.getScheme()).append("://").append(host);
    if (uri.getPort() != -1) {
      out.append(':').append(uri.getPort());
    }
    if (uri.getRawPath() != null) {
      out.append(uri.getRawPath());
    }
    if (uri.getRawQuery() != null) {
      out.append('?').append(uri.getRawQuery());
    }
    if (uri.getRawFragment() != null) {
      out.append('#').append(uri.getRawFragment());
    }
    return out.toString();
  }

  private static URI parse(String url) {
    try {
      return new URI(url);
    } catch (URISyntaxException e) {
      throw new InvalidRequestException("La URL no es válida");
    }
  }

  private static String json(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static PlatformServiceDto toDto(PlatformService s) {
    return new PlatformServiceDto(
        s.getId(),
        s.getServiceKey(),
        s.getName(),
        s.getDescription(),
        s.getCategory(),
        s.getUrl(),
        s.getDefaultUrl());
  }
}
