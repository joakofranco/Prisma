package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.*;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CommunityProfileRepository;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommunityProfileServiceTest {

  @Mock private CommunityProfileRepository repo;
  @Mock private CatalogControlRepository controlRepo;

  private CommunityProfileService service;
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    service = new CommunityProfileService(repo, controlRepo);

    CatalogVersion version = CatalogVersion.builder().id(UUID.randomUUID()).version("5.0").build();
    CatalogRequirement requirement =
        CatalogRequirement.builder().id(UUID.randomUUID()).version(version).build();
    control =
        CatalogControl.builder().id(UUID.randomUUID()).code("CT1").requirement(requirement).build();
  }

  @Test
  void listByVersionMapsSummaries() {
    CommunityProfile profile =
        CommunityProfile.builder()
            .id(UUID.randomUUID())
            .name("PYME")
            .description("desc")
            .catalogVersion("5.0")
            .controls(Set.of(control))
            .build();
    when(repo.findByCatalogVersionOrderByNameAsc("5.0")).thenReturn(List.of(profile));

    List<CommunityProfileSummaryDto> result = service.listByVersion("5.0");

    assertEquals(1, result.size());
    assertEquals("PYME", result.get(0).name());
    assertEquals(1, result.get(0).controlCount());
  }

  @Test
  void getByIdReturnsControlIds() {
    UUID id = UUID.randomUUID();
    CommunityProfile profile =
        CommunityProfile.builder()
            .id(id)
            .name("PYME")
            .catalogVersion("5.0")
            .controls(Set.of(control))
            .build();
    when(repo.findById(id)).thenReturn(Optional.of(profile));

    CommunityProfileDto dto = service.getById(id);

    assertEquals(Set.of(control.getId()), dto.controlIds());
  }

  @Test
  void getByIdThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.getById(id));
  }

  @Test
  void createPersistsProfileWithResolvedControls() {
    when(controlRepo.findAllById(Set.of(control.getId()))).thenReturn(List.of(control));
    when(repo.save(any(CommunityProfile.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateCommunityProfileDto dto =
        new CreateCommunityProfileDto("PYME", "desc", "5.0", Set.of(control.getId()));
    CommunityProfileDto result = service.create(dto);

    assertEquals("PYME", result.name());
    assertEquals(Set.of(control.getId()), result.controlIds());
  }

  @Test
  void createThrowsWhenNoControlsProvided() {
    CreateCommunityProfileDto dto = new CreateCommunityProfileDto("PYME", "desc", "5.0", Set.of());

    assertThrows(RuntimeException.class, () -> service.create(dto));
    verify(repo, never()).save(any());
  }

  @Test
  void createThrowsWhenControlDoesNotExist() {
    UUID missingId = UUID.randomUUID();
    when(controlRepo.findAllById(Set.of(missingId))).thenReturn(List.of());

    CreateCommunityProfileDto dto =
        new CreateCommunityProfileDto("PYME", "desc", "5.0", Set.of(missingId));

    assertThrows(RuntimeException.class, () -> service.create(dto));
  }

  @Test
  void createThrowsWhenControlBelongsToDifferentCatalogVersion() {
    when(controlRepo.findAllById(Set.of(control.getId()))).thenReturn(List.of(control));

    // El control armado en setUp() pertenece a la version "5.0", se pide crear el perfil en "4.0".
    CreateCommunityProfileDto dto =
        new CreateCommunityProfileDto("Otra", "desc", "4.0", Set.of(control.getId()));

    assertThrows(RuntimeException.class, () -> service.create(dto));
    verify(repo, never()).save(any());
  }

  @Test
  void updateReplacesNameDescriptionAndControls() {
    UUID id = UUID.randomUUID();
    CommunityProfile profile =
        CommunityProfile.builder()
            .id(id)
            .name("Viejo")
            .catalogVersion("5.0")
            .controls(Set.of())
            .build();
    when(repo.findById(id)).thenReturn(Optional.of(profile));
    when(controlRepo.findAllById(Set.of(control.getId()))).thenReturn(List.of(control));

    CreateCommunityProfileDto dto =
        new CreateCommunityProfileDto("Nuevo", "desc nueva", "5.0", Set.of(control.getId()));
    CommunityProfileDto result = service.update(id, dto);

    assertEquals("Nuevo", result.name());
    assertEquals(Set.of(control.getId()), result.controlIds());
    verify(repo, never()).save(any()); // dirty-checking, no hace falta save() explícito
  }

  @Test
  void updateThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    CreateCommunityProfileDto dto =
        new CreateCommunityProfileDto("X", null, "5.0", Set.of(control.getId()));

    assertThrows(RuntimeException.class, () -> service.update(id, dto));
  }

  @Test
  void deleteRemovesProfile() {
    UUID id = UUID.randomUUID();
    when(repo.existsById(id)).thenReturn(true);

    service.delete(id);

    verify(repo, times(1)).deleteById(id);
  }

  @Test
  void deleteThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.existsById(id)).thenReturn(false);

    assertThrows(RuntimeException.class, () -> service.delete(id));
    verify(repo, never()).deleteById(any());
  }
}
