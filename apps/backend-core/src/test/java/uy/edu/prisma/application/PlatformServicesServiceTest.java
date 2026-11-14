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
import uy.edu.prisma.domain.entity.PlatformService;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.PlatformServiceRepository;
import uy.edu.prisma.web.dto.Dto.PlatformServiceDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformServicesServiceTest {

  @Mock private PlatformServiceRepository repo;
  @Mock private AuditLogService auditLog;

  private PlatformServicesService service;
  private PlatformService grafana;
  private PlatformService portainer;

  @BeforeEach
  void setUp() {
    grafana = row("grafana", "http://localhost:3000", 10);
    portainer = row("portainer", "https://localhost:9443/#!/home", 20);
    when(repo.findAllByOrderBySortOrderAsc()).thenReturn(List.of(grafana, portainer));
    when(repo.findById(grafana.getId())).thenReturn(Optional.of(grafana));
    when(repo.save(any(PlatformService.class))).thenAnswer(inv -> inv.getArgument(0));
    service = new PlatformServicesService(repo, auditLog);
  }

  private static PlatformService row(String key, String url, int order) {
    return PlatformService.builder()
        .id(UUID.randomUUID())
        .serviceKey(key)
        .name(key)
        .description("d")
        .category("OBSERVABILITY")
        .url(url)
        .defaultUrl(url)
        .sortOrder(order)
        .build();
  }

  @Test
  void listReturnsTheServicesInOrder() {
    List<PlatformServiceDto> all = service.list();

    assertEquals(2, all.size());
    assertEquals("grafana", all.get(0).key());
    assertEquals("http://localhost:3000", all.get(0).url());
  }

  @Test
  void updateUrlSavesAndRecordsTheChange() {
    PlatformServiceDto dto = service.updateUrl(grafana.getId(), " https://10.8.0.1:3000/d ");

    assertEquals("https://10.8.0.1:3000/d", dto.url());
    assertEquals("http://localhost:3000", dto.defaultUrl());
    verify(repo).save(grafana);
    verify(auditLog)
        .record(
            eq("UPDATE"),
            eq("platform-service"),
            eq(
                "{\"service\":\"grafana\",\"from\":\"http://localhost:3000\","
                    + "\"to\":\"https://10.8.0.1:3000/d\"}"));
  }

  @Test
  void updateUrlWithTheSameValueIsNotRecorded() {
    service.updateUrl(grafana.getId(), "http://localhost:3000");

    verify(repo, never()).save(any());
    verify(auditLog, never()).record(anyString(), anyString(), any());
  }

  @Test
  void invalidUrlsAreRejected() {
    for (String bad :
        new String[] {
          null,
          "  ",
          "localhost:3000",
          "ftp://localhost",
          "javascript:alert(1)",
          "http://",
          "http://user:pass@localhost",
          "http://exa mple.com",
          "http://localhost/" + "a".repeat(500),
        }) {
      assertThrows(
          InvalidRequestException.class,
          () -> service.updateUrl(grafana.getId(), bad),
          String.valueOf(bad));
    }
    verify(repo, never()).save(any());
  }

  @Test
  void updateUrlOfAnUnknownServiceIsNotFound() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> service.updateUrl(UUID.randomUUID(), "http://localhost:1"));
  }

  @Test
  void resetRestoresTheFactoryUrl() {
    grafana.setUrl("http://otro:1");

    PlatformServiceDto dto = service.reset(grafana.getId());

    assertEquals("http://localhost:3000", dto.url());
  }

  @Test
  void replaceHostKeepsSchemePortAndPath() {
    List<PlatformServiceDto> result = service.replaceHost(" 10.8.0.1 ");

    assertEquals("http://10.8.0.1:3000", result.get(0).url());
    assertEquals("https://10.8.0.1:9443/#!/home", result.get(1).url());
    verify(auditLog, times(2)).record(eq("UPDATE"), eq("platform-service"), anyString());
  }

  @Test
  void replaceHostAcceptsNamesAndIpv6() {
    assertDoesNotThrow(() -> service.replaceHost("prisma.home-uy.site"));
    assertDoesNotThrow(() -> service.replaceHost("[fd00::1]"));
  }

  @Test
  void replaceHostRejectsAnythingButAHost() {
    for (String bad :
        new String[] {null, "", "http://10.8.0.1", "10.8.0.1:3000", "10.8.0.1/x", "a b", "-x"}) {
      assertThrows(
          InvalidRequestException.class, () -> service.replaceHost(bad), String.valueOf(bad));
    }
  }
}
