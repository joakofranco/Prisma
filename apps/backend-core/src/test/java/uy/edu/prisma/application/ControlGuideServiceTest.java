package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogRequirement;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.PayloadTooLargeException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.web.dto.Dto.ControlGuideImportResultDto;
import uy.edu.prisma.web.dto.Dto.ControlGuideItemDto;
import uy.edu.prisma.web.dto.Dto.SaveControlGuideDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ControlGuideServiceTest {

  @Mock private CatalogControlRepository controlRepo;
  @Mock private AuditLogService auditLog;

  private ControlGuideService service;
  private CatalogControl first;
  private CatalogControl second;

  @BeforeEach
  void setUp() {
    service = new ControlGuideService(controlRepo, auditLog);
    CatalogRequirement requirement =
        CatalogRequirement.builder().id(UUID.randomUUID()).code("PS.1").build();
    first = control("PS.1-1", requirement);
    second = control("PS.1-2", requirement);
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(first, second));
    when(controlRepo.findById(first.getId())).thenReturn(Optional.of(first));
  }

  private static CatalogControl control(String code, CatalogRequirement requirement) {
    return CatalogControl.builder()
        .id(UUID.randomUUID())
        .code(code)
        .description("Descripción de " + code)
        .targetLevel(1)
        .requirement(requirement)
        .build();
  }

  private static MockMultipartFile file(String name, String content) {
    return new MockMultipartFile(
        "file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void importsAMarkdownGuideAndMapsItByControlCode() {
    String markdown =
        "#### PS.1-1 (Nivel 1)\n\n**Interpretación:** I1\n\n**Ejemplo de aplicación:** E1\n\n"
            + "**Evidencia a considerar:** V1\n\n#### PS.9-9 (Nivel 1)\n\n**Interpretación:** I\n\n"
            + "**Ejemplo de aplicación:** E\n\n**Evidencia a considerar:** V\n\n"
            + "#### PS.1-2 (Nivel 1)\n\n**Interpretación:** solo esta\n";

    ControlGuideImportResultDto result = service.importGuide("5.0", file("guia.md", markdown));

    assertEquals(3, result.parsed());
    assertEquals(1, result.updated());
    assertEquals(List.of("PS.9-9"), result.notInCatalog());
    assertEquals(List.of("PS.1-2"), result.incomplete());
    assertEquals(1, result.catalogWithoutGuide());
    assertEquals("I1", first.getGuideInterpretation());
    assertEquals("E1", first.getGuideExample());
    assertEquals("V1", first.getGuideEvidence());
    assertNull(second.getGuideInterpretation());
    verify(auditLog).record(eq("IMPORT_GUIDE"), any(), any());
  }

  @Test
  void importsACsvWithAccentedHeadersAndSemicolons() {
    String csv =
        "Código;Nivel;Control;Interpretación;Ejemplo de aplicación;Evidencia a considerar\r\n"
            + "PS.1-2;1;x;\"Texto; con punto y coma\";Ejemplo;Evidencia\r\n";

    ControlGuideImportResultDto result = service.importGuide("5.0", file("guia.csv", csv));

    assertEquals(1, result.updated());
    assertEquals("Texto; con punto y coma", second.getGuideInterpretation());
  }

  @Test
  void importsACsvSeparatedByCommas() {
    String csv = "codigo,interpretacion,ejemplo,evidencia\nPS.1-1,I,E,V\n";

    assertEquals(1, service.importGuide("5.0", file("guia.csv", csv)).updated());
    assertEquals("V", first.getGuideEvidence());
  }

  @Test
  void aCsvWithoutTheCodeColumnIsRejected() {
    assertThrows(
        InvalidRequestException.class,
        () -> service.importGuide("5.0", file("guia.csv", "a;b\n1;2\n")));
  }

  @Test
  void rejectsUnsupportedEmptyAndOversizedFiles() {
    assertThrows(
        InvalidRequestException.class, () -> service.importGuide("5.0", file("guia.xlsx", "x")));
    assertThrows(
        InvalidRequestException.class, () -> service.importGuide("5.0", file("guia.csv", "")));
    String big = "x".repeat((int) ControlGuideService.MAX_BYTES + 1);
    assertThrows(
        PayloadTooLargeException.class, () -> service.importGuide("5.0", file("guia.md", big)));
  }

  @Test
  void importFailsForAnUnknownVersion() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.importGuide("9.9", file("guia.md", "#### PS.1-1 (Nivel 1)")));
  }

  @Test
  void exportListsEveryControlEvenWithoutGuide() {
    first.setGuideInterpretation("I1");
    first.setGuideExample("E1");
    first.setGuideEvidence("V1");

    String csv = service.exportCsv("5.0");

    assertTrue(csv.startsWith("﻿\"codigo\";\"nivel\";\"control\""));
    assertTrue(csv.contains("\"PS.1-1\";\"1\";\"Descripción de PS.1-1\";\"I1\";\"E1\";\"V1\""));
    assertTrue(csv.contains("\"PS.1-2\";\"1\";\"Descripción de PS.1-2\";\"\";\"\";\"\""));
  }

  @Test
  void anExportedCsvCanBeImportedBack() {
    first.setGuideInterpretation("I1");
    first.setGuideExample("E1");
    first.setGuideEvidence("V1");
    String csv = service.exportCsv("5.0");
    first.setGuideInterpretation(null);

    ControlGuideImportResultDto result = service.importGuide("5.0", file("guia.csv", csv));

    assertEquals(1, result.updated());
    assertEquals(1, result.incomplete().size());
    assertEquals("I1", first.getGuideInterpretation());
  }

  @Test
  void listMapsEveryControl() {
    List<ControlGuideItemDto> items = service.list("5.0");

    assertEquals(2, items.size());
    assertEquals("PS.1-1", items.get(0).code());
    assertEquals("PS.1", items.get(0).requirementCode());
    assertNull(items.get(0).interpretation());
  }

  @Test
  void summaryCountsTheControlsWithGuide() {
    first.setGuideInterpretation("I");

    assertEquals(2, service.summary("5.0").total());
    assertEquals(1, service.summary("5.0").withGuide());
  }

  @Test
  void saveStoresTheThreeSectionsTrimmed() {
    ControlGuideItemDto dto =
        service.save(first.getId(), new SaveControlGuideDto("  I  ", " E ", " V "));

    assertEquals("I", dto.interpretation());
    assertEquals("E", first.getGuideExample());
    verify(controlRepo).save(first);
    verify(auditLog).record(eq("UPDATE_GUIDE"), any(), any());
  }

  @Test
  void saveWithAllSectionsBlankClearsTheGuide() {
    first.setGuideInterpretation("I");

    ControlGuideItemDto dto = service.save(first.getId(), new SaveControlGuideDto(" ", null, ""));

    assertNull(dto.interpretation());
    assertNull(first.getGuideInterpretation());
    verify(auditLog).record(eq("DELETE_GUIDE"), any(), any());
  }

  @Test
  void saveWithSomeSectionsMissingIsRejected() {
    assertThrows(
        InvalidRequestException.class,
        () -> service.save(first.getId(), new SaveControlGuideDto("I", "", "V")));
    verify(controlRepo, never()).save(any());
  }

  @Test
  void saveFailsForAnUnknownControl() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.save(UUID.randomUUID(), new SaveControlGuideDto("I", "E", "V")));
  }

  @Test
  void deleteClearsOnlyTheControlsThatHadAGuide() {
    first.setGuideInterpretation("I");
    first.setGuideExample("E");
    first.setGuideEvidence("V");
    when(controlRepo.findAllById(List.of(first.getId(), second.getId())))
        .thenReturn(List.of(first, second));

    int removed = service.delete(List.of(first.getId(), second.getId()));

    assertEquals(1, removed);
    assertNull(first.getGuideInterpretation());
    assertNull(first.getGuideExample());
    assertNull(first.getGuideEvidence());
    verify(auditLog).record(eq("DELETE_GUIDE"), any(), any());
  }

  @Test
  void deleteWithNothingToRemoveDoesNotAudit() {
    when(controlRepo.findAllById(any())).thenReturn(List.of(second));

    assertEquals(0, service.delete(List.of(second.getId())));
    verify(auditLog, never()).record(any(), any(), any());
  }
}
