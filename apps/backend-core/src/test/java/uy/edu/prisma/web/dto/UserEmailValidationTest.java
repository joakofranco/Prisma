package uy.edu.prisma.web.dto;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uy.edu.prisma.web.dto.Dto.CreateUserDto;

/** C3: un email inválido se rechaza con 400 y mensaje específico, no con "error inesperado". */
class UserEmailValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static Set<ConstraintViolation<CreateUserDto>> validate(String email) {
    return validator.validate(
        new CreateUserDto(email, "Ana", "Perez", null, Set.of("VIEWER"), "Passw0rd!"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"ana@prisma.local", "ana.perez+tag@sub.dominio.com.uy", "a@b.c", "ANA@Test.COM"})
  void acceptsWellFormedEmails(String email) {
    assertTrue(validate(email).isEmpty(), email);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sin-arroba",
        "ana@",
        "@dominio.com",
        "ana@dominio",
        "ana@@dominio.com",
        "a b@c.com"
      })
  void rejectsMalformedEmailsWithSpecificMessage(String email) {
    Set<ConstraintViolation<CreateUserDto>> violations = validate(email);
    assertFalse(violations.isEmpty(), email);
    assertTrue(
        violations.stream().allMatch(v -> v.getPropertyPath().toString().equals("email")), email);
    assertTrue(
        violations.stream().anyMatch(v -> v.getMessage().startsWith("El email no es válido")),
        email);
  }

  @Test
  void rejectsBlankAndTooLongEmails() {
    assertFalse(validate("").isEmpty());
    assertFalse(validate("   ").isEmpty());
    assertFalse(validate("a".repeat(250) + "@b.com").isEmpty());
  }

  @Test
  void trimsSurroundingWhitespace() {
    CreateUserDto dto =
        new CreateUserDto("  ana@prisma.local ", "Ana", "Perez", null, Set.of(), "Passw0rd!");
    assertEquals("ana@prisma.local", dto.email());
    assertTrue(validator.validate(dto).isEmpty());
  }
}
