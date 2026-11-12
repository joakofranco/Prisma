package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import uy.edu.prisma.domain.entity.AuditObservation;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CatalogSubcategoryRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.AuditObservationDto;
import uy.edu.prisma.web.dto.Dto.CreateAuditObservationDto;
import uy.edu.prisma.web.dto.Dto.UpdateAuditObservationDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditObservationServiceTest {

  @Mock private AuditObservationRepository repo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private CatalogSubcategoryRepository subcategoryRepo;
  @Mock private uy.edu.prisma.domain.repository.EvidenceRepository evidenceRepo;
  @Mock private CurrentUserService currentUser;

  private AuditObservationService service;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    // Por defecto quien consulta es el administrador y la evaluación está en auditoría, que es
    // cuando el auditor trabaja; los casos de la organización y de otros estados lo cambian.
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    service =
        new AuditObservationService(
            repo, evaluationRepo, controlRepo, subcategoryRepo, evidenceRepo, currentUser);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .status(Evaluation.Status.IN_AUDIT)
            .build();
  }

  @Test
  void listByEvaluationReturnsDtos() {
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("obs")
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(obs));

    List<AuditObservationDto> result = service.listByEvaluation(eval.getId());

    assertEquals(1, result.size());
    assertEquals(eval.getId(), result.get(0).evaluationId());
    assertEquals("OBSERVATION", result.get(0).type());
    assertEquals("OPEN", result.get(0).status());
    assertNull(result.get(0).createdBy());
    assertNull(result.get(0).createdByName());
  }

  @Test
  void listByEvaluationIncludesAuditorStampName() {
    User auditor = User.builder().id(UUID.randomUUID()).firstName("Aud").lastName("Itor").build();
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("obs")
            .createdBy(auditor)
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(obs));

    List<AuditObservationDto> result = service.listByEvaluation(eval.getId());

    assertEquals(auditor.getId(), result.get(0).createdBy());
    assertEquals("Aud Itor", result.get(0).createdByName());
  }

  @Test
  void createPersistsObservationWithDefaults() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, null, null, "detalle", null);
    AuditObservationDto result = service.create(dto);

    assertEquals(eval.getId(), result.evaluationId());
    assertEquals("OBSERVATION", result.type());
    assertEquals("OPEN", result.status());
    assertEquals("detalle", result.description());
  }

  @Test
  void createUsesProvidedTypeAndStatus() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(currentUser.currentUser())
        .thenReturn(Optional.of(User.builder().id(UUID.randomUUID()).build()));
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, null, "NON_CONFORMITY", "x", "CLOSED");
    AuditObservationDto result = service.create(dto);

    assertEquals("NON_CONFORMITY", result.type());
    assertEquals("CLOSED", result.status());
    assertNotNull(result.createdBy());
  }

  @Test
  void createSetsTheControlWhenProvided() {
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).code("PL.1-1").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), control.getId(), null, null, "detalle", null);
    AuditObservationDto result = service.create(dto);

    assertEquals(control.getId(), result.controlId());
  }

  @Test
  void theDtoCarriesTheControlAndSubcategoryCodesForTheOrganization() {
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).code("PL.1-1").build();
    CatalogSubcategory subcategory =
        CatalogSubcategory.builder().id(UUID.randomUUID()).code("GV.OC").build();
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .control(control)
            .subcategory(subcategory)
            .description("o")
            .build();
    AuditObservation general =
        AuditObservation.builder().id(UUID.randomUUID()).evaluation(eval).description("g").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId()))
        .thenReturn(List.of(obs, general));

    List<AuditObservationDto> result = service.listByEvaluation(eval.getId());

    assertEquals("PL.1-1", result.get(0).controlCode());
    assertEquals("GV.OC", result.get(0).subcategoryCode());
    assertNull(result.get(1).controlCode());
    assertNull(result.get(1).subcategoryCode());
  }

  @Test
  void createSetsTheSubcategoryWhenProvided() {
    // RF-AUD-05: además de por control, el auditor puede registrar una observación atada a una
    // subcategoría del catálogo.
    CatalogSubcategory subcategory = CatalogSubcategory.builder().id(UUID.randomUUID()).build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(subcategoryRepo.findById(subcategory.getId())).thenReturn(Optional.of(subcategory));
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(
            eval.getId(), null, subcategory.getId(), null, "detalle", null);
    AuditObservationDto result = service.create(dto);

    assertEquals(subcategory.getId(), result.subcategoryId());
  }

  @Test
  void createThrowsWhenSubcategoryIdDoesNotExist() {
    UUID subcategoryId = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(subcategoryRepo.findById(subcategoryId)).thenReturn(Optional.empty());

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, subcategoryId, null, "detalle", null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createThrowsWhenControlIdDoesNotExist() {
    UUID controlId = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.empty());

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), controlId, null, null, "detalle", null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createThrowsWhenEvaluationMissing() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.empty());

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, null, null, "x", null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createRejectsInvalidType() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, null, "BOGUS", "x", null);

    assertThrows(InvalidRequestException.class, () -> service.create(dto));
  }

  @Test
  void updateModifiesFields() {
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("old")
            .build();
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateAuditObservationDto dto =
        new UpdateAuditObservationDto(null, null, "RECOMMENDATION", "nueva", "RESOLVED");
    AuditObservationDto result = service.update(obs.getId(), dto);

    assertEquals("nueva", result.description());
    assertEquals("RECOMMENDATION", result.type());
    assertEquals("RESOLVED", result.status());
  }

  @Test
  void updateSetsTheControlWhenProvided() {
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("old")
            .build();
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).build();
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateAuditObservationDto dto =
        new UpdateAuditObservationDto(control.getId(), null, null, null, null);
    AuditObservationDto result = service.update(obs.getId(), dto);

    assertEquals(control.getId(), result.controlId());
  }

  @Test
  void updateSetsTheSubcategoryWhenProvided() {
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("old")
            .build();
    CatalogSubcategory subcategory = CatalogSubcategory.builder().id(UUID.randomUUID()).build();
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));
    when(subcategoryRepo.findById(subcategory.getId())).thenReturn(Optional.of(subcategory));
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateAuditObservationDto dto =
        new UpdateAuditObservationDto(null, subcategory.getId(), null, null, null);
    AuditObservationDto result = service.update(obs.getId(), dto);

    assertEquals(subcategory.getId(), result.subcategoryId());
  }

  @Test
  void updateThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    UpdateAuditObservationDto dto = new UpdateAuditObservationDto(null, null, "x", "y", "z");

    assertThrows(ResourceNotFoundException.class, () -> service.update(id, dto));
  }

  // ---- PRI-354: escritura solo en IN_AUDIT; lectura de la organización desde la devolución ----

  private static final List<Evaluation.Status> NOT_IN_AUDIT =
      List.of(
          Evaluation.Status.DRAFT,
          Evaluation.Status.IN_PROGRESS,
          Evaluation.Status.READY_FOR_AUDIT,
          Evaluation.Status.APPROVED,
          Evaluation.Status.RETURNED,
          Evaluation.Status.ARCHIVED);

  private AuditObservation existingObservation() {
    AuditObservation obs =
        AuditObservation.builder().id(UUID.randomUUID()).evaluation(eval).description("d").build();
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));
    return obs;
  }

  private void asOrganizationUser() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
  }

  @Test
  void createIsRejectedWithTheCurrentStatusOutsideOfAudit() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, null, null, "x", null);

    for (Evaluation.Status status : NOT_IN_AUDIT) {
      eval.setStatus(status);
      ConflictException error =
          assertThrows(ConflictException.class, () -> service.create(dto), status.name());
      assertTrue(error.getMessage().contains("en auditoría"), error.getMessage());
      assertTrue(error.getMessage().contains(status.name()), error.getMessage());
    }
    verify(repo, never()).save(any());
  }

  @Test
  void updateIsRejectedOutsideOfAuditEvenForTheStatusChange() {
    AuditObservation obs = existingObservation();
    UpdateAuditObservationDto statusChange =
        new UpdateAuditObservationDto(null, null, null, null, "RESOLVED");

    for (Evaluation.Status status : NOT_IN_AUDIT) {
      eval.setStatus(status);
      assertThrows(
          ConflictException.class, () -> service.update(obs.getId(), statusChange), status.name());
    }
    assertEquals(AuditObservation.ObservationStatus.OPEN, obs.getStatus());
    verify(repo, never()).save(any());
  }

  @Test
  void updateWithAnEmptyDescriptionIsRejectedInsteadOfIgnored() {
    AuditObservation obs = existingObservation();

    for (String blank : List.of("", "   ")) {
      InvalidRequestException error =
          assertThrows(
              InvalidRequestException.class,
              () ->
                  service.update(
                      obs.getId(), new UpdateAuditObservationDto(null, null, null, blank, null)));
      assertTrue(error.getMessage().contains("descripción"));
    }
    assertEquals("d", obs.getDescription());
  }

  @Test
  void updateWithoutDescriptionKeepsTheCurrentOne() {
    AuditObservation obs = existingObservation();

    AuditObservationDto result =
        service.update(
            obs.getId(), new UpdateAuditObservationDto(null, null, "RECOMMENDATION", null, null));

    assertEquals("d", result.description());
    assertEquals("RECOMMENDATION", result.type());
  }

  @Test
  void updateOfAnotherOrganizationIsRejectedByTheAccessCheck() {
    AuditObservation obs = existingObservation();
    doThrow(new org.springframework.security.access.AccessDeniedException("otra organización"))
        .when(currentUser)
        .assertOrganizationAccess(any());

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            service.update(
                obs.getId(), new UpdateAuditObservationDto(null, null, null, "x", null)));
  }

  @Test
  void theOrganizationReadsTheObservationsOnceTheEvaluationIsReturned() {
    asOrganizationUser();
    AuditObservation obs =
        AuditObservation.builder().id(UUID.randomUUID()).evaluation(eval).description("o").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(obs));

    for (Evaluation.Status status :
        List.of(
            Evaluation.Status.RETURNED, Evaluation.Status.APPROVED, Evaluation.Status.ARCHIVED)) {
      eval.setStatus(status);
      assertEquals(1, service.listByEvaluation(eval.getId()).size(), status.name());
    }
  }

  @Test
  void afterResumingTheOrganizationKeepsSeeingTheObservationsWhileItCorrects() {
    // RF-AUD-08 (PRI-298): «Reanudar» la pasa a IN_PROGRESS y, al reenviarla, a READY_FOR_AUDIT;
    // la organización tiene que seguir viendo qué le pidió corregir el auditor.
    asOrganizationUser();
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("corregir la política de retención")
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(obs));

    for (Evaluation.Status status :
        List.of(Evaluation.Status.IN_PROGRESS, Evaluation.Status.READY_FOR_AUDIT)) {
      eval.setStatus(status);
      List<AuditObservationDto> visible = service.listByEvaluation(eval.getId());
      assertEquals(1, visible.size(), status.name());
      assertEquals("corregir la política de retención", visible.get(0).description());
    }
  }

  @Test
  void inTheFirstCycleThereIsNothingToShowBecauseObservationsOnlyExistAfterAnAudit() {
    asOrganizationUser();
    eval.setStatus(Evaluation.Status.IN_PROGRESS);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of());

    assertTrue(service.listByEvaluation(eval.getId()).isEmpty());
  }

  @Test
  void theOrganizationGetsAnEmptyListWhileTheAuditorIsWorkingOrBeforeAnyAudit() {
    asOrganizationUser();
    AuditObservation draft =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("borrador")
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(draft));

    // IN_AUDIT: el auditor está trabajando y sus observaciones son un borrador (PRI-354).
    for (Evaluation.Status status : List.of(Evaluation.Status.DRAFT, Evaluation.Status.IN_AUDIT)) {
      eval.setStatus(status);
      assertTrue(service.listByEvaluation(eval.getId()).isEmpty(), status.name());
    }
    // Ni siquiera se consulta la base: no se arma una lista para descartarla.
    verify(repo, never()).findByEvaluationIdOrderByCreatedAtDesc(any());
  }

  @Test
  void theAuditorAndTheAdminSeeTheObservationsInAnyStatus() {
    AuditObservation obs =
        AuditObservation.builder().id(UUID.randomUUID()).evaluation(eval).description("o").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(obs));

    for (boolean admin : List.of(true, false)) {
      when(currentUser.isPrismaAdmin()).thenReturn(admin);
      when(currentUser.isAuditor()).thenReturn(!admin);
      for (Evaluation.Status status : Evaluation.Status.values()) {
        eval.setStatus(status);
        assertEquals(1, service.listByEvaluation(eval.getId()).size(), status.name());
      }
    }
  }

  @Test
  void anotherOrganizationCannotReadEvenAfterTheReturn() {
    asOrganizationUser();
    eval.setStatus(Evaluation.Status.RETURNED);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    doThrow(new org.springframework.security.access.AccessDeniedException("otra organización"))
        .when(currentUser)
        .assertOrganizationAccess(any());

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.listByEvaluation(eval.getId()));
    verify(repo, never()).findByEvaluationIdOrderByCreatedAtDesc(any());
  }

  @Test
  void updateRejectsInvalidStatus() {
    AuditObservation obs =
        AuditObservation.builder().id(UUID.randomUUID()).evaluation(eval).description("d").build();
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));

    UpdateAuditObservationDto dto = new UpdateAuditObservationDto(null, null, null, null, "NOPE");

    assertThrows(InvalidRequestException.class, () -> service.update(obs.getId(), dto));
  }

  // ---- PRI-360 · RF-EVI-15: la observación queda ligada a la evidencia citada ----

  private uy.edu.prisma.domain.entity.Evidence evidenceOf(Evaluation evaluation) {
    return uy.edu.prisma.domain.entity.Evidence.builder()
        .id(UUID.randomUUID())
        .evaluation(evaluation)
        .fileName("politica.pdf")
        .build();
  }

  @Test
  void createLinksTheObservationToTheCitedEvidence() {
    var evidence = evidenceOf(eval);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditObservationDto result =
        service.create(
            new CreateAuditObservationDto(
                eval.getId(), null, null, null, "Revisión de evidencia", null, evidence.getId()));

    assertEquals(evidence.getId(), result.evidenceId());
    assertEquals("politica.pdf", result.evidenceFileName());
  }

  @Test
  void createWithoutEvidenceLeavesItNull() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditObservationDto result =
        service.create(
            new CreateAuditObservationDto(eval.getId(), null, null, null, "general", null));

    assertNull(result.evidenceId());
    assertNull(result.evidenceFileName());
    verifyNoInteractions(evidenceRepo);
  }

  @Test
  void createRejectsEvidenceFromAnotherEvaluation() {
    Evaluation other =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .build();
    var foreign = evidenceOf(other);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evidenceRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

    assertThrows(
        InvalidRequestException.class,
        () ->
            service.create(
                new CreateAuditObservationDto(
                    eval.getId(), null, null, null, "x", null, foreign.getId())));
    verify(repo, never()).save(any());
  }

  @Test
  void createRejectsUnknownEvidence() {
    UUID missing = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evidenceRepo.findById(missing)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () ->
            service.create(
                new CreateAuditObservationDto(eval.getId(), null, null, null, "x", null, missing)));
  }

  @Test
  void updateCanLinkEvidenceAndRejectsAnotherEvaluationsOne() {
    AuditObservation obs =
        AuditObservation.builder().id(UUID.randomUUID()).evaluation(eval).description("d").build();
    var own = evidenceOf(eval);
    var foreign =
        evidenceOf(
            Evaluation.builder()
                .id(UUID.randomUUID())
                .organization(Organization.builder().id(UUID.randomUUID()).build())
                .build());
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));
    when(evidenceRepo.findById(own.getId())).thenReturn(Optional.of(own));
    when(evidenceRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditObservationDto linked =
        service.update(
            obs.getId(), new UpdateAuditObservationDto(null, null, null, null, null, own.getId()));
    assertEquals(own.getId(), linked.evidenceId());

    assertThrows(
        InvalidRequestException.class,
        () ->
            service.update(
                obs.getId(),
                new UpdateAuditObservationDto(null, null, null, null, null, foreign.getId())));
  }

  @Test
  void updateWithoutEvidenceIdKeepsTheExistingLink() {
    var own = evidenceOf(eval);
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("d")
            .evidence(own)
            .build();
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));
    when(repo.save(any(AuditObservation.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditObservationDto result =
        service.update(obs.getId(), new UpdateAuditObservationDto(null, null, null, "nuevo", null));

    assertEquals(own.getId(), result.evidenceId());
    verifyNoInteractions(evidenceRepo);
  }

  @Test
  void listShowsTheEvidenceFileNameToWhoeverReadsTheObservation() {
    var evidence = evidenceOf(eval);
    AuditObservation obs =
        AuditObservation.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .description("obs")
            .evidence(evidence)
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(obs));
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    List<AuditObservationDto> result = service.listByEvaluation(eval.getId());

    assertEquals("politica.pdf", result.get(0).evidenceFileName());
  }
}
