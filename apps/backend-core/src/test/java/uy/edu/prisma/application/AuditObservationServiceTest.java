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
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
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
  @Mock private CurrentUserService currentUser;

  private AuditObservationService service;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    service = new AuditObservationService(repo, evaluationRepo, controlRepo, currentUser);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .organization(Organization.builder().id(UUID.randomUUID()).build())
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
    User auditor =
        User.builder().id(UUID.randomUUID()).firstName("Aud").lastName("Itor").build();
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
        new CreateAuditObservationDto(eval.getId(), null, null, "detalle", null);
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
        new CreateAuditObservationDto(eval.getId(), null, "NON_CONFORMITY", "x", "CLOSED");
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
        new CreateAuditObservationDto(eval.getId(), control.getId(), null, "detalle", null);
    AuditObservationDto result = service.create(dto);

    assertEquals(control.getId(), result.controlId());
  }

  @Test
  void createThrowsWhenControlIdDoesNotExist() {
    UUID controlId = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.empty());

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), controlId, null, "detalle", null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createThrowsWhenEvaluationMissing() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.empty());

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, null, "x", null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createRejectsInvalidType() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    CreateAuditObservationDto dto =
        new CreateAuditObservationDto(eval.getId(), null, "BOGUS", "x", null);

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
        new UpdateAuditObservationDto(null, "RECOMMENDATION", "nueva", "RESOLVED");
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

    UpdateAuditObservationDto dto = new UpdateAuditObservationDto(control.getId(), null, null, null);
    AuditObservationDto result = service.update(obs.getId(), dto);

    assertEquals(control.getId(), result.controlId());
  }

  @Test
  void updateThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    UpdateAuditObservationDto dto = new UpdateAuditObservationDto(null, "x", "y", "z");

    assertThrows(ResourceNotFoundException.class, () -> service.update(id, dto));
  }

  @Test
  void updateRejectsInvalidStatus() {
    AuditObservation obs =
        AuditObservation.builder().id(UUID.randomUUID()).evaluation(eval).description("d").build();
    when(repo.findById(obs.getId())).thenReturn(Optional.of(obs));

    UpdateAuditObservationDto dto = new UpdateAuditObservationDto(null, null, null, "NOPE");

    assertThrows(InvalidRequestException.class, () -> service.update(obs.getId(), dto));
  }
}
