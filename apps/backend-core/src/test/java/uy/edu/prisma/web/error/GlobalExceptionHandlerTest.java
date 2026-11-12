package uy.edu.prisma.web.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.validation.Valid;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import uy.edu.prisma.config.EvidenceConfig.EvidenceProperties;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.MalwareDetectedException;
import uy.edu.prisma.domain.exception.PayloadTooLargeException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.web.dto.Dto.CreateOrganizationDto;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new FixtureController())
            .setControllerAdvice(
                new GlobalExceptionHandler(
                    new EvidenceProperties(List.of("pdf"), DataSize.ofMegabytes(50))))
            .build();
  }

  @Test
  void notFoundMapsTo404() throws Exception {
    mockMvc
        .perform(get("/notfound"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void conflictMapsTo409() throws Exception {
    mockMvc
        .perform(get("/conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("dup"));
  }

  @Test
  void aiUnavailableMapsTo503WithItsOwnCode() throws Exception {
    mockMvc
        .perform(get("/ai-unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
        .andExpect(jsonPath("$.message").value("IA caída"));
  }

  @Test
  void payloadTooLargeMapsTo413WithItsMessage() throws Exception {
    mockMvc
        .perform(get("/too-large"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
        .andExpect(jsonPath("$.message").value("demasiado"));
  }

  @Test
  void malwareDetectedMapsTo422() throws Exception {
    mockMvc
        .perform(get("/malware"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("MALWARE_DETECTED"))
        .andExpect(jsonPath("$.details.signature[0]").value("Eicar"))
        .andExpect(
            jsonPath("$.message")
                .value("El archivo fue rechazado: se detectó contenido malicioso."));
  }

  @Test
  void multipartLimitExceededMapsTo413WithTheConfiguredLimit() throws Exception {
    mockMvc
        .perform(get("/multipart-too-large"))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
        .andExpect(
            jsonPath("$.message").value("El archivo supera el tamaño máximo permitido de 50 MB"));
  }

  @Test
  void invalidRequestMapsTo400() throws Exception {
    mockMvc
        .perform(get("/invalid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void validationErrorReturnsDetails() throws Exception {
    mockMvc
        .perform(post("/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.details.name").isNotEmpty());
  }

  @Test
  void unreadableBodyMapsTo400() throws Exception {
    mockMvc
        .perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void integrityViolationMapsTo409() throws Exception {
    mockMvc
        .perform(get("/integrity"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
  }

  @Test
  void conflictWithAFieldReturnsItInDetails() throws Exception {
    mockMvc
        .perform(get("/conflict-field"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(jsonPath("$.details.rut[0]").value("rut repetido"));
  }

  @Test
  void duplicateRutConstraintViolationGetsAClearMessage() throws Exception {
    // Red de seguridad: dos altas simultáneas con el mismo RUT que se adelantan al chequeo previo.
    mockMvc
        .perform(get("/rut-constraint"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(jsonPath("$.message").value("Ya existe una organización con ese RUT"))
        .andExpect(jsonPath("$.details.rut[0]").value("Ya existe una organización con ese RUT"));
  }

  @Test
  void accessDeniedMapsTo403() throws Exception {
    mockMvc
        .perform(get("/denied"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void authenticationMapsTo401() throws Exception {
    mockMvc
        .perform(get("/unauth"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void illegalArgumentMapsTo400() throws Exception {
    mockMvc
        .perform(get("/illegal"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void genericErrorMapsTo500() throws Exception {
    mockMvc
        .perform(get("/generic"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }

  @RestController
  @Validated
  static class FixtureController {

    @GetMapping("/notfound")
    public String notFound() {
      throw new ResourceNotFoundException("nope");
    }

    @GetMapping("/ai-unavailable")
    public String aiUnavailable() {
      throw new uy.edu.prisma.domain.exception.AiUnavailableException("IA caída", null);
    }

    @GetMapping("/conflict")
    public String conflict() {
      throw new ConflictException("dup");
    }

    @GetMapping("/conflict-field")
    public String conflictField() {
      throw new ConflictException("rut repetido", "rut");
    }

    @GetMapping("/rut-constraint")
    public String rutConstraint() {
      throw new DataIntegrityViolationException(
          "could not execute statement",
          new RuntimeException(
              "ERROR: duplicate key value violates unique constraint \"organizations_rut_key\""));
    }

    @GetMapping("/malware")
    public String malware() {
      throw new MalwareDetectedException("Eicar");
    }

    @GetMapping("/too-large")
    public String tooLarge() {
      throw new PayloadTooLargeException("demasiado");
    }

    @GetMapping("/multipart-too-large")
    public String multipartTooLarge() {
      throw new MaxUploadSizeExceededException(52_428_800L);
    }

    @GetMapping("/invalid")
    public String invalid() {
      throw new InvalidRequestException("bad");
    }

    @GetMapping("/integrity")
    public String integrity() {
      throw new DataIntegrityViolationException("fk");
    }

    @GetMapping("/denied")
    public String denied() {
      throw new AccessDeniedException("denied");
    }

    @GetMapping("/unauth")
    public String unauth() {
      throw new BadCredentialsException("bad");
    }

    @GetMapping("/illegal")
    public String illegal() {
      throw new IllegalArgumentException("bad arg");
    }

    @GetMapping("/generic")
    public String generic() {
      throw new IllegalStateException("boom");
    }

    @PostMapping("/valid")
    public String valid(@Valid @RequestBody CreateOrganizationDto dto) {
      return dto.name();
    }

    @PostMapping("/body")
    public String body(@RequestBody CreateOrganizationDto dto) {
      return dto.name();
    }
  }
}
