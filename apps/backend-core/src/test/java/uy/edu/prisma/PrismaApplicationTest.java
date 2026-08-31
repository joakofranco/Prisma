package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PrismaApplicationTest {

  @Test
  void mainFailsFastWithoutDatabase() {
    assertThrows(
        Exception.class,
        () ->
            PrismaApplication.main(
                new String[] {
                  "--spring.main.web-application-type=none",
                  "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none",
                  "--spring.datasource.username=x",
                  "--spring.datasource.password=x",
                  "--spring.datasource.hikari.connection-timeout=500",
                  "--spring.security.oauth2.resourceserver.jwt.issuer-uri=",
                  "--logging.level.root=OFF"
                }));
  }
}
