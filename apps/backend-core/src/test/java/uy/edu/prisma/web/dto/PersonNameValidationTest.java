package uy.edu.prisma.web.dto;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uy.edu.prisma.web.dto.Dto.CreateUserDto;
import uy.edu.prisma.web.dto.Dto.UpdateProfileDto;

/** C2: nombre y apellido se validan por separado y exigen al menos una letra. */
class PersonNameValidationTest {

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

  private static Set<String> invalidFields(Object dto) {
    return validator.validate(dto).stream()
        .map(v -> v.getPropertyPath().toString())
        .collect(Collectors.toSet());
  }

  private static CreateUserDto user(String firstName, String lastName) {
    return new CreateUserDto(
        "a@test.com", firstName, lastName, null, Set.of("VIEWER"), "Passw0rd!");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Ana",
        "María José",
        "O'Brien",
        "O’Brien",
        "Pérez-Gómez",
        "De la Cruz",
        "Ñandú",
        "Jr.",
        "Carlos 4",
        "Carlos IV",
        "4 Carlos"
      })
  void acceptsNamesWithLettersEvenIfTheyContainDigits(String name) {
    assertTrue(validator.validate(user(name, name)).isEmpty(), name);
    assertTrue(validator.validate(new UpdateProfileDto(name, name)).isEmpty(), name);
  }

  @ParameterizedTest
  @ValueSource(strings = {"12345", "4", "---", "@#$%", "1 2 3", "' '", "Ana_Maria", "Ana<script>"})
  void rejectsNamesWithoutLetterOrWithForbiddenCharacters(String name) {
    assertEquals(Set.of("firstName"), invalidFields(user(name, "Perez")), name);
    assertEquals(Set.of("lastName"), invalidFields(user("Ana", name)), name);
    assertEquals(Set.of("firstName"), invalidFields(new UpdateProfileDto(name, "Perez")), name);
  }

  @Test
  void fieldsAreValidatedIndependently() {
    assertEquals(Set.of("firstName", "lastName"), invalidFields(user("123", "456")));
    assertEquals(Set.of("lastName"), invalidFields(user("Ana", "456")));
  }

  @Test
  void rejectsBlankTooShortAndTooLong() {
    assertTrue(invalidFields(user("", "Perez")).contains("firstName"));
    assertTrue(invalidFields(user("   ", "Perez")).contains("firstName"));
    assertTrue(invalidFields(user("A", "Perez")).contains("firstName"));
    assertTrue(invalidFields(user("A".repeat(101), "Perez")).contains("firstName"));
    assertTrue(invalidFields(user("A".repeat(100), "Perez")).isEmpty());
  }

  @Test
  void trimsSurroundingWhitespace() {
    CreateUserDto dto = user("  Ana ", " Perez  ");
    assertEquals("Ana", dto.firstName());
    assertEquals("Perez", dto.lastName());
    assertTrue(validator.validate(dto).isEmpty());

    UpdateProfileDto profile = new UpdateProfileDto("  Ana ", " Perez  ");
    assertEquals("Ana", profile.firstName());
    assertEquals("Perez", profile.lastName());
  }
}
