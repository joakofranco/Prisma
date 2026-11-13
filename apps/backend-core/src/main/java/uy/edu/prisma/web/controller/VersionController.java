package uy.edu.prisma.web.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.edu.prisma.application.AppVersionService;
import uy.edu.prisma.web.dto.Dto.AppVersionDto;

/**
 * Versión y entorno de la API. Es público (SecurityConfig) porque el frontend lo muestra en la
 * pantalla de login, antes de autenticarse: sin sesión devuelve solo versión y entorno; con sesión
 * suma el commit y la fecha de compilación.
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

  private final AppVersionService service;

  public VersionController(AppVersionService service) {
    this.service = service;
  }

  @GetMapping
  public AppVersionDto version(Authentication authentication) {
    // Sin sesión Spring no resuelve el Authentication (llega null); un anónimo explícito también
    // cuenta como sin sesión.
    boolean authenticated =
        authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    return authenticated ? service.fullInfo() : service.publicInfo();
  }
}
