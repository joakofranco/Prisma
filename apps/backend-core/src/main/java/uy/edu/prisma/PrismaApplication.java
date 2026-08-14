package uy.edu.prisma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PRISMA — Plataforma de Revisión Integral de Seguridad y Marcos de Auditorías.
 *
 * <p>Punto de entrada de la aplicación Spring Boot.
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
// Habilita @Scheduled -- lo usa LoginFailureAuditSyncService para traer periódicamente a la
// bitácora propia los intentos de login fallidos que Keycloak ya audita solo.
@EnableScheduling
public class PrismaApplication {

  public static void main(String[] args) {
    SpringApplication.run(PrismaApplication.class, args);
  }
}
