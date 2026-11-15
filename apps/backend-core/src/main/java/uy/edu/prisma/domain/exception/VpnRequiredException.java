package uy.edu.prisma.domain.exception;

import org.springframework.security.access.AccessDeniedException;

/** El recurso solo se sirve a quien llega desde la VPN (ver VpnAccessGuard). */
public class VpnRequiredException extends AccessDeniedException {

  public VpnRequiredException(String message) {
    super(message);
  }
}
