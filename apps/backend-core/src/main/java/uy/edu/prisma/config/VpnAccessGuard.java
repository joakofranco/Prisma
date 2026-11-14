package uy.edu.prisma.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import uy.edu.prisma.domain.exception.VpnRequiredException;

/**
 * Restringe el lanzador de servicios a quien llega desde la VPN. La IP de origen es
 * {@code getRemoteAddr()}: con {@code server.forward-headers-strategy: framework} Spring la arma a
 * partir del {@code X-Forwarded-For} que fija NGINX (nunca el que manda el cliente). Las redes
 * permitidas salen de {@code prisma.services.allowed-cidrs} (variable {@code
 * SERVICES_ALLOWED_CIDRS}): por defecto loopback y rangos privados, que es donde cae la subred de
 * WireGuard; en producción conviene acotarla a la subred exacta de la VPN.
 */
@Component
public class VpnAccessGuard {

  public static final String DENIED_MESSAGE =
      "Los servicios de la infraestructura solo están disponibles desde la VPN";

  private final List<IpAddressMatcher> allowed;

  public VpnAccessGuard(@Value("${prisma.services.allowed-cidrs:}") List<String> cidrs) {
    this.allowed =
        cidrs.stream()
            .map(String::trim)
            .filter(c -> !c.isEmpty())
            .map(IpAddressMatcher::new)
            .toList();
  }

  public boolean isAllowed(String remoteAddress) {
    if (remoteAddress == null || remoteAddress.isBlank()) {
      return false;
    }
    try {
      return allowed.stream().anyMatch(m -> m.matches(remoteAddress));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public void require(HttpServletRequest request) {
    if (!isAllowed(request.getRemoteAddr())) {
      throw new VpnRequiredException(DENIED_MESSAGE);
    }
  }
}
