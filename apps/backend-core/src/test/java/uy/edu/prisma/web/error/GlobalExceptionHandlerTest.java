package uy.edu.prisma.web.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.web.dto.Dto.CreateOrganizationDto;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new FixtureController())
            .setControllerAdvice(new GlobalExceptionHandler())
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

    @GetMapping("/conflict")
    public String conflict() {
      throw new ConflictException("dup");
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
